package com.android.insta.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.insta.BuildConfig
import com.android.insta.R
import com.android.insta.core.phone.Country

/** Country selector (flag + dial code, opens a searchable sheet) in front of the national number. */
@Composable
fun PhoneNumberField(
    country: Country,
    countries: List<Country>,
    number: String,
    onCountrySelected: (Country) -> Unit,
    onNumberChange: (String) -> Unit,
    error: UiMessage?,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val countryLabel = stringResource(R.string.cd_country_code, country.name, country.dialCode)
    OutlinedTextField(
        value = number,
        onValueChange = { value -> onNumberChange(value.filter { it.isDigit() || it in " -()+" }.take(20)) },
        label = { Text(stringResource(R.string.field_phone)) },
        leadingIcon = {
            TextButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = countryLabel }) {
                Text("${country.flag} +${country.dialCode}")
            }
        },
        isError = error != null,
        supportingText = error?.let { { Text(it.asString()) } },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumberNational },
    )
    if (picking) {
        CountryPickerSheet(countries, onPick = { onCountrySelected(it); picking = false }, onDismiss = { picking = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerSheet(countries: List<Country>, onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, countries) {
        val q = query.trim().removePrefix("+")
        if (q.isEmpty()) countries
        else countries.filter { it.name.contains(q, ignoreCase = true) || it.dialCode.toString().startsWith(q) || it.region.equals(q, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_country)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn {
            items(filtered, key = { it.region }) { country ->
                ListItem(
                    leadingContent = { Text(country.flag, style = MaterialTheme.typography.titleLarge) },
                    headlineContent = { Text(country.name) },
                    trailingContent = { Text("+${country.dialCode}") },
                    modifier = Modifier.clickable { onPick(country) },
                )
            }
        }
    }
}

/** "Enter the 6-digit code we sent to …" with verify, resend countdown and SMS-code autofill. */
@Composable
fun OtpStep(
    state: OtpStepState,
    verifyLabel: String,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.otp_sent_to, state.sentTo), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.code,
            onValueChange = { onCodeChange(sanitizeCode(it)) },
            label = { Text(stringResource(R.string.field_otp_code)) },
            isError = state.error != null,
            supportingText = state.error?.let { { Text(it.asString(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
            enabled = !state.isVerifying,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canVerify) onVerify() }),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
        )
        if (BuildConfig.DEBUG && state.devCode != null) {
            Text(stringResource(R.string.otp_dev_code, state.devCode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
        Button(onClick = onVerify, enabled = state.canVerify, modifier = Modifier.fillMaxWidth()) {
            if (state.isVerifying) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(verifyLabel)
        }
        TextButton(onClick = onResend, enabled = state.canResend, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.resendIn > 0) stringResource(R.string.otp_resend_in, state.resendIn / 60, state.resendIn % 60)
                else stringResource(R.string.otp_resend),
            )
        }
    }
}
