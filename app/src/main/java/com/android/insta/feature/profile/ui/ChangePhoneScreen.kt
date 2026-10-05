package com.android.insta.feature.profile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.phone.Country
import com.android.insta.core.phone.PhoneNumbers
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.core.ui.OtpStep
import com.android.insta.core.ui.OtpStepState
import com.android.insta.core.ui.PhoneNumberField
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.launchCountdown
import com.android.insta.core.ui.toAuthMessage
import com.android.insta.feature.profile.data.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class ChangePhoneUiState(
    /** The current number, formatted for display. */
    val currentPhone: String?,
    val country: Country,
    val number: String = "",
    val phoneError: UiMessage? = null,
    val isSending: Boolean = false,
    val otp: OtpStepState? = null,
    val done: Boolean = false,
)

sealed interface ChangePhoneEvent {
    data class CountrySelected(val country: Country) : ChangePhoneEvent
    data class NumberChanged(val value: String) : ChangePhoneEvent
    data object SendCode : ChangePhoneEvent
    data class CodeChanged(val value: String) : ChangePhoneEvent
    data object Verify : ChangePhoneEvent
    data object Resend : ChangePhoneEvent
    data object ChangeNumber : ChangePhoneEvent
}

/** New number → code sent to that new number → verified → it replaces the old one (and is used for phone sign-in). */
class ChangePhoneViewModel(
    private val profiles: ProfileRepository,
    private val phones: PhoneNumbers,
    sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ChangePhoneUiState(
            currentPhone = (sessionManager.state.value as? SessionState.LoggedIn)?.user?.phone?.let(phones::format),
            country = phones.country(phones.defaultRegion),
        ),
    )
    val state: StateFlow<ChangePhoneUiState> = _state.asStateFlow()

    val countries: List<Country> get() = phones.countries()
    private var phone: String? = null
    private var countdown: Job? = null

    fun onEvent(event: ChangePhoneEvent) {
        when (event) {
            is ChangePhoneEvent.CountrySelected -> _state.update { it.copy(country = event.country, phoneError = null) }
            is ChangePhoneEvent.NumberChanged -> _state.update { it.copy(number = event.value, phoneError = null) }
            ChangePhoneEvent.SendCode -> send(resend = false)
            is ChangePhoneEvent.CodeChanged -> _state.update { s -> s.copy(otp = s.otp?.copy(code = event.value, error = null)) }
            ChangePhoneEvent.Verify -> verify()
            ChangePhoneEvent.Resend -> send(resend = true)
            ChangePhoneEvent.ChangeNumber -> {
                countdown?.cancel()
                _state.update { it.copy(otp = null) }
            }
        }
    }

    private fun send(resend: Boolean) {
        val s = _state.value
        if (s.isSending || (resend && s.otp?.canResend != true)) return
        val e164 = if (resend) phone else phones.toE164(s.country.region, s.number)
        if (e164 == null) return _state.update { it.copy(phoneError = UiMessage.Resource(R.string.error_phone_invalid)) }
        phone = e164
        _state.update { it.copy(isSending = true, phoneError = null, otp = it.otp?.copy(isResending = true, error = null)) }
        viewModelScope.launch {
            when (val result = profiles.requestPhoneChange(e164)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSending = false, otp = OtpStepState.from(result.value)) }
                    countdown?.cancel()
                    countdown = viewModelScope.launchCountdown(result.value.resendInSeconds) { left ->
                        _state.update { st -> st.copy(otp = st.otp?.copy(resendIn = left)) }
                    }
                }
                is ApiResult.Failure -> _state.update {
                    val onCode = it.otp != null && (result.error as? AppError.Api)?.code?.startsWith("OTP_") == true
                    if (onCode) it.copy(isSending = false, otp = it.otp?.copy(isResending = false, error = result.error.toAuthMessage()))
                    else it.copy(isSending = false, otp = null, phoneError = result.error.toAuthMessage())
                }
            }
        }
    }

    private fun verify() {
        val otp = _state.value.otp ?: return
        if (!otp.canVerify) return
        _state.update { it.copy(otp = otp.copy(isVerifying = true, error = null)) }
        viewModelScope.launch {
            when (val result = profiles.confirmPhoneChange(otp.challengeId, otp.code)) {
                is ApiResult.Success -> _state.update { it.copy(done = true) }
                is ApiResult.Failure -> _state.update { s ->
                    if ((result.error as? AppError.Api)?.code == "PHONE_IN_USE") s.copy(otp = null, phoneError = result.error.toAuthMessage())
                    else s.copy(otp = s.otp?.copy(isVerifying = false, code = "", error = result.error.toAuthMessage()))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePhoneScreen(onDone: () -> Unit, viewModel: ChangePhoneViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.change_phone_title)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.currentPhone?.let {
                    Text(stringResource(R.string.change_phone_current, it), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.change_phone_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val otp = state.otp
                PhoneNumberField(
                    country = state.country,
                    countries = viewModel.countries,
                    number = state.number,
                    onCountrySelected = { viewModel.onEvent(ChangePhoneEvent.CountrySelected(it)) },
                    onNumberChange = { viewModel.onEvent(ChangePhoneEvent.NumberChanged(it)) },
                    error = state.phoneError,
                    enabled = !state.isSending && otp == null,
                    onDone = { viewModel.onEvent(ChangePhoneEvent.SendCode) },
                )
                if (otp == null) {
                    Button(onClick = { viewModel.onEvent(ChangePhoneEvent.SendCode) }, enabled = !state.isSending && state.number.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        if (state.isSending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.action_send_code))
                    }
                } else {
                    OtpStep(
                        state = otp,
                        verifyLabel = stringResource(R.string.action_save),
                        onCodeChange = { viewModel.onEvent(ChangePhoneEvent.CodeChanged(it)) },
                        onVerify = { viewModel.onEvent(ChangePhoneEvent.Verify) },
                        onResend = { viewModel.onEvent(ChangePhoneEvent.Resend) },
                    )
                    TextButton(onClick = { viewModel.onEvent(ChangePhoneEvent.ChangeNumber) }, enabled = !otp.isVerifying) {
                        Text(stringResource(R.string.action_use_different_number))
                    }
                }
            }
        }
    }
}
