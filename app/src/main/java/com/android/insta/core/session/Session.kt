package com.android.insta.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

@Serializable
data class SessionUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    /** The account's verified phone (E.164), private to this user. */
    val phone: String? = null,
)

data class Session(val accessToken: String, val refreshToken: String, val user: SessionUser)

/** Persistent storage for the signed-in session. */
interface SessionStore {
    val session: Flow<Session?>
    suspend fun current(): Session?
    suspend fun save(session: Session)
    suspend fun updateTokens(accessToken: String, refreshToken: String)

    /** Replaces the cached profile (e.g. after editing it); no-op when signed out. */
    suspend fun updateUser(user: SessionUser)
    suspend fun clear()
}

sealed interface SessionState {
    /** Reading the stored session at startup; the splash screen stays up meanwhile. */
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val user: SessionUser) : SessionState
}

/**
 * Single source of truth for "is someone signed in". The UI switches between the auth flow and
 * the main app purely by observing [state], so login, logout and server-side session expiry
 * all navigate the same way.
 */
class SessionManager(store: SessionStore, scope: CoroutineScope) {
    val state: StateFlow<SessionState> = store.session
        .map { session -> session?.let { SessionState.LoggedIn(it.user) } ?: SessionState.LoggedOut }
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)
}
