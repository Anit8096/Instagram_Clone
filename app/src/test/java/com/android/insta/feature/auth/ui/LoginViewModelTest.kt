package com.android.insta.feature.auth.ui

import app.cash.turbine.test
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.auth.data.GoogleSignInResult
import com.android.insta.testutil.FakeAuthRepository
import com.android.insta.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val viewModel = LoginViewModel(repository, isGoogleAvailable = true)

    private fun fillIn(login: String = "jane.doe", password: String = "secret-pass") {
        viewModel.onEvent(LoginEvent.LoginChanged(login))
        viewModel.onEvent(LoginEvent.PasswordChanged(password))
    }

    @Test
    fun `empty fields are flagged without calling the server`() {
        viewModel.onEvent(LoginEvent.Submit)

        val state = viewModel.state.value
        assertEquals(UiMessage.Resource(R.string.error_required), state.loginError)
        assertEquals(UiMessage.Resource(R.string.error_required), state.passwordError)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `typing clears the field error`() {
        viewModel.onEvent(LoginEvent.Submit)
        viewModel.onEvent(LoginEvent.LoginChanged("j"))
        assertNull(viewModel.state.value.loginError)
    }

    @Test
    fun `successful login keeps the spinner while the app switches to the main flow`() = runTest {
        fillIn()
        viewModel.state.test {
            assertFalse(awaitItem().isSubmitting)
            viewModel.onEvent(LoginEvent.Submit)
            val submitting = awaitItem()
            assertTrue(submitting.isSubmitting)
            assertNull(submitting.formError)
            expectNoEvents()
        }
        assertEquals(listOf("login:jane.doe"), repository.calls)
    }

    @Test
    fun `wrong password shows a friendly message and re-enables the form`() {
        repository.loginResult = ApiResult.Failure(AppError.Api(401, "INVALID_CREDENTIALS", "Incorrect username/email or password"))
        fillIn()
        viewModel.onEvent(LoginEvent.Submit)

        val state = viewModel.state.value
        assertFalse(state.isSubmitting)
        assertEquals(UiMessage.Resource(R.string.error_invalid_credentials), state.formError)
    }

    @Test
    fun `network failure is reported`() {
        repository.loginResult = ApiResult.Failure(AppError.Network)
        fillIn()
        viewModel.onEvent(LoginEvent.Submit)
        assertEquals(UiMessage.Resource(R.string.error_network), viewModel.state.value.formError)
    }

    @Test
    fun `google token is exchanged with the server`() {
        viewModel.onEvent(LoginEvent.GoogleStarted)
        viewModel.onEvent(LoginEvent.GoogleResult(GoogleSignInResult.Success("id-token")))
        assertEquals(listOf("google:id-token"), repository.calls)
    }

    @Test
    fun `cancelling the google sheet just re-enables the form`() {
        viewModel.onEvent(LoginEvent.GoogleStarted)
        viewModel.onEvent(LoginEvent.GoogleResult(GoogleSignInResult.Cancelled))
        assertFalse(viewModel.state.value.isSubmitting)
        assertNull(viewModel.state.value.formError)
        assertTrue(repository.calls.isEmpty())
    }
}
