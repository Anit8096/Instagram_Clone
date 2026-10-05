package com.android.insta.core.ui

import com.android.insta.R
import com.android.insta.core.network.AppError
import com.android.insta.feature.auth.data.OtpChallenge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The "enter the code we sent" step, shared by phone sign-in, onboarding, change phone and delete account. */
data class OtpStepState(
    val challengeId: String,
    val sentTo: String,
    val code: String = "",
    val error: UiMessage? = null,
    /** Seconds until "Resend code" is allowed again. */
    val resendIn: Int = 0,
    val isVerifying: Boolean = false,
    val isResending: Boolean = false,
    /** Shown only in debug builds when the server echoes codes (OTP_DEV_ECHO). */
    val devCode: String? = null,
) {
    val canVerify: Boolean get() = code.length == CODE_LENGTH && !isVerifying
    val canResend: Boolean get() = resendIn == 0 && !isResending && !isVerifying

    companion object {
        const val CODE_LENGTH = 6

        fun from(challenge: OtpChallenge) = OtpStepState(challenge.challengeId, challenge.sentTo, resendIn = challenge.resendInSeconds, devCode = challenge.devCode)
    }
}

/** Keeps only digits, at most [OtpStepState.CODE_LENGTH] of them (paste-friendly). */
fun sanitizeCode(raw: String): String = raw.filter(Char::isDigit).take(OtpStepState.CODE_LENGTH)

/** Ticks once per second from [seconds] down to 0. */
fun CoroutineScope.launchCountdown(seconds: Int, onTick: (Int) -> Unit): Job = launch {
    var left = seconds.coerceAtLeast(0)
    onTick(left)
    while (left > 0) {
        delay(1_000)
        left--
        onTick(left)
    }
}

/** Seconds to wait from an `OTP_RATE_LIMITED` error, if that's what this is. */
fun AppError.retryAfterSeconds(): Int? =
    (this as? AppError.Api)?.takeIf { it.code == "OTP_RATE_LIMITED" }?.fieldErrors?.get("retryAfter")?.toIntOrNull()

/** Messages for the sign-in, onboarding and code errors; anything else falls back to the generic mapping. */
fun AppError.toAuthMessage(): UiMessage {
    if (this !is AppError.Api) return toUiMessage()
    return when (code) {
        "NO_LINKED_ACCOUNT" -> UiMessage.Resource(R.string.error_no_linked_account)
        "PHONE_IN_USE" -> UiMessage.Resource(R.string.error_phone_in_use)
        "USERNAME_TAKEN" -> UiMessage.Resource(R.string.error_username_taken)
        "INVALID_ONBOARDING_TOKEN" -> UiMessage.Resource(R.string.error_onboarding_expired)
        "OTP_INVALID" -> fieldErrors["attemptsLeft"]?.toIntOrNull()
            ?.let { UiMessage.Plural(R.plurals.error_otp_invalid_attempts, it) }
            ?: UiMessage.Resource(R.string.error_otp_invalid)
        "OTP_EXPIRED" -> UiMessage.Resource(R.string.error_otp_expired)
        "OTP_USED" -> UiMessage.Resource(R.string.error_otp_used)
        "OTP_TOO_MANY_ATTEMPTS" -> UiMessage.Resource(R.string.error_otp_too_many)
        "OTP_RATE_LIMITED" -> retryAfterSeconds()?.let { UiMessage.Plural(R.plurals.error_otp_rate_limited, it) }
            ?: UiMessage.Resource(R.string.error_rate_limited)
        "INVALID_GOOGLE_TOKEN", "GOOGLE_SIGN_IN_DISABLED" -> UiMessage.Resource(R.string.error_google_failed)
        "VALIDATION_ERROR" -> if ("phone" in fieldErrors) UiMessage.Resource(R.string.error_phone_invalid) else toUiMessage()
        else -> toUiMessage()
    }
}
