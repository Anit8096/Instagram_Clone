package com.android.insta.server.users

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val createdAt: String,
)

fun UserRecord.toDto() = UserDto(
    id = id.toString(),
    username = username,
    displayName = displayName,
    bio = bio,
    avatarUrl = avatarMediaId?.let { "/api/v1/media/$it/thumb" },
    createdAt = createdAt.toInstant().toString(),
)
