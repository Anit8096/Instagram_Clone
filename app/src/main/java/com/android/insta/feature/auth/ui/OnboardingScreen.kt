package com.android.insta.feature.auth.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.phone.Country
import com.android.insta.core.phone.PhoneNumbers
import com.android.insta.core.ui.OtpStep
import com.android.insta.core.ui.OtpStepState
import com.android.insta.core.ui.PhoneNumberField
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.launchCountdown
import com.android.insta.core.ui.toAuthMessage
import com.android.insta.feature.auth.data.AuthRepository
import com.android.insta.feature.auth.data.OtpChallenge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class OnboardingUiState(
    val username: String,
    val displayName: String,
    val country: Country,
    val number: String = "",
    val usernameError: UiMessage? = null,
    val displayNameError: UiMessage? = null,
    val phoneError: UiMessage? = null,
    val formError: UiMessage? = null,
    val isSending: Boolean = false,
    /** Non-null once a code was sent to the phone; the account is created when it's verified. */
    val otp: OtpStepState? = null,
    /** The Google onboarding session (15 min) ran out: the user has to start again with Google. */
    val sessionExpired: Boolean = false,
)

sealed interface OnboardingEvent {
    data class UsernameChanged(val value: String) : OnboardingEvent
    data class DisplayNameChanged(val value: String) : OnboardingEvent
    data class CountrySelected(val country: Country) : OnboardingEvent
    data class NumberChanged(val value: String) : OnboardingEvent
    data object SendCode : OnboardingEvent
    data class CodeChanged(val value: String) : OnboardingEvent
    data object CreateAccount : OnboardingEvent
    data object Resend : OnboardingEvent
    data object ChangeNumber : OnboardingEvent
}

/**
 * New Google user: choose a username and display name, verify a phone with a code, then the account is created and
 * the session switches the root to the main app.
 */
class OnboardingViewModel(
    private val onboardingToken: String,
    suggestedUsername: String,
    displayName: String,
    private val auth: AuthRepository,
    private val phones: PhoneNumbers,
) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingUiState(username = suggestedUsername, displayName = displayName, country = phones.country(phones.defaultRegion)),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    val countries: List<Country> get() = phones.countries()
    private var phone: String? = null
    private var countdown: Job? = null

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.UsernameChanged -> _state.update { it.copy(username = event.value.lowercase(), usernameError = null, formError = null) }
            is OnboardingEvent.DisplayNameChanged -> _state.update {
                it.copy(displayName = event.value, displayNameError = AuthValidation.displayNameError(event.value)?.let(UiMessage::Resource))
            }
            is OnboardingEvent.CountrySelected -> _state.update { it.copy(country = event.country, phoneError = null) }
            is OnboardingEvent.NumberChanged -> _state.update { it.copy(number = event.value, phoneError = null) }
            OnboardingEvent.SendCode -> sendCode()
            is OnboardingEvent.CodeChanged -> _state.update { s -> s.copy(otp = s.otp?.copy(code = event.value, error = null)) }
            OnboardingEvent.CreateAccount -> createAccount()
            OnboardingEvent.Resend -> resend()
            OnboardingEvent.ChangeNumber -> {
                countdown?.cancel()
                _state.update { it.copy(otp = null) }
            }
        }
    }

    /** Validates the profile fields locally; returns false (with field errors shown) when something's wrong. */
    private fun profileValid(): Boolean {
        val s = _state.value
        val usernameError = AuthValidation.usernameError(s.username)?.let(UiMessage::Resource)
        val nameError = AuthValidation.displayNameError(s.displayName)?.let(UiMessage::Resource)
        _state.update { it.copy(usernameError = usernameError, displayNameError = nameError) }
        return usernameError == null && nameError == null
    }

    private fun sendCode() {
        val s = _state.value
        if (s.isSending || !profileValid()) return
        val e164 = phones.toE164(s.country.region, s.number)
            ?: return _state.update { it.copy(phoneError = UiMessage.Resource(R.string.error_phone_invalid)) }
        phone = e164
        _state.update { it.copy(isSending = true, phoneError = null, formError = null) }
        viewModelScope.launch {
            when (val result = auth.requestOnboardingOtp(onboardingToken, e164)) {
                is ApiResult.Success -> showCode(result.value)
                is ApiResult.Failure -> onFailure(result.error) { it.copy(isSending = false) }
            }
        }
    }

    private fun createAccount() {
        val s = _state.value
        val otp = s.otp ?: return
        if (!otp.canVerify || !profileValid()) return
        _state.update { it.copy(otp = otp.copy(isVerifying = true, error = null), formError = null) }
        viewModelScope.launch {
            val result = auth.completeOnboarding(onboardingToken, otp.challengeId, otp.code, AuthValidation.normalizeUsername(s.username), s.displayName)
            when (result) {
                // The session switches the root to the main app.
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> onFailure(result.error) { st -> st.copy(otp = st.otp?.copy(isVerifying = false)) }
            }
        }
    }

    private fun resend() {
        val otp = _state.value.otp ?: return
        val e164 = phone ?: return
        if (!otp.canResend) return
        _state.update { it.copy(otp = otp.copy(isResending = true, error = null)) }
        viewModelScope.launch {
            when (val result = auth.requestOnboardingOtp(onboardingToken, e164)) {
                is ApiResult.Success -> showCode(result.value)
                is ApiResult.Failure -> onFailure(result.error) { st -> st.copy(otp = st.otp?.copy(isResending = false)) }
            }
        }
    }

    /** Routes each server error to the field it belongs to. */
    private fun onFailure(error: AppError, reset: (OnboardingUiState) -> OnboardingUiState) {
        val api = error as? AppError.Api
        val code = api?.code
        _state.update { current ->
            val s = reset(current)
            when {
                code == "INVALID_ONBOARDING_TOKEN" -> s.copy(sessionExpired = true, formError = error.toAuthMessage())
                code == "USERNAME_TAKEN" -> s.copy(usernameError = UiMessage.Resource(R.string.error_username_taken))
                api?.fieldErrors?.containsKey("username") == true -> s.copy(usernameError = UiMessage.Resource(R.string.error_username_format))
                code == "PHONE_IN_USE" -> s.copy(phoneError = error.toAuthMessage(), otp = null)
                api?.fieldErrors?.containsKey("phone") == true -> s.copy(phoneError = error.toAuthMessage(), otp = null)
                code?.startsWith("OTP_") == true && s.otp != null -> s.copy(otp = s.otp.copy(code = "", error = error.toAuthMessage()))
                else -> s.copy(formError = error.toAuthMessage())
            }
        }
    }

    private fun showCode(challenge: OtpChallenge) {
        _state.update { it.copy(isSending = false, otp = OtpStepState.from(challenge)) }
        countdown?.cancel()
        countdown = viewModelScope.launchCountdown(challenge.resendInSeconds) { left ->
            _state.update { s -> s.copy(otp = s.otp?.copy(resendIn = left)) }
        }
    }
}

