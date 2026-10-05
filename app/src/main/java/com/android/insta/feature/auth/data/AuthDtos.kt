package com.android.insta.feature.auth.data

import com.android.insta.core.session.SessionUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for /api/v1/auth/*, mirroring server/src/main/kotlin/.../auth/AuthDtos.kt and Otp.kt.

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class PhoneOtpRequest(val phone: String)

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
    val displayName: String,
)

/** Re-authentication for `DELETE /me`: a code from `POST /me/delete/otp`, or a fresh Google ID token. */
@Serializable
data class DeleteAccountRequest(val challengeId: String? = null, val code: String? = null, val googleIdToken: String? = null)

@Serializable
data class OtpChallengeDto(
    val challengeId: String,
    val sentTo: String,
    val expiresIn: Long,
    val resendIn: Long,
    val devCode: String? = null,
)

/**
 * A user as the API returns it. Public payloads have no contact details; the signed-in user's own data
 * (`/me`, auth responses) also has [phone] and [email].
 */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val createdAt: String,
    val phone: String? = null,
    val email: String? = null,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
    val isNewUser: Boolean = false,
)

/** `POST /auth/google`: signed in, or a new Google user who must finish onboarding (`type` discriminator). */
@Serializable
sealed interface GoogleAuthResultDto {
    @Serializable @SerialName("signed_in")
    data class SignedIn(val auth: AuthResponse) : GoogleAuthResultDto

    @Serializable @SerialName("needs_onboarding")
    data class NeedsOnboarding(
        val onboardingToken: String,
        val suggestedUsername: String,
        val displayName: String,
        val email: String? = null,
    ) : GoogleAuthResultDto
}

/** A code was sent; [devCode] is only filled by a server running with OTP_DEV_ECHO (local demos). */
data class OtpChallenge(val challengeId: String, val sentTo: String, val resendInSeconds: Int, val devCode: String?)

fun OtpChallengeDto.toChallenge() = OtpChallenge(challengeId, sentTo, resendIn.toInt(), devCode)

fun UserDto.toSessionUser() = SessionUser(id = id, username = username, displayName = displayName, avatarUrl = avatarUrl, phone = phone)
