package com.android.insta.core.network

import com.android.insta.core.session.Session
import com.android.insta.feature.auth.data.AuthApi
import com.android.insta.feature.auth.data.GoogleLoginRequest
import com.android.insta.feature.profile.data.ProfileApi
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.TEST_USER
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real [createHttpClient] configuration against a scripted backend. */
class HttpClientAuthTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val store = FakeSessionStore(Session("old-access", "old-refresh", TEST_USER))
    private val refreshCalls = AtomicInteger()

    private val userJson = """{"id":"u1","username":"jane.doe","displayName":"Jane","bio":"","createdAt":"2026-10-04T00:00:00Z"}"""
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val bearerChallenge = headersOf(
        HttpHeaders.ContentType to listOf("application/json"),
        HttpHeaders.WWWAuthenticate to listOf("Bearer realm=\"insta\""),
    )

    private fun client(refreshStatus: HttpStatusCode = HttpStatusCode.OK, extra: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null) =
        createHttpClient(
            engine = MockEngine { request ->
                extra?.invoke(this, request)?.let { return@MockEngine it }
                when (request.url.encodedPath) {
                    "/api/v1/me" ->
                        if (request.headers[HttpHeaders.Authorization] == "Bearer new-access") {
                            respond(userJson, HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respond("""{"error":{"code":"UNAUTHORIZED","message":"expired"}}""", HttpStatusCode.Unauthorized, bearerChallenge)
                        }
                    "/api/v1/auth/refresh" -> {
                        refreshCalls.incrementAndGet()
                        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        if (refreshStatus == HttpStatusCode.OK && "old-refresh" in body) {
                            respond(
                                """{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":900,"user":$userJson}""",
                                HttpStatusCode.OK,
                                jsonHeaders,
                            )
                        } else {
                            respond("""{"error":{"code":"INVALID_REFRESH_TOKEN","message":"no"}}""", refreshStatus, jsonHeaders)
                        }
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
            baseUrl = "http://test",
            sessionStore = store,
            json = json,
            enableLogging = false,
        )

    @Test
    fun `expired access token is refreshed once, persisted, and the request retried`() = runTest {
        val result = ProfileApi(client()).me()

        assertTrue(result is ApiResult.Success)
        assertEquals(1, refreshCalls.get())
        val session = store.current()!!
        assertEquals("new-access", session.accessToken)
        assertEquals("new-refresh", session.refreshToken)
    }

    @Test
    fun `concurrent 401s share a single refresh`() = runTest {
        val api = ProfileApi(client())
        val results = List(5) { async { api.me() } }.awaitAll()

        assertTrue(results.all { it is ApiResult.Success })
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `rejected refresh token ends the session`() = runTest {
        val result = ProfileApi(client(refreshStatus = HttpStatusCode.Unauthorized)).me()

        assertTrue(result is ApiResult.Failure && (result.error as AppError.Api).status == 401)
        assertNull(store.current())
    }

    @Test
    fun `server error during refresh keeps the session`() = runTest {
        ProfileApi(client(refreshStatus = HttpStatusCode.ServiceUnavailable)).me()
        assertEquals("old-access", store.current()?.accessToken)
    }

    @Test
    fun `a rejected google token on sign-in does not trigger a token refresh`() = runTest {
        val client = client { request ->
            if (request.url.encodedPath == "/api/v1/auth/google") {
                respond("""{"error":{"code":"INVALID_GOOGLE_TOKEN","message":"Invalid"}}""", HttpStatusCode.Unauthorized, bearerChallenge)
            } else {
                null
            }
        }
        val result = AuthApi(client).loginWithGoogle(GoogleLoginRequest("forged"))

        assertEquals("INVALID_GOOGLE_TOKEN", ((result as ApiResult.Failure).error as AppError.Api).code)
        assertEquals(0, refreshCalls.get())
        assertEquals("old-access", store.current()?.accessToken)
    }

    @Test
    fun `connection failure maps to a network error`() = runTest {
        val client = client { throw java.io.IOException("connection refused") }
        assertEquals(ApiResult.Failure(AppError.Network), ProfileApi(client).me())
    }
}
