package com.android.insta.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.GoogleSignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val loginError: UiMessage? = null,
    val passwordError: UiMessage? = null,
    val formError: UiMessage? = null,
    val isSubmitting: Boolean = false,
    val isGoogleAvailable: Boolean = false,
)

sealed interface LoginEvent {
    data class LoginChanged(val value: String) : LoginEvent
    data class PasswordChanged(val value: String) : LoginEvent
    data object Submit : LoginEvent
    data object GoogleStarted : LoginEvent
    data class GoogleResult(val result: GoogleSignInResult) : LoginEvent
}

/**
 * Success needs no navigation event: the repository stores the session, SessionManager emits
 * LoggedIn, and the root composable swaps the auth flow for the main app.
 */
class LoginViewModel(
    private val repository: AuthRepository,
    isGoogleAvailable: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(isGoogleAvailable = isGoogleAvailable))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.LoginChanged -> _state.update { it.copy(login = event.value, loginError = null, formError = null) }
            is LoginEvent.PasswordChanged -> _state.update { it.copy(password = event.value, passwordError = null, formError = null) }
            LoginEvent.Submit -> submit()
            LoginEvent.GoogleStarted -> _state.update { it.copy(isSubmitting = true, formError = null) }
            is LoginEvent.GoogleResult -> onGoogleResult(event.result)
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        val loginError = if (current.login.isBlank()) UiMessage.Resource(R.string.error_required) else null
        val passwordError = if (current.password.isEmpty()) UiMessage.Resource(R.string.error_required) else null
        if (loginError != null || passwordError != null) {
            _state.update { it.copy(loginError = loginError, passwordError = passwordError) }
            return
        }
        authenticate { repository.login(current.login, current.password) }
    }

    private fun onGoogleResult(result: GoogleSignInResult) {
        when (result) {
            is GoogleSignInResult.Success -> authenticate { repository.loginWithGoogle(result.idToken) }
            GoogleSignInResult.Cancelled -> _state.update { it.copy(isSubmitting = false) }
            GoogleSignInResult.NoAccount ->
                _state.update { it.copy(isSubmitting = false, formError = UiMessage.Resource(R.string.error_google_no_account)) }
            is GoogleSignInResult.Failure ->
                _state.update { it.copy(isSubmitting = false, formError = UiMessage.Resource(R.string.error_google_failed)) }
        }
    }

    private fun authenticate(call: suspend () -> ApiResult<Unit>) {
        _state.update { it.copy(isSubmitting = true, formError = null) }
        viewModelScope.launch {
            when (val result = call()) {
                // Keep the spinner: this screen is about to be replaced by the main app.
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> _state.update { it.copy(isSubmitting = false, formError = result.error.toLoginMessage()) }
            }
        }
    }

    private fun AppError.toLoginMessage(): UiMessage =
        if (this is AppError.Api && code == "INVALID_CREDENTIALS") {
            UiMessage.Resource(R.string.error_invalid_credentials)
        } else {
            toUiMessage()
        }
}
