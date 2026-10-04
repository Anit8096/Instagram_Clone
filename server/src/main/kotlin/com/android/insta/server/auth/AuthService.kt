package com.android.insta.server.auth

import com.android.insta.server.common.ApiException
import com.android.insta.server.users.NewUser
import com.android.insta.server.users.UserRecord
import com.android.insta.server.users.UserRepository
import com.android.insta.server.users.toDto
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.random.Random
import kotlin.uuid.Uuid

class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val hasher: PasswordHasher,
    private val tokens: TokenService,
    private val google: GoogleTokenVerifier,
    private val clock: Clock,
) {
    // Verified against when the login doesn't exist, so response time doesn't reveal valid usernames.
    private val dummyHash by lazy { hasher.hash("timing-equalizer-password") }

    suspend fun register(request: RegisterRequest): AuthResponse {
        val valid = AuthValidation.validate(request)
        if (users.usernameExists(valid.username)) throw usernameTaken()
        if (users.findByEmail(valid.email) != null) {
            throw ApiException(HttpStatusCode.Conflict, "EMAIL_TAKEN", "Email is already registered")
        }
        val hash = withContext(Dispatchers.Default) { hasher.hash(valid.password) }
        val user = users.create(
            NewUser(
                username = valid.username,
                email = valid.email,
                passwordHash = hash,
                googleSub = null,
                displayName = valid.displayName.orEmpty(),
            ),
        )
        return issueTokens(user, isNewUser = true)
    }

    suspend fun login(request: LoginRequest): AuthResponse {
        val login = request.login.trim().lowercase()
        val user = if ('@' in login) users.findByEmail(login) else users.findByUsername(login)
        val hash = user?.passwordHash
        val ok = withContext(Dispatchers.Default) {
            if (hash == null) {
                hasher.verify(request.password, dummyHash)
                false
            } else {
                hasher.verify(request.password, hash)
            }
        }
        if (!ok || user == null) {
            throw ApiException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Incorrect username/email or password")
        }
        return issueTokens(user)
    }

    /**
     * Signs in with Google: an existing linked account, else links to an account with the same
     * verified email, else creates a new password-less account with a generated username.
     */
    suspend fun loginWithGoogle(request: GoogleLoginRequest): AuthResponse {
        val identity = google.verify(request.idToken)
        users.findByGoogleSub(identity.subject)?.let { return issueTokens(it) }

        val email = identity.email?.takeIf { identity.emailVerified }
        if (email != null) {
            val existing = users.findByEmail(email)
            if (existing != null) {
                if (existing.googleSub != null) {
                    throw ApiException(HttpStatusCode.Conflict, "GOOGLE_ACCOUNT_LINKED", "Account is linked to a different Google account")
                }
                users.linkGoogle(existing.id, identity.subject)
                return issueTokens(existing.copy(googleSub = identity.subject))
            }
        }

        val user = users.create(
            NewUser(
                username = generateUsername(email ?: identity.name ?: "user"),
                email = email,
                passwordHash = null,
                googleSub = identity.subject,
                displayName = identity.name.orEmpty().take(AuthValidation.DISPLAY_NAME_MAX),
            ),
        )
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
            user = user.toDto(),
        )
    }

    suspend fun logout(request: RefreshRequest) {
        refreshTokens.revokeFamilyOf(TokenService.hashRefreshToken(request.refreshToken), clock.instant())
    }

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
            user = user.toDto(),
            isNewUser = isNewUser,
        )
    }

    private suspend fun generateUsername(seed: String): String {
        val base = seed.substringBefore('@').lowercase().filter { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' }
            .take(24)
            .padEnd(3, '_')
        if (!users.usernameExists(base)) return base
        repeat(10) {
            val candidate = "${base}_${Random.nextInt(1000, 10000)}"
            if (!users.usernameExists(candidate)) return candidate
        }
        throw usernameTaken()
    }

    private fun usernameTaken() = ApiException(HttpStatusCode.Conflict, "USERNAME_TAKEN", "Username is already taken")

    private fun invalidRefreshToken() =
        ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired")
}
