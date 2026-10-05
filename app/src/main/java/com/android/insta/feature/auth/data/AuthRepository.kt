package com.android.insta.feature.auth.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.map
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionStore

/** Outcome of Google sign-in: signed in, or a new Google user who must finish onboarding. */
sealed interface GoogleSignIn {
    data object SignedIn : GoogleSignIn
    data class NeedsOnboarding(val onboardingToken: String, val suggestedUsername: String, val displayName: String) : GoogleSignIn
}

/**
 * Google-first authentication (docs/SPEC.md): accounts are created only through Google + onboarding with a verified
 * phone; phone + code signs in to an existing account.
 */
interface AuthRepository {
    suspend fun signInWithGoogle(idToken: String): ApiResult<GoogleSignIn>

    /** `NO_LINKED_ACCOUNT` when no account has this (E.164) number. */
    suspend fun requestLoginOtp(phone: String): ApiResult<OtpChallenge>
    suspend fun verifyLoginOtp(challengeId: String, code: String): ApiResult<Unit>

    suspend fun requestOnboardingOtp(onboardingToken: String, phone: String): ApiResult<OtpChallenge>
    suspend fun completeOnboarding(onboardingToken: String, challengeId: String, code: String, username: String, displayName: String): ApiResult<Unit>

    /** Always ends signed out locally, even if the server can't be reached. */
    suspend fun logout()

    suspend fun requestDeleteOtp(): ApiResult<OtpChallenge>

    /** Permanently deletes the account after a code or Google confirmation; on success the device is signed out and wiped. */
    suspend fun deleteAccount(challengeId: String?, code: String?, googleIdToken: String?): ApiResult<Unit>
}

/** Wipes per-account local data (drafts, queued uploads) when the session ends. */
fun interface UserDataCleaner {
    suspend fun clear()
}

class DefaultAuthRepository(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
    private val google: GoogleSignInClient,
    private val userData: UserDataCleaner = UserDataCleaner { },
) : AuthRepository {

    override suspend fun signInWithGoogle(idToken: String): ApiResult<GoogleSignIn> =
        when (val result = api.loginWithGoogle(GoogleLoginRequest(idToken))) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> when (val dto = result.value) {
                is GoogleAuthResultDto.SignedIn -> {
                    storeSession(dto.auth)
                    ApiResult.Success(GoogleSignIn.SignedIn)
                }
                is GoogleAuthResultDto.NeedsOnboarding -> ApiResult.Success(GoogleSignIn.NeedsOnboarding(dto.onboardingToken, dto.suggestedUsername, dto.displayName))
            }
        }

    override suspend fun requestLoginOtp(phone: String) = api.requestLoginOtp(PhoneOtpRequest(phone)).map { it.toChallenge() }

    override suspend fun verifyLoginOtp(challengeId: String, code: String) =
        api.verifyLoginOtp(VerifyOtpRequest(challengeId, code.trim())).storeSession()

    override suspend fun requestOnboardingOtp(onboardingToken: String, phone: String) =
        api.requestOnboardingOtp(OnboardingOtpRequest(onboardingToken, phone)).map { it.toChallenge() }

    override suspend fun completeOnboarding(onboardingToken: String, challengeId: String, code: String, username: String, displayName: String) =
        api.completeOnboarding(CompleteOnboardingRequest(onboardingToken, challengeId, code.trim(), username.trim(), displayName.trim())).storeSession()

    override suspend fun logout() {
        sessionStore.current()?.let { api.logout(RefreshRequest(it.refreshToken)) }
        signOutLocally()
    }

    override suspend fun requestDeleteOtp() = api.requestDeleteOtp().map { it.toChallenge() }

    override suspend fun deleteAccount(challengeId: String?, code: String?, googleIdToken: String?): ApiResult<Unit> {
        val result = api.deleteAccount(DeleteAccountRequest(challengeId, code?.trim(), googleIdToken))
        // The server already revoked every session, so there's nothing to log out of remotely.
        if (result is ApiResult.Success) signOutLocally()
        return result
    }

    private suspend fun signOutLocally() {
        userData.clear()
        sessionStore.clear()
        api.clearCachedTokens()
        google.clearState()
    }

    private suspend fun storeSession(auth: AuthResponse) {
        sessionStore.save(Session(auth.accessToken, auth.refreshToken, auth.user.toSessionUser()))
        api.clearCachedTokens()
    }

    private suspend fun ApiResult<AuthResponse>.storeSession(): ApiResult<Unit> {
        if (this is ApiResult.Success) storeSession(value)
        return map { }
    }
}
