package com.android.insta.testutil

import com.android.insta.core.network.ApiResult
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionStore
import com.android.insta.core.session.SessionUser
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.GoogleSignInClient
import com.android.insta.feature.auth.data.GoogleSignInResult
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Swaps Dispatchers.Main (used by viewModelScope) for a test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val TEST_USER = SessionUser(id = "u1", username = "jane.doe", displayName = "Jane")

class FakeSessionStore(initial: Session? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: Flow<Session?> = state
    override suspend fun current(): Session? = state.value
    override suspend fun save(session: Session) { state.value = session }
    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        state.value = state.value?.copy(accessToken = accessToken, refreshToken = refreshToken)
    }
    override suspend fun updateUser(user: SessionUser) {
        state.value = state.value?.copy(user = user)
    }
    override suspend fun clear() { state.value = null }
}

/** Repository whose results are scripted per test; records what was called. */
class FakeAuthRepository : AuthRepository {
    var loginResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var registerResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var googleResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val calls = mutableListOf<String>()

    override suspend fun login(login: String, password: String): ApiResult<Unit> {
        calls += "login:$login"
        return loginResult
    }

    override suspend fun register(username: String, email: String, password: String, displayName: String?): ApiResult<Unit> {
        calls += "register:$username"
        return registerResult
    }

    override suspend fun loginWithGoogle(idToken: String): ApiResult<Unit> {
        calls += "google:$idToken"
        return googleResult
    }

    override suspend fun logout() {
        calls += "logout"
    }
}

class FakeGoogleSignInClient(
    override val isConfigured: Boolean = true,
    var result: GoogleSignInResult = GoogleSignInResult.Success("google-id-token"),
) : GoogleSignInClient {
    var cleared = false
    override suspend fun requestIdToken(context: Context): GoogleSignInResult = result
    override suspend fun clearState() { cleared = true }
}
