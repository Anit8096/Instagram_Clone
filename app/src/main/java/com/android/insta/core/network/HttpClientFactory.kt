package com.android.insta.core.network

import com.android.insta.core.session.SessionStore
import com.android.insta.feature.auth.data.AuthResponse
import com.android.insta.feature.auth.data.RefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import timber.log.Timber

const val REFRESH_PATH = "api/v1/auth/refresh"

/**
 * The app's single HttpClient. Requests outside `/auth` carry `Authorization: Bearer <access>`.
 * On a 401 the Auth plugin calls [refreshTokens] once (concurrent 401s share the same refresh),
 * persists the rotated pair and retries. If the server rejects the refresh token the session is
 * over: [onSessionExpired] runs and the UI falls back to the login screen.
 */
fun createHttpClient(
    engine: HttpClientEngine,
    baseUrl: String,
    sessionStore: SessionStore,
    json: Json,
    enableLogging: Boolean,
    onSessionExpired: suspend () -> Unit = { sessionStore.clear() },
): HttpClient = HttpClient(engine) {
    expectSuccess = false

    install(ContentNegotiation) { json(json) }
    // Required by RealtimeClient's `webSocket(...)`; pings keep idle sockets alive through proxies.
    install(WebSockets) { pingIntervalMillis = 15_000 }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
    defaultRequest {
        url(baseUrl.trimEnd('/') + "/")
    }
    if (enableLogging) {
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) = Timber.tag("Http").d(message)
            }
            sanitizeHeader { it == HttpHeaders.Authorization }
        }
    }
    install(Auth) {
        bearer {
            loadTokens {
                sessionStore.current()?.let { BearerTokens(it.accessToken, it.refreshToken) }
            }
            refreshTokens {
                val refreshToken = oldTokens?.refreshToken ?: return@refreshTokens null
                val response = client.post(REFRESH_PATH) {
                    markAsRefreshTokenRequest()
                    attributes.put(AuthCircuitBreaker, Unit)
                    jsonBody(RefreshRequest(refreshToken))
                }
                when {
                    response.status.isSuccess() -> {
                        val auth = response.body<AuthResponse>()
                        sessionStore.updateTokens(auth.accessToken, auth.refreshToken)
                        BearerTokens(auth.accessToken, auth.refreshToken)
                    }
                    response.status == HttpStatusCode.Unauthorized -> {
                        onSessionExpired()
                        null
                    }
                    else -> null // Server trouble: keep the session, let this request fail.
                }
            }
            sendWithoutRequest { request -> "auth" !in request.url.pathSegments }
        }
    }
}
