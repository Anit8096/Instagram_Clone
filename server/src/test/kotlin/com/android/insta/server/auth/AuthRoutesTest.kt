package com.android.insta.server.auth

import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.support.FakeGoogleVerifier
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.users.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthRoutesTest : IntegrationTest() {

    private suspend fun HttpClient.postJson(path: String, body: Any): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.register(
        username: String = "jane.doe",
        email: String = "jane@example.com",
        password: String = "correct-horse",
    ) = postJson("/api/v1/auth/register", RegisterRequest(username, email, password, "Jane"))

    @Test
    fun `register returns tokens and a usable access token`() = withApp { client ->
        val response = client.register(username = "  Jane.Doe ")
        assertEquals(HttpStatusCode.Created, response.status)
        val auth = response.body<AuthResponse>()
        assertEquals("jane.doe", auth.user.username)
        assertTrue(auth.isNewUser)

        val me = client.get("/api/v1/me") { bearerAuth(auth.accessToken) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(auth.user.id, me.body<UserDto>().id)
    }

    @Test
    fun `register rejects invalid fields with details`() = withApp { client ->
        val response = client.postJson("/api/v1/auth/register", RegisterRequest("x", "nope", "short"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ErrorEnvelope>().error
        assertEquals("VALIDATION_ERROR", error.code)
        assertEquals(setOf("username", "email", "password"), error.details?.keys)
    }

    @Test
    fun `duplicate username and email are conflicts`() = withApp { client ->
        client.register()
        val sameName = client.register(email = "other@example.com")
        assertEquals(HttpStatusCode.Conflict, sameName.status)
        assertEquals("USERNAME_TAKEN", sameName.body<ErrorEnvelope>().error.code)

        val sameEmail = client.register(username = "someone.else", email = "JANE@example.com")
        assertEquals(HttpStatusCode.Conflict, sameEmail.status)
        assertEquals("EMAIL_TAKEN", sameEmail.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `login works with username or email and rejects wrong password`() = withApp { client ->
        client.register()
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/auth/login", LoginRequest("jane.doe", "correct-horse")).status)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/auth/login", LoginRequest("Jane@Example.com", "correct-horse")).status)

        val wrong = client.postJson("/api/v1/auth/login", LoginRequest("jane.doe", "wrong-password"))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("INVALID_CREDENTIALS", wrong.body<ErrorEnvelope>().error.code)

        val unknown = client.postJson("/api/v1/auth/login", LoginRequest("nobody", "correct-horse"))
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
    }

    @Test
    fun `me requires a valid access token`() = withApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me").status)
        val bad = client.get("/api/v1/me") { bearerAuth("not-a-jwt") }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("UNAUTHORIZED", bad.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `refresh rotates and reusing an old token revokes the whole session`() = withApp { client ->
        val first = client.register().body<AuthResponse>()

        val second = client.postJson("/api/v1/auth/refresh", RefreshRequest(first.refreshToken))
        assertEquals(HttpStatusCode.OK, second.status)
        val rotated = second.body<AuthResponse>()
        assertNotEquals(first.refreshToken, rotated.refreshToken)

        // Replaying the first token looks like theft: rejected, and its successor dies too.
        val replay = client.postJson("/api/v1/auth/refresh", RefreshRequest(first.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        val successor = client.postJson("/api/v1/auth/refresh", RefreshRequest(rotated.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, successor.status)
        assertEquals("INVALID_REFRESH_TOKEN", successor.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `sessions are independent and logout revokes only its own`() = withApp { client ->
        client.register()
        val phone = client.postJson("/api/v1/auth/login", LoginRequest("jane.doe", "correct-horse")).body<AuthResponse>()
        val tablet = client.postJson("/api/v1/auth/login", LoginRequest("jane.doe", "correct-horse")).body<AuthResponse>()

        assertEquals(HttpStatusCode.NoContent, client.postJson("/api/v1/auth/logout", RefreshRequest(phone.refreshToken)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/auth/refresh", RefreshRequest(phone.refreshToken)).status)
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/auth/refresh", RefreshRequest(tablet.refreshToken)).status)
    }

    @Test
    fun `google sign-in creates, then returns, the same account`() {
        val google = FakeGoogleVerifier(
            mapOf("token-1" to GoogleIdentity("google-sub-1", "sam.smith@gmail.com", emailVerified = true, name = "Sam Smith")),
        )
        withApp(google = google) { client ->
            val created = client.postJson("/api/v1/auth/google", GoogleLoginRequest("token-1")).body<AuthResponse>()
            assertTrue(created.isNewUser)
            assertEquals("sam.smith", created.user.username)
            assertEquals("Sam Smith", created.user.displayName)

            val again = client.postJson("/api/v1/auth/google", GoogleLoginRequest("token-1")).body<AuthResponse>()
            assertFalse(again.isNewUser)
            assertEquals(created.user.id, again.user.id)

            val invalid = client.postJson("/api/v1/auth/google", GoogleLoginRequest("forged"))
            assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        }
    }

    @Test
    fun `google sign-in links to an existing account with the same verified email`() {
        val google = FakeGoogleVerifier(
            mapOf(
                "verified" to GoogleIdentity("sub-a", "jane@example.com", emailVerified = true, name = "Jane"),
                "unverified" to GoogleIdentity("sub-b", "jane@example.com", emailVerified = false, name = "Jane"),
            ),
        )
        withApp(google = google) { client ->
            val registered = client.register().body<AuthResponse>()

            // Unverified email must not take over the existing account; it gets a fresh one.
            val unverified = client.postJson("/api/v1/auth/google", GoogleLoginRequest("unverified")).body<AuthResponse>()
            assertNotEquals(registered.user.id, unverified.user.id)

            val linked = client.postJson("/api/v1/auth/google", GoogleLoginRequest("verified")).body<AuthResponse>()
            assertEquals(registered.user.id, linked.user.id)
            assertFalse(linked.isNewUser)
        }
    }

    @Test
    fun `auth endpoints are rate limited`() = withApp(config = testConfig(authRequestsPerMinute = 3)) { client ->
        repeat(3) { client.postJson("/api/v1/auth/login", LoginRequest("nobody", "whatever-pass")) }
        val limited = client.postJson("/api/v1/auth/login", LoginRequest("nobody", "whatever-pass"))
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", limited.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `malformed body is a 400 envelope`() = withApp { client ->
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `health reports database up and swagger is served`() = withApp { client ->
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/docs").status)
    }
}
