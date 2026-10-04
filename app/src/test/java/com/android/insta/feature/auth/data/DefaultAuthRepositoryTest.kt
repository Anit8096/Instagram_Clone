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
           "user":{"id":"u1","username":"jane.doe","displayName":"Jane","bio":"","createdAt":"2026-10-04T00:00:00Z"}}"""

    private val requests = mutableListOf<String>()
    private val store = FakeSessionStore()
    private val google = FakeGoogleSignInClient()

    private fun repository(status: HttpStatusCode = HttpStatusCode.OK, body: String = authJson) = DefaultAuthRepository(
        api = AuthApi(
            createHttpClient(
                engine = MockEngine { request ->
                    requests += "${request.method.value} ${request.url.encodedPath}"
                    if (request.url.encodedPath == "/api/v1/auth/logout") respond("", HttpStatusCode.NoContent)
                    else respond(body, status, jsonHeaders)
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
    fun `login stores the session`() = runTest {
        val result = repository().login(" jane.doe ", "pw")

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(Session("a1", "r1", TEST_USER), store.current())
        assertEquals(listOf("POST /api/v1/auth/login"), requests)
    }

    @Test
    fun `register stores the session`() = runTest {
        repository().register("jane.doe", "jane@example.com", "correct-horse", "")
        assertEquals("a1", store.current()?.accessToken)
    }

    @Test
    fun `failed login leaves no session and surfaces the server code`() = runTest {
        val result = repository(HttpStatusCode.Unauthorized, """{"error":{"code":"INVALID_CREDENTIALS","message":"Incorrect"}}""")
            .login("jane.doe", "wrong")

        assertEquals("INVALID_CREDENTIALS", ((result as ApiResult.Failure).error as AppError.Api).code)
        assertNull(store.current())
    }

    @Test
    fun `logout revokes on the server and clears local state`() = runTest {
        store.save(Session("a1", "r1", TEST_USER))
        repository().logout()

        assertEquals(listOf("POST /api/v1/auth/logout"), requests)
        assertNull(store.current())
        assertTrue(google.cleared)
    }

    @Test
    fun `deleting the account signs out locally only on success`() = runTest {
        store.save(Session("a1", "r1", TEST_USER))
        val refused = repository(HttpStatusCode.Forbidden, """{"error":{"code":"REAUTH_FAILED","message":"no"}}""").deleteAccount("wrong", null)
        assertEquals("REAUTH_FAILED", ((refused as ApiResult.Failure).error as AppError.Api).code)
        assertEquals("a1", store.current()?.accessToken) // still signed in

        requests.clear()
        assertEquals(ApiResult.Success(Unit), repository(HttpStatusCode.NoContent, "").deleteAccount("right", null))
        assertEquals(listOf("DELETE /api/v1/me"), requests) // no logout call: the server already revoked everything
        assertNull(store.current())
        assertTrue(google.cleared)
    }
}
