package com.android.insta.feature.post.data

import kotlinx.serialization.Serializable

// Wire models mirroring the server's media/posts/profile DTOs.

@Serializable
data class MediaDto(val id: String, val url: String, val thumbUrl: String, val width: Int, val height: Int)

@Serializable
data class CreatePostRequest(val mediaId: String, val caption: String)

@Serializable
data class AuthorDto(val id: String, val username: String, val displayName: String, val avatarUrl: String? = null)

@Serializable
data class PostDto(
    val id: String,
    val author: AuthorDto,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val caption: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
    val createdAt: String,
)

@Serializable
data class PageDto<T>(val items: List<T>, val nextCursor: String? = null)
