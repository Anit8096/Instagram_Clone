package com.android.insta.feature.auth.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.jsonBody
import com.android.insta.core.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.post

class AuthApi(private val client: HttpClient) {

    suspend fun loginWithGoogle(request: GoogleLoginRequest): ApiResult<GoogleAuthResultDto> =
        safeApiCall { client.post("api/v1/auth/google") { unauthenticated(); jsonBody(request) } }

    suspend fun requestLoginOtp(request: PhoneOtpRequest): ApiResult<OtpChallengeDto> =
        safeApiCall { client.post("api/v1/auth/phone/otp") { unauthenticated(); jsonBody(request) } }

    suspend fun verifyLoginOtp(request: VerifyOtpRequest): ApiResult<AuthResponse> =
        safeApiCall { client.post("api/v1/auth/phone/verify") { unauthenticated(); jsonBody(request) } }

    suspend fun requestOnboardingOtp(request: OnboardingOtpRequest): ApiResult<OtpChallengeDto> =
        safeApiCall { client.post("api/v1/auth/onboarding/otp") { unauthenticated(); jsonBody(request) } }

    suspend fun completeOnboarding(request: CompleteOnboardingRequest): ApiResult<AuthResponse> =
        safeApiCall { client.post("api/v1/auth/onboarding/complete") { unauthenticated(); jsonBody(request) } }

    suspend fun logout(request: RefreshRequest): ApiResult<Unit> =
        safeApiCall { client.post("api/v1/auth/logout") { unauthenticated(); jsonBody(request) } }

    /** Sends a confirmation code to the signed-in account's phone. */
    suspend fun requestDeleteOtp(): ApiResult<OtpChallengeDto> = safeApiCall { client.post("api/v1/me/delete/otp") }

    /** Authenticated; a failed confirmation is 403 REAUTH_FAILED or 400 OTP_* (never 401), so no refresh loop. */
    suspend fun deleteAccount(request: DeleteAccountRequest): ApiResult<Unit> =
        safeApiCall { client.delete("api/v1/me") { jsonBody(request) } }

    /** Drops the Auth plugin's cached tokens so the next request reloads them from the session store. */
    fun clearCachedTokens() {
        client.authProvider<BearerAuthProvider>()?.clearToken()
    }

    // A 401 from a sign-in endpoint means "bad Google token" or "expired sign-up", not "access token expired":
    // skip refresh handling.
    private fun HttpRequestBuilder.unauthenticated() {
        attributes.put(AuthCircuitBreaker, Unit)
    }
}
