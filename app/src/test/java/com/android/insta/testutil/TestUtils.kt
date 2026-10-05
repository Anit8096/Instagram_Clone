package com.android.insta.testutil

import com.android.insta.core.network.ApiResult
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionStore
import com.android.insta.core.session.SessionUser
import com.android.insta.core.phone.Country
import com.android.insta.core.phone.PhoneNumbers
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.GoogleSignIn
import com.android.insta.feature.auth.data.OtpChallenge
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

val TEST_CHALLENGE = OtpChallenge(challengeId = "c1", sentTo = "+91 ••••••3210", resendInSeconds = 30, devCode = "123456")

/** Repository whose results are scripted per test; records what was called. */
class FakeAuthRepository : AuthRepository {
    var googleResult: ApiResult<GoogleSignIn> = ApiResult.Success(GoogleSignIn.SignedIn)
    var otpResult: ApiResult<OtpChallenge> = ApiResult.Success(TEST_CHALLENGE)
    var verifyResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var completeResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val calls = mutableListOf<String>()

    override suspend fun signInWithGoogle(idToken: String): ApiResult<GoogleSignIn> {
        calls += "google:$idToken"
        return googleResult
    }

    override suspend fun requestLoginOtp(phone: String): ApiResult<OtpChallenge> {
        calls += "loginOtp:$phone"
        return otpResult
    }

    override suspend fun verifyLoginOtp(challengeId: String, code: String): ApiResult<Unit> {
        calls += "verify:$challengeId:$code"
        return verifyResult
    }

    override suspend fun requestOnboardingOtp(onboardingToken: String, phone: String): ApiResult<OtpChallenge> {
        calls += "onboardingOtp:$onboardingToken:$phone"
        return otpResult
    }

    override suspend fun completeOnboarding(onboardingToken: String, challengeId: String, code: String, username: String, displayName: String): ApiResult<Unit> {
        calls += "complete:$onboardingToken:$challengeId:$code:$username:$displayName"
        return completeResult
    }

    override suspend fun logout() {
        calls += "logout"
    }

    override suspend fun requestDeleteOtp(): ApiResult<OtpChallenge> {
        calls += "deleteOtp"
        return otpResult
    }

    override suspend fun deleteAccount(challengeId: String?, code: String?, googleIdToken: String?): ApiResult<Unit> {
        calls += "delete:${challengeId ?: "-"}:${code ?: "-"}:${googleIdToken ?: "-"}"
        return deleteResult
    }
}

/** India only; any 10 digits are a valid number. */
class FakePhoneNumbers : PhoneNumbers {
    private val india = Country("IN", "India", 91)
    override val defaultRegion = "IN"
    override fun countries() = listOf(india, Country("US", "United States", 1))
    override fun country(region: String) = countries().first { it.region == region }
    override fun toE164(region: String, input: String): String? {
        val digits = input.filter(Char::isDigit)
        return if (digits.length == 10) "+${country(region).dialCode}$digits" else null
    }
    override fun format(e164: String) = "formatted:$e164"
}

/** An API failure with the server's error code (and details, e.g. attemptsLeft). */
fun apiError(status: Int, code: String, details: Map<String, String> = emptyMap()) =
    ApiResult.Failure(com.android.insta.core.network.AppError.Api(status, code, code, details))

class FakeGoogleSignInClient(
    override val isConfigured: Boolean = true,
    var result: GoogleSignInResult = GoogleSignInResult.Success("google-id-token"),
) : GoogleSignInClient {
    var cleared = false
    override suspend fun requestIdToken(context: Context): GoogleSignInResult = result
    override suspend fun clearState() { cleared = true }
}
