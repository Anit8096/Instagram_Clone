package com.android.insta.feature.auth.ui

import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.auth.data.GoogleSignIn
import com.android.insta.feature.auth.data.GoogleSignInResult
import com.android.insta.testutil.FakeAuthRepository
import com.android.insta.testutil.FakePhoneNumbers
import com.android.insta.testutil.MainDispatcherRule
import com.android.insta.testutil.apiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WelcomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val auth = FakeAuthRepository()

    @Test
    fun `linked google account signs in without navigating`() {
        val viewModel = WelcomeViewModel(auth, isGoogleAvailable = true)
        viewModel.onEvent(WelcomeEvent.GoogleStarted)
        viewModel.onEvent(WelcomeEvent.GoogleResult(GoogleSignInResult.Success("tok")))
        assertEquals(listOf("google:tok"), auth.calls)
        assertTrue(viewModel.state.value.isSigningIn) // spinner stays until the main app replaces the screen
        assertNull(viewModel.state.value.onboarding)
    }

    @Test
    fun `new google user is sent to onboarding once`() {
        val onboarding = GoogleSignIn.NeedsOnboarding("t1", "sam.smith", "Sam")
        auth.googleResult = ApiResult.Success(onboarding)
        val viewModel = WelcomeViewModel(auth, isGoogleAvailable = true)
        viewModel.onEvent(WelcomeEvent.GoogleResult(GoogleSignInResult.Success("tok")))
        assertEquals(onboarding, viewModel.state.value.onboarding)

        viewModel.onEvent(WelcomeEvent.OnboardingOpened)
        assertNull(viewModel.state.value.onboarding)
        assertFalse(viewModel.state.value.isSigningIn)
    }

    @Test
    fun `cancel and failures stop the spinner with a message`() {
        val viewModel = WelcomeViewModel(auth, isGoogleAvailable = true)
        viewModel.onEvent(WelcomeEvent.GoogleStarted)
        viewModel.onEvent(WelcomeEvent.GoogleResult(GoogleSignInResult.Cancelled))
        assertFalse(viewModel.state.value.isSigningIn)
        assertNull(viewModel.state.value.error)

        auth.googleResult = apiError(401, "INVALID_GOOGLE_TOKEN")
        viewModel.onEvent(WelcomeEvent.GoogleResult(GoogleSignInResult.Success("bad")))
        assertEquals(UiMessage.Resource(R.string.error_google_failed), viewModel.state.value.error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSignInViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val auth = FakeAuthRepository()
    private val viewModel by lazy { PhoneSignInViewModel(auth, FakePhoneNumbers()) }

    @Test
    fun `invalid numbers are rejected before calling the server`() {
        viewModel.onEvent(PhoneSignInEvent.NumberChanged("12345"))
        viewModel.onEvent(PhoneSignInEvent.SendCode)
        assertEquals(UiMessage.Resource(R.string.error_phone_invalid), viewModel.state.value.phoneError)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `unknown number offers google instead`() {
        auth.otpResult = apiError(404, "NO_LINKED_ACCOUNT")
        viewModel.onEvent(PhoneSignInEvent.NumberChanged("98765 43210"))
        viewModel.onEvent(PhoneSignInEvent.SendCode)
        assertEquals(listOf("loginOtp:+919876543210"), auth.calls)
        assertTrue(viewModel.state.value.noLinkedAccount)
        assertNull(viewModel.state.value.otp)

        viewModel.onEvent(PhoneSignInEvent.NumberChanged("98765 43211"))
        assertFalse(viewModel.state.value.noLinkedAccount)
    }

    @Test
    fun `code step verifies, shows attempts left, and resends after the countdown`() {
        viewModel.onEvent(PhoneSignInEvent.NumberChanged("9876543210"))
        viewModel.onEvent(PhoneSignInEvent.SendCode)
        val otp = viewModel.state.value.otp!!
        assertEquals("+91 ••••••3210" to 30, otp.sentTo to otp.resendIn)
        assertFalse(otp.canResend)

        auth.verifyResult = apiError(400, "OTP_INVALID", mapOf("attemptsLeft" to "4"))
        viewModel.onEvent(PhoneSignInEvent.CodeChanged("000000"))
        viewModel.onEvent(PhoneSignInEvent.Verify)
        assertEquals(UiMessage.Plural(R.plurals.error_otp_invalid_attempts, 4), viewModel.state.value.otp?.error)
        assertEquals("", viewModel.state.value.otp?.code)

        main.dispatcher.scheduler.advanceTimeBy(31_000)
        assertTrue(viewModel.state.value.otp!!.canResend)
        viewModel.onEvent(PhoneSignInEvent.Resend)
        assertEquals(2, auth.calls.count { it == "loginOtp:+919876543210" })

        auth.verifyResult = ApiResult.Success(Unit)
        viewModel.onEvent(PhoneSignInEvent.CodeChanged("123456"))
        viewModel.onEvent(PhoneSignInEvent.Verify)
        assertEquals("verify:c1:123456", auth.calls.last())
        assertTrue(viewModel.state.value.otp!!.isVerifying) // spinner stays: the session switches the root
    }

    @Test
    fun `rate limit message carries the wait time`() {
        auth.otpResult = apiError(429, "OTP_RATE_LIMITED", mapOf("retryAfter" to "21"))
        viewModel.onEvent(PhoneSignInEvent.NumberChanged("9876543210"))
        viewModel.onEvent(PhoneSignInEvent.SendCode)
        assertEquals(UiMessage.Plural(R.plurals.error_otp_rate_limited, 21), viewModel.state.value.phoneError)
    }
}

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val auth = FakeAuthRepository()
    private val viewModel by lazy { OnboardingViewModel("t1", "sam.smith", "Sam", auth, FakePhoneNumbers()) }

    @Test
    fun `prefills from google and validates the profile before sending a code`() {
        assertEquals("sam.smith" to "Sam", viewModel.state.value.username to viewModel.state.value.displayName)
        viewModel.onEvent(OnboardingEvent.UsernameChanged("x!"))
        viewModel.onEvent(OnboardingEvent.NumberChanged("9876543210"))
        viewModel.onEvent(OnboardingEvent.SendCode)
        assertEquals(UiMessage.Resource(R.string.error_username_format), viewModel.state.value.usernameError)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `verified code creates the account with the profile`() {
        viewModel.onEvent(OnboardingEvent.UsernameChanged("Sam.Smith"))
        viewModel.onEvent(OnboardingEvent.NumberChanged("9876543210"))
        viewModel.onEvent(OnboardingEvent.SendCode)
        assertEquals("onboardingOtp:t1:+919876543210", auth.calls.single())
        assertNotNull(viewModel.state.value.otp)

        viewModel.onEvent(OnboardingEvent.CodeChanged("123456"))
        viewModel.onEvent(OnboardingEvent.CreateAccount)
        assertEquals("complete:t1:c1:123456:sam.smith:Sam", auth.calls.last())
    }

    @Test
    fun `server errors land on the right field`() {
        viewModel.onEvent(OnboardingEvent.NumberChanged("9876543210"))

        auth.otpResult = apiError(409, "PHONE_IN_USE")
        viewModel.onEvent(OnboardingEvent.SendCode)
        assertEquals(UiMessage.Resource(R.string.error_phone_in_use), viewModel.state.value.phoneError)
        assertNull(viewModel.state.value.otp)

        auth.otpResult = ApiResult.Success(com.android.insta.testutil.TEST_CHALLENGE)
        viewModel.onEvent(OnboardingEvent.SendCode)
        viewModel.onEvent(OnboardingEvent.CodeChanged("123456"))
        auth.completeResult = apiError(409, "USERNAME_TAKEN")
        viewModel.onEvent(OnboardingEvent.CreateAccount)
        assertEquals(UiMessage.Resource(R.string.error_username_taken), viewModel.state.value.usernameError)
        assertNotNull("the code stays valid; only the username needs changing", viewModel.state.value.otp)

        auth.completeResult = apiError(401, "INVALID_ONBOARDING_TOKEN")
        viewModel.onEvent(OnboardingEvent.UsernameChanged("sam.other"))
        viewModel.onEvent(OnboardingEvent.CreateAccount)
        assertTrue(viewModel.state.value.sessionExpired)
    }
}
