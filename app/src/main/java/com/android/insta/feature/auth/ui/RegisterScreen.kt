package com.android.insta.feature.auth.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.insta.R
import com.android.insta.ui.theme.InstaTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    viewModel: RegisterViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RegisterContent(state = state, onEvent = viewModel::onEvent, onNavigateToLogin = onNavigateToLogin)
}

@Composable
fun RegisterContent(
    state: RegisterUiState,
    onEvent: (RegisterEvent) -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val enabled = !state.isSubmitting
    AuthScaffold {
        AuthHeader(stringResource(R.string.register_subtitle))
        AuthTextField(
            value = state.username,
            onValueChange = { onEvent(RegisterEvent.UsernameChanged(it)) },
            label = stringResource(R.string.field_username),
            error = state.usernameError,
            enabled = enabled,
            supportingText = stringResource(R.string.field_username_hint),
        )
        AuthTextField(
            value = state.email,
            onValueChange = { onEvent(RegisterEvent.EmailChanged(it)) },
            label = stringResource(R.string.field_email),
            error = state.emailError,
            enabled = enabled,
            keyboardType = KeyboardType.Email,
        )
        AuthTextField(
            value = state.displayName,
            onValueChange = { onEvent(RegisterEvent.DisplayNameChanged(it)) },
            label = stringResource(R.string.field_display_name),
            error = state.displayNameError,
            enabled = enabled,
        )
        PasswordField(
            value = state.password,
            onValueChange = { onEvent(RegisterEvent.PasswordChanged(it)) },
            label = stringResource(R.string.field_password),
            error = state.passwordError,
            enabled = enabled,
            imeAction = ImeAction.Done,
            onImeAction = { onEvent(RegisterEvent.Submit) },
        )
        FormError(state.formError)
        PrimaryButton(
            text = stringResource(R.string.action_create_account),
            loading = state.isSubmitting,
            enabled = true,
            onClick = { onEvent(RegisterEvent.Submit) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.register_have_account), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onNavigateToLogin, enabled = enabled) { Text(stringResource(R.string.action_log_in)) }
        }
    }
}

@PreviewLightDark
@Composable
private fun RegisterContentPreview() {
    InstaTheme { RegisterContent(RegisterUiState(username = "jane.doe"), {}, {}) }
}