@Composable
fun OnboardingScreen(
    onboardingToken: String,
    suggestedUsername: String,
    displayName: String,
    onBack: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(key = onboardingToken) { parametersOf(onboardingToken, suggestedUsername, displayName) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val otp = state.otp
    val busy = state.isSending || otp?.isVerifying == true
    AuthScaffold {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        AuthHeader(stringResource(R.string.onboarding_subtitle))
        AuthTextField(
            value = state.username,
            onValueChange = { viewModel.onEvent(OnboardingEvent.UsernameChanged(it)) },
            label = stringResource(R.string.field_username),
            error = state.usernameError,
            enabled = !busy,
            supportingText = stringResource(R.string.field_username_hint),
        )
        AuthTextField(
            value = state.displayName,
            onValueChange = { viewModel.onEvent(OnboardingEvent.DisplayNameChanged(it)) },
            label = stringResource(R.string.field_display_name),
            error = state.displayNameError,
            enabled = !busy,
            keyboardType = KeyboardType.Text,
        )
        PhoneNumberField(
            country = state.country,
            countries = viewModel.countries,
            number = state.number,
            onCountrySelected = { viewModel.onEvent(OnboardingEvent.CountrySelected(it)) },
            onNumberChange = { viewModel.onEvent(OnboardingEvent.NumberChanged(it)) },
            error = state.phoneError,
            // The code is tied to this number: change it with "Use a different number".
            enabled = !busy && otp == null,
            onDone = { viewModel.onEvent(OnboardingEvent.SendCode) },
        )
        FormError(state.formError)
        if (state.sessionExpired) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_start_again)) }
        } else if (otp == null) {
            PrimaryButton(stringResource(R.string.action_send_code), loading = state.isSending, enabled = state.number.isNotBlank()) {
                viewModel.onEvent(OnboardingEvent.SendCode)
            }
        } else {
            OtpStep(
                state = otp,
                verifyLabel = stringResource(R.string.action_create_account),
                onCodeChange = { viewModel.onEvent(OnboardingEvent.CodeChanged(it)) },
                onVerify = { viewModel.onEvent(OnboardingEvent.CreateAccount) },
                onResend = { viewModel.onEvent(OnboardingEvent.Resend) },
            )
            TextButton(onClick = { viewModel.onEvent(OnboardingEvent.ChangeNumber) }, enabled = !otp.isVerifying) {
                Text(stringResource(R.string.action_use_different_number))
            }
        }
    }
}
