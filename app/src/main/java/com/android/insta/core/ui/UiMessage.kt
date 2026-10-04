package com.android.insta.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.insta.R
import com.android.insta.core.network.AppError

/** Text for the UI that ViewModels can create without a Context. */
sealed interface UiMessage {
    data class Resource(@StringRes val id: Int) : UiMessage
    data class Raw(val text: String) : UiMessage
}

@Composable
fun UiMessage.asString(): String = when (this) {
    is UiMessage.Resource -> stringResource(id)
    is UiMessage.Raw -> text
}

/** Generic mapping for errors a screen doesn't handle specially. */
fun AppError.toUiMessage(): UiMessage = when (this) {
    AppError.Network -> UiMessage.Resource(R.string.error_network)
    is AppError.Api -> when (code) {
        "RATE_LIMITED" -> UiMessage.Resource(R.string.error_rate_limited)
        "INTERNAL" -> UiMessage.Resource(R.string.error_server)
        else -> if (status >= 500) UiMessage.Resource(R.string.error_server) else UiMessage.Raw(message)
    }
    is AppError.Unexpected -> UiMessage.Resource(R.string.error_generic)
}
