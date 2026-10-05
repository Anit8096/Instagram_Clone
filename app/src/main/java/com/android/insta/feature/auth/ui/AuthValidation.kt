package com.android.insta.feature.auth.ui

import com.android.insta.R

/**
 * Client-side copy of the server's onboarding rules (server AuthValidation) so users get instant feedback.
 * The server stays authoritative and its field errors are shown too.
 */
object AuthValidation {
    private val USERNAME = Regex("^[a-z0-9._]{3,30}$")
    const val DISPLAY_NAME_MAX = 60

    fun normalizeUsername(raw: String) = raw.trim().lowercase()

    fun usernameError(username: String): Int? =
        if (USERNAME.matches(normalizeUsername(username))) null else R.string.error_username_format

    fun displayNameError(name: String): Int? =
        if (name.trim().length <= DISPLAY_NAME_MAX) null else R.string.error_display_name_length
}
