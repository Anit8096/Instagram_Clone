package com.android.insta.server.auth

import com.android.insta.server.common.ApiException
import com.android.insta.server.users.NewUser
import com.android.insta.server.users.UserRecord
import com.android.insta.server.users.UserRepository
import com.android.insta.server.users.toMeDto
import io.ktor.http.HttpStatusCode
import java.time.Clock
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Google-first authentication (docs/SPEC.md): accounts are created only through Google + onboarding with a verified
 * phone; phone + OTP signs in to an existing account and never creates one.
 */
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val tokens: TokenService,
    private val google: GoogleTokenVerifier,
    private val otp: OtpService,
    private val clock: Clock,
) {
    suspend fun loginWithGoogle(request: GoogleLoginRequest): GoogleAuthResult {
        val identity = google.verify(request.idToken)
        users.findByGoogleSub(identity.subject)?.let { return GoogleAuthResult.SignedIn(issueTokens(it)) }
        return GoogleAuthResult.NeedsOnboarding(
            onboardingToken = tokens.createOnboardingToken(identity),
            suggestedUsername = suggestUsername(identity.email?.substringBefore('@') ?: identity.name ?: "user"),
            displayName = identity.name.orEmpty().take(AuthValidation.DISPLAY_NAME_MAX),
            email = identity.email?.takeIf { identity.emailVerified },
        )
    }

    /** Only numbers linked to an account get a code; anything else is "no linked account" (spec decision). */
    suspend fun requestPhoneLogin(request: PhoneOtpRequest): OtpChallengeDto {
        val phone = PhoneNumbers.normalize(request.phone)
        val user = users.findByPhone(phone) ?: throw noLinkedAccount()
        return otp.request(phone, OtpPurpose.LOGIN, OtpOwner.User(user.id))
    }

    suspend fun verifyPhoneLogin(request: VerifyOtpRequest): AuthResponse {
        val userId = loginChallengeOwner(request.challengeId)
        otp.verify(request.challengeId, request.code, OtpPurpose.LOGIN, OtpOwner.User(userId))
        val user = users.findById(userId) ?: throw noLinkedAccount()
        return issueTokens(user)
    }

    suspend fun requestOnboardingOtp(request: OnboardingOtpRequest): OtpChallengeDto {
        val identity = tokens.verifyOnboardingToken(request.onboardingToken)
        val phone = PhoneNumbers.normalize(request.phone)
        if (users.findByPhone(phone) != null) throw UserRepository.phoneInUse()
        return otp.request(phone, OtpPurpose.ONBOARDING, OtpOwner.Onboarding(identity.subject))
    }

    /** Creates the account only after the phone code checks out; username and phone uniqueness are re-checked. */
    suspend fun completeOnboarding(request: CompleteOnboardingRequest): AuthResponse {
        val identity = tokens.verifyOnboardingToken(request.onboardingToken)
        val (username, displayName) = AuthValidation.validateProfile(request.username, request.displayName)
        users.findByGoogleSub(identity.subject)?.let { return issueTokens(it) } // double submit: already created
        if (users.usernameExists(username)) throw UserRepository.usernameTaken()
        val phone = otp.verify(request.challengeId, request.code, OtpPurpose.ONBOARDING, OtpOwner.Onboarding(identity.subject))
        val user = users.create(NewUser(username, identity.email, identity.subject, phone, displayName.ifEmpty { identity.name.orEmpty().take(AuthValidation.DISPLAY_NAME_MAX) }))
        return issueTokens(user, isNewUser = true)
    }

    suspend fun refresh(request: RefreshRequest): AuthResponse {
        val newToken = tokens.newRefreshToken()
        val result = refreshTokens.rotate(
            presentedHash = TokenService.hashRefreshToken(request.refreshToken),
            newHash = TokenService.hashRefreshToken(newToken),
            newExpiry = tokens.refreshExpiry(),
            now = clock.instant(),
        )
        val userId = when (result) {
            is RotationResult.Rotated -> result.userId
            RotationResult.Reused, RotationResult.Invalid -> throw invalidRefreshToken()
        }
        val user = users.findById(userId) ?: throw invalidRefreshToken()
        return AuthResponse(
            accessToken = tokens.createAccessToken(user.id),
            refreshToken = newToken,
            expiresIn = tokens.accessTtlSeconds,
            user = user.toMeDto(),
        )
    }

    suspend fun logout(request: RefreshRequest) {
        refreshTokens.revokeFamilyOf(TokenService.hashRefreshToken(request.refreshToken), clock.instant())
    }

    /** The login challenge records its account; an unknown id fails the same way as a wrong code. */
    private suspend fun loginChallengeOwner(challengeId: String): Uuid =
        otp.ownerOf(challengeId) ?: throw ApiException(HttpStatusCode.BadRequest, "OTP_INVALID", "That code isn't right")

    private suspend fun issueTokens(user: UserRecord, isNewUser: Boolean = false): AuthResponse {
        val refreshToken = tokens.newRefreshToken()
        refreshTokens.insert(
            userId = user.id,
            familyId = Uuid.random(),
            tokenHash = TokenService.hashRefreshToken(refreshToken),
            expiresAt = tokens.refreshExpiry(),
        )
        return AuthResponse(
            accessToken = tokens.createAccessToken(user.id),
            refreshToken = refreshToken,
            expiresIn = tokens.accessTtlSeconds,
            user = user.toMeDto(),
            isNewUser = isNewUser,
        )
    }

    /** A free username from the seed; the user can change it on the onboarding screen. */
    private suspend fun suggestUsername(seed: String): String {
        val base = seed.lowercase().filter { (it.isLetterOrDigit() && it.code < 128) || it == '.' || it == '_' }
            .take(24)
            .padEnd(3, '_')
        if (!users.usernameExists(base)) return base
        repeat(10) {
            val candidate = "${base}_${Random.nextInt(1000, 10000)}"
            if (!users.usernameExists(candidate)) return candidate
        }
        return "${base}_${Random.nextInt(10000, 100000)}"
    }

    private fun noLinkedAccount() =
        ApiException(HttpStatusCode.NotFound, "NO_LINKED_ACCOUNT", "No account is linked to this number. Sign in with Google to create one.")

    private fun invalidRefreshToken() =
        ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired")
}
