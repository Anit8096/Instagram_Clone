package com.android.insta.feature.auth.ui

import com.android.insta.R

/**
 * Client-side copy of the server's rules (server AuthValidation) so users get instant feedback.
 * The server stays authoritative and its field errors are shown too.
 */
object AuthValidation {
    private val USERNAME = Regex("^[a-z0-9._]{3,30}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 128
    const val DISPLAY_NAME_MAX = 60

    fun usernameError(username: String): Int? =
        if (USERNAME.matches(username.trim().lowercase())) null else R.string.error_username_format

    fun emailError(email: String): Int? = when {
        email.isBlank() -> R.string.error_required
        email.trim().length > 254 || !EMAIL.matches(email.trim()) -> R.string.error_email_format
        else -> null
    }

    fun passwordError(password: String): Int? =
        if (password.length in PASSWORD_MIN..PASSWORD_MAX) null else R.string.error_password_length

    fun displayNameError(name: String): Int? =
        if (name.trim().length <= DISPLAY_NAME_MAX) null else R.string.error_display_name_length
}
