package com.android.insta.feature.settings.ui

import com.android.insta.R
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.auth.data.GoogleSignInResult
import com.android.insta.testutil.FakeAuthRepository
import com.android.insta.testutil.MainDispatcherRule
import com.android.insta.testutil.apiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val auth = FakeAuthRepository()
    private val viewModel = SettingsViewModel(auth, googleConfigured = true)

    @Test
    fun `logout goes through the auth repository once`() {
        repeat(2) { viewModel.onEvent(SettingsEvent.Logout) }
        assertEquals(listOf("logout"), auth.calls)
    }

    @Test
    fun `delete sends a code to the account's phone, then confirms with it`() {
        viewModel.onEvent(SettingsEvent.OpenDelete)
        viewModel.onEvent(SettingsEvent.SendDeleteCode)
        assertEquals(listOf("deleteOtp"), auth.calls)
        assertNotNull(viewModel.state.value.deleteOtp)

        viewModel.onEvent(SettingsEvent.CodeChanged("123456"))
        viewModel.onEvent(SettingsEvent.ConfirmWithCode)
        assertEquals("delete:c1:123456:-", auth.calls.last())
    }

    @Test
    fun `a wrong code shows on the code step and keeps the dialog open`() {
        auth.deleteResult = apiError(400, "OTP_EXPIRED")
        viewModel.onEvent(SettingsEvent.OpenDelete)
        viewModel.onEvent(SettingsEvent.SendDeleteCode)
        viewModel.onEvent(SettingsEvent.CodeChanged("123456"))
        viewModel.onEvent(SettingsEvent.ConfirmWithCode)

        val state = viewModel.state.value
        assertEquals(UiMessage.Resource(R.string.error_otp_expired), state.deleteOtp?.error)
        assertTrue(state.isDeleteDialogOpen)
        assertFalse(state.isDeleting)
    }

    @Test
    fun `google confirmation sends the fresh id token, cancel just stops`() {
        viewModel.onEvent(SettingsEvent.OpenDelete)
        viewModel.onEvent(SettingsEvent.GoogleStarted)
        viewModel.onEvent(SettingsEvent.GoogleResult(GoogleSignInResult.Cancelled))
        assertFalse(viewModel.state.value.isDeleting)

        viewModel.onEvent(SettingsEvent.GoogleStarted)
        viewModel.onEvent(SettingsEvent.GoogleResult(GoogleSignInResult.Success("fresh-token")))
        assertEquals("delete:-:-:fresh-token", auth.calls.last())

        auth.deleteResult = apiError(403, "REAUTH_FAILED")
        viewModel.onEvent(SettingsEvent.GoogleResult(GoogleSignInResult.Success("other-account")))
        assertEquals(UiMessage.Resource(R.string.error_reauth_failed), viewModel.state.value.deleteError)
    }
}
