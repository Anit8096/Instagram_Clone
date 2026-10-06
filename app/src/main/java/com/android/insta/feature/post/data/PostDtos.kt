package com.android.insta.feature.post.data

import kotlinx.serialization.Serializable

// Wire models mirroring the server's media/posts/profile DTOs.

@Serializable
data class MediaDto(val id: String, val url: String, val thumbUrl: String, val width: Int, val height: Int)

/** [mediaIds] in carousel order (1–10); the first is the cover. */
@Serializable
data class CreatePostRequest(val mediaIds: List<String>, val caption: String)

@Serializable
data class PostMediaDto(val id: String, val type: String = "photo", val url: String, val thumbUrl: String, val width: Int, val height: Int)

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
    /** All items in order; servers before carousels don't send it (the cover fields are then the only photo). */
    val media: List<PostMediaDto> = emptyList(),
    val caption: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
    val createdAt: String,
)

@Serializable
data class PageDto<T>(val items: List<T>, val nextCursor: String? = null)
