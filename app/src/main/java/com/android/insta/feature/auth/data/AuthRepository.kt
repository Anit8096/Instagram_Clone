package com.android.insta.feature.auth.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.map
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionStore

interface AuthRepository {
    suspend fun login(login: String, password: String): ApiResult<Unit>
    suspend fun register(username: String, email: String, password: String, displayName: String?): ApiResult<Unit>
    suspend fun loginWithGoogle(idToken: String): ApiResult<Unit>

    /** Always ends signed out locally, even if the server can't be reached. */
    suspend fun logout()

    /** Permanently deletes the account after re-authentication; on success the device is signed out and wiped. */
    suspend fun deleteAccount(password: String?, googleIdToken: String?): ApiResult<Unit>
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

    override suspend fun login(login: String, password: String) =
        api.login(LoginRequest(login.trim(), password)).storeSession()

    override suspend fun register(username: String, email: String, password: String, displayName: String?) =
        api.register(RegisterRequest(username.trim(), email.trim(), password, displayName?.trim()?.ifEmpty { null }))
            .storeSession()

    override suspend fun loginWithGoogle(idToken: String) =
        api.loginWithGoogle(GoogleLoginRequest(idToken)).storeSession()

    override suspend fun logout() {
        sessionStore.current()?.let { api.logout(RefreshRequest(it.refreshToken)) }
        signOutLocally()
    }

    override suspend fun deleteAccount(password: String?, googleIdToken: String?): ApiResult<Unit> {
        val result = api.deleteAccount(DeleteAccountRequest(password?.takeIf { it.isNotEmpty() }, googleIdToken))
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

    private suspend fun ApiResult<AuthResponse>.storeSession(): ApiResult<Unit> {
        if (this is ApiResult.Success) {
            sessionStore.save(Session(value.accessToken, value.refreshToken, value.user.toSessionUser()))
            api.clearCachedTokens()
        }
        return map { }
    }
}
