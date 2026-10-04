package com.android.insta.feature.auth.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.insta.R
import com.android.insta.feature.auth.data.GoogleSignInClient
import com.android.insta.ui.theme.InstaTheme
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    viewModel: LoginViewModel = koinViewModel(),
    googleSignIn: GoogleSignInClient = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LoginContent(
        state = state,
        onEvent = viewModel::onEvent,
        onGoogleClick = {
            viewModel.onEvent(LoginEvent.GoogleStarted)
            // Credential Manager needs the Activity context to show its bottom sheet.
            scope.launch { viewModel.onEvent(LoginEvent.GoogleResult(googleSignIn.requestIdToken(context))) }
        },
        onNavigateToRegister = onNavigateToRegister,
    )
}

@Composable
fun LoginContent(
    state: LoginUiState,
    onEvent: (LoginEvent) -> Unit,
    onGoogleClick: () -> Unit,
    onNavigateToRegister: () -> Unit,
) {
    AuthScaffold {
        AuthHeader(stringResource(R.string.login_subtitle))
        AuthTextField(
            value = state.login,
            onValueChange = { onEvent(LoginEvent.LoginChanged(it)) },
            label = stringResource(R.string.field_login),
            error = state.loginError,
            enabled = !state.isSubmitting,
            keyboardType = KeyboardType.Email,
        )
        PasswordField(
            value = state.password,
            onValueChange = { onEvent(LoginEvent.PasswordChanged(it)) },
            label = stringResource(R.string.field_password),
            error = state.passwordError,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Done,
            onImeAction = { onEvent(LoginEvent.Submit) },
        )
        FormError(state.formError)
        PrimaryButton(
            text = stringResource(R.string.action_log_in),
            loading = state.isSubmitting,
            enabled = true,
            onClick = { onEvent(LoginEvent.Submit) },
        )
        if (state.isGoogleAvailable) {
            HorizontalDivider()
            OutlinedButton(onClick = onGoogleClick, enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue_with_google))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.login_no_account), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onNavigateToRegister, enabled = !state.isSubmitting) {
                Text(stringResource(R.string.action_sign_up))
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun LoginContentPreview() {
    InstaTheme {
        LoginContent(LoginUiState(login = "jane.doe", isGoogleAvailable = true), {}, {}, {})
    }
}
