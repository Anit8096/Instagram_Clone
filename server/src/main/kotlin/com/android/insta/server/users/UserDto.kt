package com.android.insta.server.users

import kotlinx.serialization.Serializable

/** Public view of a user: safe in profiles, search, comments and chat. Never contains contact details. */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val createdAt: String,
)

/** The signed-in user's own account: the public fields plus private ones. Only for `/me` and auth responses. */
@Serializable
data class MeDto(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val createdAt: String,
    val phone: String,
    val email: String? = null,
)

fun UserRecord.toDto() = UserDto(
    id = id.toString(),
    username = username,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarMediaId?.let { "/api/v1/media/$it/thumb" },
    createdAt = createdAt.toInstant().toString(),
)

fun UserRecord.toMeDto() = toDto().let { MeDto(it.id, it.username, it.displayName, it.bio, it.avatarUrl, it.createdAt, phone, email) }
