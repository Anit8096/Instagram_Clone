package com.android.insta.feature.profile.ui

import com.android.insta.R
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionManager
import com.android.insta.core.ui.UiMessage
import com.android.insta.testutil.FakePhoneNumbers
import com.android.insta.testutil.FakeProfileRepository
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.MainDispatcherRule
import com.android.insta.testutil.TEST_USER
import com.android.insta.testutil.apiError
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChangePhoneViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val profiles = FakeProfileRepository()
    private val store = FakeSessionStore(Session("a", "r", TEST_USER.copy(phone = "+919876543210")))
    private val sessionManager by lazy { SessionManager(store, TestScope(main.dispatcher)) }
    private val viewModel by lazy { ChangePhoneViewModel(profiles, FakePhoneNumbers(), sessionManager) }

    @Test
    fun `shows the current number and confirms the new one with a code`() {
        assertEquals("formatted:+919876543210", viewModel.state.value.currentPhone)
        viewModel.onEvent(ChangePhoneEvent.NumberChanged("9123456789"))
        viewModel.onEvent(ChangePhoneEvent.SendCode)
        assertEquals("phoneOtp:+919123456789", profiles.calls.single())

        viewModel.onEvent(ChangePhoneEvent.CodeChanged("123456"))
        viewModel.onEvent(ChangePhoneEvent.Verify)
        assertEquals("confirmPhone:c1:123456", profiles.calls.last())
        assertTrue(viewModel.state.value.done)
    }

    @Test
    fun `a number taken by another account is an error on the phone field`() {
        profiles.phoneOtpResult = apiError(409, "PHONE_IN_USE")
        viewModel.onEvent(ChangePhoneEvent.NumberChanged("9123456789"))
        viewModel.onEvent(ChangePhoneEvent.SendCode)
        assertEquals(UiMessage.Resource(R.string.error_phone_in_use), viewModel.state.value.phoneError)
        assertNull(viewModel.state.value.otp)
    }

    @Test
    fun `edit profile shows the session phone and follows changes`() {
        val edit = EditProfileViewModel(profiles, sessionManager, formatPhone = { "fmt:$it" })
        assertEquals("fmt:+919876543210", edit.state.value.phone)
        kotlinx.coroutines.test.runTest(main.dispatcher) { store.updateUser(TEST_USER.copy(phone = "+919123456789")) }
        assertEquals("fmt:+919123456789", edit.state.value.phone)
    }
}
