package com.android.insta.feature.auth.ui

import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.testutil.FakeAuthRepository
import com.android.insta.testutil.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RegisterViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val viewModel = RegisterViewModel(repository)

    private fun fillValid() {
        viewModel.onEvent(RegisterEvent.UsernameChanged("Jane.Doe"))
        viewModel.onEvent(RegisterEvent.EmailChanged("jane@example.com"))
        viewModel.onEvent(RegisterEvent.PasswordChanged("correct-horse"))
    }

    @Test
    fun `username is normalized to lowercase as you type`() {
        viewModel.onEvent(RegisterEvent.UsernameChanged("Jane.Doe "))
        assertEquals("jane.doe", viewModel.state.value.username)
    }

    @Test
    fun `invalid fields are reported together and nothing is sent`() {
        viewModel.onEvent(RegisterEvent.UsernameChanged("x"))
        viewModel.onEvent(RegisterEvent.EmailChanged("nope"))
        viewModel.onEvent(RegisterEvent.PasswordChanged("short"))
        viewModel.onEvent(RegisterEvent.Submit)

        val state = viewModel.state.value
        assertEquals(UiMessage.Resource(R.string.error_username_format), state.usernameError)
        assertEquals(UiMessage.Resource(R.string.error_email_format), state.emailError)
        assertEquals(UiMessage.Resource(R.string.error_password_length), state.passwordError)
        assertNull(state.displayNameError)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `valid form registers`() {
        fillValid()
        viewModel.onEvent(RegisterEvent.Submit)
        assertEquals(listOf("register:jane.doe"), repository.calls)
        assertTrue(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `taken username is shown on the username field`() {
        repository.registerResult = ApiResult.Failure(AppError.Api(409, "USERNAME_TAKEN", "Username is already taken"))
        fillValid()
        viewModel.onEvent(RegisterEvent.Submit)

        val state = viewModel.state.value
        assertFalse(state.isSubmitting)
        assertEquals(UiMessage.Resource(R.string.error_username_taken), state.usernameError)
        assertNull(state.formError)
    }

    @Test
    fun `server validation details map onto fields`() {
        repository.registerResult = ApiResult.Failure(
            AppError.Api(400, "VALIDATION_ERROR", "Request validation failed", mapOf("email" to "Invalid email address")),
        )
        fillValid()
        viewModel.onEvent(RegisterEvent.Submit)
        assertEquals(UiMessage.Raw("Invalid email address"), viewModel.state.value.emailError)
    }
}
