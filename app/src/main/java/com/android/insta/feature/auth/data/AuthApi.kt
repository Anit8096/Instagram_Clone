package com.android.insta.feature.auth.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.post
import com.android.insta.core.network.jsonBody

class AuthApi(private val client: HttpClient) {

    suspend fun register(request: RegisterRequest): ApiResult<AuthResponse> =
        safeApiCall { client.post("api/v1/auth/register") { unauthenticated(); jsonBody(request) } }

    suspend fun login(request: LoginRequest): ApiResult<AuthResponse> =
        safeApiCall { client.post("api/v1/auth/login") { unauthenticated(); jsonBody(request) } }

    suspend fun loginWithGoogle(request: GoogleLoginRequest): ApiResult<AuthResponse> =
        safeApiCall { client.post("api/v1/auth/google") { unauthenticated(); jsonBody(request) } }

    suspend fun logout(request: RefreshRequest): ApiResult<Unit> =
        safeApiCall { client.post("api/v1/auth/logout") { unauthenticated(); jsonBody(request) } }

    /** Authenticated; the server answers 403 REAUTH_FAILED (not 401) on a wrong password, so no refresh loop. */
    suspend fun deleteAccount(request: DeleteAccountRequest): ApiResult<Unit> =
        safeApiCall { client.delete("api/v1/me") { jsonBody(request) } }

    /** Drops the Auth plugin's cached tokens so the next request reloads them from the session store. */
    fun clearCachedTokens() {
        client.authProvider<BearerAuthProvider>()?.clearToken()
    }

    // A 401 from login means "wrong password", not "access token expired": skip refresh handling.
    private fun HttpRequestBuilder.unauthenticated() {
        attributes.put(AuthCircuitBreaker, Unit)
    }
}
