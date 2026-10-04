package com.android.insta.server.auth

import com.android.insta.server.common.ValidationException
import com.android.insta.server.users.UserDto
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String? = null,
)

/** [login] is a username or an email address. */
@Serializable
data class LoginRequest(val login: String, val password: String)

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto,
    val isNewUser: Boolean = false,
)

object AuthValidation {
    val USERNAME = Regex("^[a-z0-9._]{3,30}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 128
    const val DISPLAY_NAME_MAX = 60

    fun normalizeUsername(raw: String) = raw.trim().lowercase()
    fun normalizeEmail(raw: String) = raw.trim().lowercase()

    /** Returns the request with normalized fields, or throws [ValidationException] listing every bad field. */
    fun validate(request: RegisterRequest): RegisterRequest {
        val normalized = request.copy(
            username = normalizeUsername(request.username),
            email = normalizeEmail(request.email),
            displayName = request.displayName?.trim(),
        )
        val errors = buildMap {
            if (!USERNAME.matches(normalized.username)) {
                put("username", "3-30 characters: lowercase letters, digits, '.' or '_'")
            }
            if (normalized.email.length > 254 || !EMAIL.matches(normalized.email)) put("email", "Invalid email address")
            if (request.password.length !in PASSWORD_MIN..PASSWORD_MAX) {
                put("password", "Must be $PASSWORD_MIN-$PASSWORD_MAX characters")
            }
            if ((normalized.displayName?.length ?: 0) > DISPLAY_NAME_MAX) {
                put("displayName", "At most $DISPLAY_NAME_MAX characters")
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return normalized
    }
}
