package com.android.insta.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.asString
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.GoogleSignInClient
import com.android.insta.feature.auth.data.GoogleSignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

data class SettingsUiState(
    val isLoggingOut: Boolean = false,
    val isDeleteDialogOpen: Boolean = false,
    val password: String = "",
    val isDeleting: Boolean = false,
    val deleteError: UiMessage? = null,
    /** Google-only accounts confirm with Google instead of a password. */
    val canConfirmWithGoogle: Boolean = false,
)

sealed interface SettingsEvent {
    data object Logout : SettingsEvent
    data object OpenDelete : SettingsEvent
    data object DismissDelete : SettingsEvent
    data class PasswordChanged(val value: String) : SettingsEvent
    data object ConfirmWithPassword : SettingsEvent
    data object GoogleStarted : SettingsEvent
    data class GoogleResult(val result: GoogleSignInResult) : SettingsEvent
}

/** Signing out (or deleting) flips the session, and the root navigation switches to sign-in by itself. */
class SettingsViewModel(private val auth: AuthRepository, googleConfigured: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(canConfirmWithGoogle = googleConfigured))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.Logout -> if (!_state.value.isLoggingOut) {
                _state.update { it.copy(isLoggingOut = true) }
                viewModelScope.launch { auth.logout() }
            }
            SettingsEvent.OpenDelete -> _state.update { it.copy(isDeleteDialogOpen = true, password = "", deleteError = null) }
            SettingsEvent.DismissDelete -> if (!_state.value.isDeleting) _state.update { it.copy(isDeleteDialogOpen = false, password = "") }
            is SettingsEvent.PasswordChanged -> _state.update { it.copy(password = event.value, deleteError = null) }
            SettingsEvent.ConfirmWithPassword -> {
                val password = _state.value.password
                if (password.isEmpty()) {
                    _state.update { it.copy(deleteError = UiMessage.Resource(R.string.error_required)) }
                } else {
                    delete(password = password, googleIdToken = null)
                }
            }
            SettingsEvent.GoogleStarted -> _state.update { it.copy(isDeleting = true, deleteError = null) }
            is SettingsEvent.GoogleResult -> when (val result = event.result) {
                is GoogleSignInResult.Success -> delete(password = null, googleIdToken = result.idToken)
                GoogleSignInResult.Cancelled -> _state.update { it.copy(isDeleting = false) }
                GoogleSignInResult.NoAccount -> _state.update { it.copy(isDeleting = false, deleteError = UiMessage.Resource(R.string.error_google_no_account)) }
                is GoogleSignInResult.Failure -> _state.update { it.copy(isDeleting = false, deleteError = UiMessage.Resource(R.string.error_google_failed)) }
            }
        }
    }

    private fun delete(password: String?, googleIdToken: String?) {
        _state.update { it.copy(isDeleting = true, deleteError = null) }
        viewModelScope.launch {
            when (val result = auth.deleteAccount(password, googleIdToken)) {
                // Success: the session is gone and this screen leaves composition with the signed-in shell.
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> _state.update { it.copy(isDeleting = false, deleteError = result.error.toDeleteMessage()) }
            }
        }
    }

    private fun AppError.toDeleteMessage(): UiMessage =
        if (this is AppError.Api && code == "REAUTH_FAILED") UiMessage.Resource(R.string.error_reauth_failed) else toUiMessage()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    googleSignIn: GoogleSignInClient = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_log_out)) },
                trailingContent = if (state.isLoggingOut) ({ CircularProgressIndicator(modifier = Modifier.size(24.dp)) }) else null,
                modifier = Modifier.clickable(enabled = !state.isLoggingOut, role = Role.Button) { viewModel.onEvent(SettingsEvent.Logout) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_delete_account)) },
                supportingContent = { Text(stringResource(R.string.delete_account_summary)) },
                colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.clickable(role = Role.Button) { viewModel.onEvent(SettingsEvent.OpenDelete) },
            )
        }
    }

    if (state.isDeleteDialogOpen) {
        DeleteAccountDialog(
            state = state,
            onEvent = viewModel::onEvent,
            onConfirmWithGoogle = {
                viewModel.onEvent(SettingsEvent.GoogleStarted)
                scope.launch { viewModel.onEvent(SettingsEvent.GoogleResult(googleSignIn.requestIdToken(context))) }
            },
        )
    }
}

@Composable
private fun DeleteAccountDialog(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit, onConfirmWithGoogle: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(SettingsEvent.DismissDelete) },
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.delete_account_body))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { onEvent(SettingsEvent.PasswordChanged(it)) },
                    label = { Text(stringResource(R.string.field_password)) },
                    singleLine = true,
                    enabled = !state.isDeleting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = state.deleteError != null,
                    supportingText = state.deleteError?.let { { Text(it.asString()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.canConfirmWithGoogle) {
                    OutlinedButton(onClick = onConfirmWithGoogle, enabled = !state.isDeleting, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_confirm_with_google))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SettingsEvent.ConfirmWithPassword) }, enabled = !state.isDeleting) {
                if (state.isDeleting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SettingsEvent.DismissDelete) }, enabled = !state.isDeleting) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
