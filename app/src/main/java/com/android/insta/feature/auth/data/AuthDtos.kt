package com.android.insta.feature.auth.data

import com.android.insta.core.session.SessionUser
import kotlinx.serialization.Serializable

// Wire models for /api/v1/auth/*, mirroring server/src/main/kotlin/.../auth/AuthDtos.kt.

@Serializable
data class RegisterRequest(val username: String, val email: String, val password: String, val displayName: String? = null)

@Serializable
data class LoginRequest(val login: String, val password: String)

@Serializable
data class GoogleLoginRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val createdAt: String,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
    val isNewUser: Boolean = false,
)

fun UserDto.toSessionUser() = SessionUser(id = id, username = username, displayName = displayName, avatarUrl = avatarUrl)
