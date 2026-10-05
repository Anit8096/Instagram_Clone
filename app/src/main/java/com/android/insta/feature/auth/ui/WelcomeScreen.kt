package com.android.insta.feature.auth.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.toAuthMessage
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.GoogleSignIn
import com.android.insta.feature.auth.data.GoogleSignInClient
import com.android.insta.feature.auth.data.GoogleSignInResult
import com.android.insta.ui.theme.InstaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

data class WelcomeUiState(
    val isGoogleAvailable: Boolean = false,
    val isSigningIn: Boolean = false,
    val error: UiMessage? = null,
    /** Set when a new Google user must finish onboarding; the screen navigates and clears it. */
    val onboarding: GoogleSignIn.NeedsOnboarding? = null,
)

sealed interface WelcomeEvent {
    data object GoogleStarted : WelcomeEvent
    data class GoogleResult(val result: GoogleSignInResult) : WelcomeEvent
    data object OnboardingOpened : WelcomeEvent
}

/**
 * Google is the primary sign-in and the only way to create an account. A linked account needs no navigation: the
 * session is stored, SessionManager emits LoggedIn and the root swaps to the main app.
 */
class WelcomeViewModel(private val auth: AuthRepository, isGoogleAvailable: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(WelcomeUiState(isGoogleAvailable = isGoogleAvailable))
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    fun onEvent(event: WelcomeEvent) {
        when (event) {
            WelcomeEvent.GoogleStarted -> _state.update { it.copy(isSigningIn = true, error = null) }
            is WelcomeEvent.GoogleResult -> onGoogleResult(event.result)
            WelcomeEvent.OnboardingOpened -> _state.update { it.copy(onboarding = null, isSigningIn = false) }
        }
    }

    private fun onGoogleResult(result: GoogleSignInResult) {
        when (result) {
            is GoogleSignInResult.Success -> viewModelScope.launch {
                when (val signIn = auth.signInWithGoogle(result.idToken)) {
                    // Keep the spinner: this screen is about to be replaced by the main app.
                    is ApiResult.Success -> when (val value = signIn.value) {
                        GoogleSignIn.SignedIn -> Unit
                        is GoogleSignIn.NeedsOnboarding -> _state.update { it.copy(onboarding = value) }
                    }
                    is ApiResult.Failure -> _state.update { it.copy(isSigningIn = false, error = signIn.error.toAuthMessage()) }
                }
            }
            GoogleSignInResult.Cancelled -> _state.update { it.copy(isSigningIn = false) }
            GoogleSignInResult.NoAccount -> _state.update { it.copy(isSigningIn = false, error = UiMessage.Resource(R.string.error_google_no_account)) }
            is GoogleSignInResult.Failure -> _state.update { it.copy(isSigningIn = false, error = UiMessage.Resource(R.string.error_google_failed)) }
        }
    }
}

@Composable
fun WelcomeScreen(
    onPhoneSignIn: () -> Unit,
    onNeedsOnboarding: (GoogleSignIn.NeedsOnboarding) -> Unit,
    viewModel: WelcomeViewModel = koinViewModel(),
    googleSignIn: GoogleSignInClient = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.onboarding) {
        state.onboarding?.let {
            onNeedsOnboarding(it)
            viewModel.onEvent(WelcomeEvent.OnboardingOpened)
        }
    }
    WelcomeContent(
        state = state,
        onGoogleClick = {
            viewModel.onEvent(WelcomeEvent.GoogleStarted)
            // Credential Manager needs the Activity context to show its bottom sheet.
            scope.launch { viewModel.onEvent(WelcomeEvent.GoogleResult(googleSignIn.requestIdToken(context))) }
        },
        onPhoneSignIn = onPhoneSignIn,
    )
}

@Composable
fun WelcomeContent(state: WelcomeUiState, onGoogleClick: () -> Unit, onPhoneSignIn: () -> Unit) {
    AuthScaffold {
        AuthHeader(stringResource(R.string.welcome_subtitle))
        if (state.isGoogleAvailable) {
            PrimaryButton(stringResource(R.string.action_continue_with_google), loading = state.isSigningIn, enabled = true, onClick = onGoogleClick)
        } else {
            Text(
                stringResource(R.string.welcome_google_not_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(onClick = onPhoneSignIn, enabled = !state.isSigningIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_sign_in_with_phone))
        }
        FormError(state.error)
        Text(
            stringResource(R.string.welcome_phone_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@PreviewLightDark
@Composable
private fun WelcomeContentPreview() {
    InstaTheme { WelcomeContent(WelcomeUiState(isGoogleAvailable = true), {}, {}) }
}
