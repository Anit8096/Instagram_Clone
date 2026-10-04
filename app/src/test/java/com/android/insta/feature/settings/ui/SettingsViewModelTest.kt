package com.android.insta.feature.settings.ui

import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.auth.data.GoogleSignInResult
import com.android.insta.testutil.FakeAuthRepository
import com.android.insta.testutil.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `delete needs a password before calling the server`() {
        viewModel.onEvent(SettingsEvent.OpenDelete)
        viewModel.onEvent(SettingsEvent.ConfirmWithPassword)
        assertEquals(UiMessage.Resource(R.string.error_required), viewModel.state.value.deleteError)
        assertTrue(auth.calls.isEmpty())

        viewModel.onEvent(SettingsEvent.PasswordChanged("correct-horse"))
        viewModel.onEvent(SettingsEvent.ConfirmWithPassword)
        assertEquals(listOf("delete:correct-horse:-"), auth.calls)
    }

    @Test
    fun `wrong password shows a specific message and keeps the dialog open`() {
        auth.deleteResult = ApiResult.Failure(AppError.Api(403, "REAUTH_FAILED", "nope"))
        viewModel.onEvent(SettingsEvent.OpenDelete)
        viewModel.onEvent(SettingsEvent.PasswordChanged("wrong-one"))
        viewModel.onEvent(SettingsEvent.ConfirmWithPassword)

        val state = viewModel.state.value
        assertEquals(UiMessage.Resource(R.string.error_reauth_failed), state.deleteError)
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
        assertEquals(listOf("delete:-:fresh-token"), auth.calls)
    }
}
