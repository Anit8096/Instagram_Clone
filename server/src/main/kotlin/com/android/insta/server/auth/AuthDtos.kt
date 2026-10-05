package com.android.insta.server.auth

import com.android.insta.server.common.ValidationException
import com.android.insta.server.users.MeDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** Phone sign-in step 1 (existing accounts only) and the change-phone request. */
@Serializable
data class PhoneOtpRequest(val phone: String)

/** Phone sign-in step 2, change phone and delete-account confirmation. */
@Serializable
data class VerifyOtpRequest(val challengeId: String, val code: String)

@Serializable
data class OnboardingOtpRequest(val onboardingToken: String, val phone: String)

@Serializable
data class CompleteOnboardingRequest(
    val onboardingToken: String,
    val challengeId: String,
    val code: String,
    val username: String,
    val displayName: String = "",
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: MeDto,
    val isNewUser: Boolean = false,
)

/** `POST /auth/google`: either signed in, or a new Google user who must finish onboarding (`type` discriminator). */
@Serializable
sealed interface GoogleAuthResult {
    @Serializable @SerialName("signed_in")
    data class SignedIn(val auth: AuthResponse) : GoogleAuthResult

    @Serializable @SerialName("needs_onboarding")
    data class NeedsOnboarding(
        val onboardingToken: String,
        val suggestedUsername: String,
        val displayName: String,
        val email: String? = null,
    ) : GoogleAuthResult
}

object AuthValidation {
    val USERNAME = Regex("^[a-z0-9._]{3,30}$")
    const val DISPLAY_NAME_MAX = 60

    fun normalizeUsername(raw: String) = raw.trim().lowercase()

    /** Returns normalised (username, displayName), or throws [ValidationException] listing every bad field. */
    fun validateProfile(username: String, displayName: String): Pair<String, String> {
        val normalized = normalizeUsername(username)
        val name = displayName.trim()
        val errors = buildMap {
            if (!USERNAME.matches(normalized)) put("username", "3-30 characters: lowercase letters, digits, '.' or '_'")
            if (name.length > DISPLAY_NAME_MAX) put("displayName", "At most $DISPLAY_NAME_MAX characters")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return normalized to name
    }
}
