package com.android.insta.feature.auth.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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

data class PhoneSignInUiState(
    val country: Country,
    val number: String = "",
    val phoneError: UiMessage? = null,
    val isSending: Boolean = false,
    /** The number has no account: offer Google, which is how accounts are created. */
    val noLinkedAccount: Boolean = false,
    /** Non-null once a code was sent. */
    val otp: OtpStepState? = null,
)

sealed interface PhoneSignInEvent {
    data class CountrySelected(val country: Country) : PhoneSignInEvent
    data class NumberChanged(val value: String) : PhoneSignInEvent
    data object SendCode : PhoneSignInEvent
    data class CodeChanged(val value: String) : PhoneSignInEvent
    data object Verify : PhoneSignInEvent
    data object Resend : PhoneSignInEvent
    data object ChangeNumber : PhoneSignInEvent
}

/** Phone + code sign-in for existing accounts. Success needs no navigation (the session switches the root). */
class PhoneSignInViewModel(private val auth: AuthRepository, private val phones: PhoneNumbers) : ViewModel() {
    private val _state = MutableStateFlow(PhoneSignInUiState(country = phones.country(phones.defaultRegion)))
    val state: StateFlow<PhoneSignInUiState> = _state.asStateFlow()

    val countries: List<Country> get() = phones.countries()
    private var phone: String? = null
    private var countdown: Job? = null

    fun onEvent(event: PhoneSignInEvent) {
        when (event) {
            is PhoneSignInEvent.CountrySelected -> _state.update { it.copy(country = event.country, phoneError = null, noLinkedAccount = false) }
            is PhoneSignInEvent.NumberChanged -> _state.update { it.copy(number = event.value, phoneError = null, noLinkedAccount = false) }
            PhoneSignInEvent.SendCode -> sendCode()
            is PhoneSignInEvent.CodeChanged -> _state.update { s -> s.copy(otp = s.otp?.copy(code = event.value, error = null)) }
            PhoneSignInEvent.Verify -> verify()
            PhoneSignInEvent.Resend -> resend()
            PhoneSignInEvent.ChangeNumber -> {
                countdown?.cancel()
                _state.update { it.copy(otp = null) }
            }
        }
    }

    private fun sendCode() {
        val s = _state.value
        if (s.isSending) return
        val e164 = phones.toE164(s.country.region, s.number)
            ?: return _state.update { it.copy(phoneError = UiMessage.Resource(R.string.error_phone_invalid)) }
        phone = e164
        _state.update { it.copy(isSending = true, phoneError = null, noLinkedAccount = false) }
        viewModelScope.launch {
            when (val result = auth.requestLoginOtp(e164)) {
                is ApiResult.Success -> showCode(result.value)
                is ApiResult.Failure -> _state.update {
                    val noAccount = (result.error as? AppError.Api)?.code == "NO_LINKED_ACCOUNT"
                    it.copy(isSending = false, noLinkedAccount = noAccount, phoneError = if (noAccount) null else result.error.toAuthMessage())
                }
            }
        }
    }

    private fun verify() {
        val otp = _state.value.otp ?: return
        if (!otp.canVerify) return
        _state.update { it.copy(otp = otp.copy(isVerifying = true, error = null)) }
        viewModelScope.launch {
            when (val result = auth.verifyLoginOtp(otp.challengeId, otp.code)) {
                // Keep the spinner: the main app replaces this screen.
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> _state.update { s -> s.copy(otp = s.otp?.copy(isVerifying = false, code = "", error = result.error.toAuthMessage())) }
            }
        }
    }

    private fun resend() {
        val otp = _state.value.otp ?: return
        val e164 = phone ?: return
        if (!otp.canResend) return
        _state.update { it.copy(otp = otp.copy(isResending = true, error = null)) }
        viewModelScope.launch {
            when (val result = auth.requestLoginOtp(e164)) {
                is ApiResult.Success -> showCode(result.value)
                is ApiResult.Failure -> _state.update { s -> s.copy(otp = s.otp?.copy(isResending = false, error = result.error.toAuthMessage())) }
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
fun PhoneSignInScreen(onBack: () -> Unit, onUseGoogle: () -> Unit, viewModel: PhoneSignInViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthScaffold {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        AuthHeader(stringResource(R.string.phone_sign_in_subtitle))
        val otp = state.otp
        if (otp == null) {
            PhoneNumberField(
                country = state.country,
                countries = viewModel.countries,
                number = state.number,
                onCountrySelected = { viewModel.onEvent(PhoneSignInEvent.CountrySelected(it)) },
                onNumberChange = { viewModel.onEvent(PhoneSignInEvent.NumberChanged(it)) },
                error = state.phoneError,
                enabled = !state.isSending,
                onDone = { viewModel.onEvent(PhoneSignInEvent.SendCode) },
            )
            PrimaryButton(stringResource(R.string.action_send_code), loading = state.isSending, enabled = state.number.isNotBlank()) {
                viewModel.onEvent(PhoneSignInEvent.SendCode)
            }
            if (state.noLinkedAccount) {
                FormError(UiMessage.Resource(R.string.error_no_linked_account))
                OutlinedButton(onClick = onUseGoogle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_continue_with_google))
                }
            }
        } else {
            OtpStep(
                state = otp,
                verifyLabel = stringResource(R.string.action_sign_in),
                onCodeChange = { viewModel.onEvent(PhoneSignInEvent.CodeChanged(it)) },
                onVerify = { viewModel.onEvent(PhoneSignInEvent.Verify) },
                onResend = { viewModel.onEvent(PhoneSignInEvent.Resend) },
            )
            TextButton(onClick = { viewModel.onEvent(PhoneSignInEvent.ChangeNumber) }, enabled = !otp.isVerifying) {
                Text(stringResource(R.string.action_use_different_number))
            }
        }
        Text(
            stringResource(R.string.phone_sign_in_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
