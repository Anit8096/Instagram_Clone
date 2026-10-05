package com.android.insta.feature.auth.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.Session
import com.android.insta.testutil.FakeGoogleSignInClient
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.TEST_USER
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAuthRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val authJson =
        """{"accessToken":"a1","refreshToken":"r1","expiresIn":900,"isNewUser":true,
           "user":{"id":"u1","username":"jane.doe","displayName":"Jane","bio":"","createdAt":"2026-10-04T00:00:00Z","phone":"+919876543210"}}"""
    private val challengeJson = """{"challengeId":"c1","sentTo":"+91 ••••••3210","expiresIn":300,"resendIn":30,"devCode":"123456"}"""

    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val store = FakeSessionStore()
    private val google = FakeGoogleSignInClient()

    /** Responds per path; anything unlisted is a 500. */
    private fun repository(responses: Map<String, Pair<HttpStatusCode, String>>) = DefaultAuthRepository(
        api = AuthApi(
            createHttpClient(
                engine = MockEngine { request ->
                    requests += "${request.method.value} ${request.url.encodedPath}"
                    bodies += (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
                    val (status, body) = responses[request.url.encodedPath] ?: (HttpStatusCode.InternalServerError to "")
                    if (body.isEmpty()) respond("", status) else respond(body, status, jsonHeaders)
                },
                baseUrl = "http://test",
                sessionStore = store,
                json = json,
                enableLogging = false,
            ),
        ),
        sessionStore = store,
        google = google,
    )

    @Test
    fun `google sign-in of a linked account stores the session with the phone`() = runTest {
        val repo = repository(mapOf("/api/v1/auth/google" to (HttpStatusCode.OK to """{"type":"signed_in","auth":$authJson}""")))
        assertEquals(ApiResult.Success(GoogleSignIn.SignedIn), repo.signInWithGoogle("id-token"))
        assertEquals(Session("a1", "r1", TEST_USER.copy(phone = "+919876543210")), store.current())
    }

    @Test
    fun `a new google user needs onboarding and is not signed in yet`() = runTest {
        val repo = repository(mapOf("/api/v1/auth/google" to (HttpStatusCode.OK to
            """{"type":"needs_onboarding","onboardingToken":"t1","suggestedUsername":"sam.smith","displayName":"Sam"}""")))
        assertEquals(ApiResult.Success(GoogleSignIn.NeedsOnboarding("t1", "sam.smith", "Sam")), repo.signInWithGoogle("id-token"))
        assertNull(store.current())
    }

    @Test
    fun `phone sign-in returns the challenge and signs in on the right code`() = runTest {
        val repo = repository(mapOf(
            "/api/v1/auth/phone/otp" to (HttpStatusCode.OK to challengeJson),
            "/api/v1/auth/phone/verify" to (HttpStatusCode.OK to authJson),
        ))
        assertEquals(ApiResult.Success(OtpChallenge("c1", "+91 ••••••3210", 30, "123456")), repo.requestLoginOtp("+919876543210"))
        assertEquals(ApiResult.Success(Unit), repo.verifyLoginOtp("c1", " 123456 "))
        assertTrue(bodies.last().contains(""""code":"123456""""))
        assertEquals("a1", store.current()?.accessToken)
    }

    @Test
    fun `unknown numbers and wrong codes surface the server code and leave no session`() = runTest {
        val repo = repository(mapOf(
            "/api/v1/auth/phone/otp" to (HttpStatusCode.NotFound to """{"error":{"code":"NO_LINKED_ACCOUNT","message":"No"}}"""),
            "/api/v1/auth/phone/verify" to (HttpStatusCode.BadRequest to """{"error":{"code":"OTP_INVALID","message":"No","details":{"attemptsLeft":"4"}}}"""),
        ))
        assertEquals("NO_LINKED_ACCOUNT", ((repo.requestLoginOtp("+911111111111") as ApiResult.Failure).error as AppError.Api).code)
        val wrong = (repo.verifyLoginOtp("c1", "000000") as ApiResult.Failure).error as AppError.Api
        assertEquals("OTP_INVALID" to "4", wrong.code to wrong.fieldErrors["attemptsLeft"])
        assertNull(store.current())
    }

    @Test
    fun `completing onboarding signs in`() = runTest {
        val repo = repository(mapOf(
            "/api/v1/auth/onboarding/otp" to (HttpStatusCode.OK to challengeJson),
            "/api/v1/auth/onboarding/complete" to (HttpStatusCode.Created to authJson),
        ))
        repo.requestOnboardingOtp("t1", "+919876543210")
        assertEquals(ApiResult.Success(Unit), repo.completeOnboarding("t1", "c1", "123456", " jane.doe ", " Jane "))
        assertTrue(bodies.last().contains(""""username":"jane.doe"""") && bodies.last().contains(""""displayName":"Jane""""))
        assertEquals("jane.doe", store.current()?.user?.username)
    }

    @Test
    fun `logout revokes on the server and clears local state`() = runTest {
        store.save(Session("a1", "r1", TEST_USER))
        repository(mapOf("/api/v1/auth/logout" to (HttpStatusCode.NoContent to ""))).logout()

        assertEquals(listOf("POST /api/v1/auth/logout"), requests)
        assertNull(store.current())
        assertTrue(google.cleared)
    }

    @Test
    fun `deleting the account signs out locally only on success`() = runTest {
        store.save(Session("a1", "r1", TEST_USER))
        val refused = repository(mapOf("/api/v1/me" to (HttpStatusCode.BadRequest to """{"error":{"code":"OTP_INVALID","message":"no"}}""")))
            .deleteAccount("c1", "000000", null)
        assertEquals("OTP_INVALID", ((refused as ApiResult.Failure).error as AppError.Api).code)
        assertEquals("a1", store.current()?.accessToken) // still signed in

        requests.clear()
        assertEquals(ApiResult.Success(Unit), repository(mapOf("/api/v1/me" to (HttpStatusCode.NoContent to ""))).deleteAccount("c1", "123456", null))
        assertEquals(listOf("DELETE /api/v1/me"), requests) // no logout call: the server already revoked everything
        assertNull(store.current())
        assertTrue(google.cleared)
    }
}
