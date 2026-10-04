package com.android.insta.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.auth.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val password: String = "",
    val usernameError: UiMessage? = null,
    val emailError: UiMessage? = null,
    val displayNameError: UiMessage? = null,
    val passwordError: UiMessage? = null,
    val formError: UiMessage? = null,
    val isSubmitting: Boolean = false,
)

sealed interface RegisterEvent {
    data class UsernameChanged(val value: String) : RegisterEvent
    data class EmailChanged(val value: String) : RegisterEvent
    data class DisplayNameChanged(val value: String) : RegisterEvent
    data class PasswordChanged(val value: String) : RegisterEvent
    data object Submit : RegisterEvent
}

class RegisterViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun onEvent(event: RegisterEvent) {
        when (event) {
            // Usernames are lowercase on the server; normalize as the user types.
            is RegisterEvent.UsernameChanged ->
                _state.update { it.copy(username = event.value.lowercase().trim(), usernameError = null, formError = null) }
            is RegisterEvent.EmailChanged -> _state.update { it.copy(email = event.value, emailError = null, formError = null) }
            is RegisterEvent.DisplayNameChanged ->
                _state.update { it.copy(displayName = event.value, displayNameError = null, formError = null) }
            is RegisterEvent.PasswordChanged -> _state.update { it.copy(password = event.value, passwordError = null, formError = null) }
            RegisterEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.isSubmitting) return
        val validated = s.copy(
            usernameError = AuthValidation.usernameError(s.username)?.let(UiMessage::Resource),
            emailError = AuthValidation.emailError(s.email)?.let(UiMessage::Resource),
            displayNameError = AuthValidation.displayNameError(s.displayName)?.let(UiMessage::Resource),
            passwordError = AuthValidation.passwordError(s.password)?.let(UiMessage::Resource),
        )
        if (listOf(validated.usernameError, validated.emailError, validated.displayNameError, validated.passwordError).any { it != null }) {
            _state.value = validated
            return
        }

        _state.update { it.copy(isSubmitting = true, formError = null) }
        viewModelScope.launch {
            when (val result = repository.register(s.username, s.email, s.password, s.displayName)) {
                is ApiResult.Success -> Unit // SessionManager switches to the main app.
                is ApiResult.Failure -> _state.update { it.withError(result.error).copy(isSubmitting = false) }
            }
        }
    }

    /** Puts server errors next to the field they belong to where possible. */
    private fun RegisterUiState.withError(error: AppError): RegisterUiState {
        if (error !is AppError.Api) return copy(formError = error.toUiMessage())
        return when (error.code) {
            "USERNAME_TAKEN" -> copy(usernameError = UiMessage.Resource(R.string.error_username_taken))
            "EMAIL_TAKEN" -> copy(emailError = UiMessage.Resource(R.string.error_email_taken))
            "VALIDATION_ERROR" -> copy(
                usernameError = error.fieldErrors["username"]?.let(UiMessage::Raw),
                emailError = error.fieldErrors["email"]?.let(UiMessage::Raw),
                displayNameError = error.fieldErrors["displayName"]?.let(UiMessage::Raw),
                passwordError = error.fieldErrors["password"]?.let(UiMessage::Raw),
            )
            else -> copy(formError = error.toUiMessage())
        }
    }
}
