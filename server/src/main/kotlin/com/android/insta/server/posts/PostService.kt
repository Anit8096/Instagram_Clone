package com.android.insta.server.posts

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.Cursor
import com.android.insta.server.common.Page
import com.android.insta.server.common.PageRequest
import com.android.insta.server.common.ValidationException
import com.android.insta.server.media.MediaKind
import com.android.insta.server.media.MediaRepository
import com.android.insta.server.media.MediaService
import com.android.insta.server.media.mediaUrl
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import com.android.insta.server.db.Likes
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import com.android.insta.server.redis.Cache
import com.android.insta.server.users.invalidateCounts
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

@Serializable
data class CreatePostRequest(val mediaId: String, val caption: String = "")

@Serializable
data class AuthorDto(val id: String, val username: String, val displayName: String, val avatarUrl: String?)

@Serializable
data class PostDto(
    val id: String,
    val author: AuthorDto,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val caption: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean = false,
    val createdAt: String,
)

fun PostRecord.toDto(likedByMe: Boolean = false) = PostDto(
    id = id.toString(),
    author = AuthorDto(authorId.toString(), authorUsername, authorDisplayName, authorAvatarMediaId?.let { mediaUrl(it, "thumb") }),
    imageUrl = mediaUrl(mediaId, "full"),
    thumbUrl = mediaUrl(mediaId, "thumb"),
    width = width,
    height = height,
    caption = caption,
    likeCount = likeCount,
    commentCount = commentCount,
    likedByMe = likedByMe,
    createdAt = createdAt.toInstant().toString(),
)

/** Result of an idempotent create: [created] is false when the same client id was already stored. */
data class CreateResult(val post: PostRecord, val created: Boolean)

class PostService(
    private val db: Database,
    private val posts: PostRepository,
    private val mediaRepository: MediaRepository,
    private val mediaService: MediaService,
    private val clock: Clock,
    private val cache: Cache,
) {
    /**
     * `PUT /posts/{id}` with a client-generated id: retries (e.g. WorkManager after a timeout) return
     * the existing post instead of creating a duplicate.
     */
    suspend fun create(authorId: Uuid, postId: Uuid, request: CreatePostRequest): CreateResult =
        insert(authorId, postId, request).also { if (it.created) cache.invalidateCounts(authorId) }

    private suspend fun insert(authorId: Uuid, postId: Uuid, request: CreatePostRequest): CreateResult {
        val caption = request.caption.trim()
        if (caption.length > CAPTION_MAX) throw ValidationException(mapOf("caption" to "At most $CAPTION_MAX characters"))
        val mediaId = runCatching { Uuid.parse(request.mediaId) }.getOrNull()
            ?: throw ValidationException(mapOf("mediaId" to "Invalid media id"))

        return try {
            suspendTransaction(db) {
                with(posts) {
                    findIn(postId)?.let { existing ->
                        if (existing.authorId != authorId) throw idConflict()
                        return@suspendTransaction CreateResult(existing, created = false)
                    }
                    val media = with(mediaRepository) { findIn(mediaId) }
                    if (media == null || media.ownerId != authorId || media.kind != MediaKind.POST) {
                        throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_MEDIA", "Upload the image with kind=post first")
                    }
                    if (isMediaUsed(mediaId)) throw mediaInUse()
                    insertIn(postId, authorId, mediaId, caption, OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)))
                    CreateResult(findIn(postId)!!, created = true)
                }
            }
        } catch (e: ExposedSQLException) {
            // Lost a race with a concurrent identical request: answer like the retry it is.
            if (e.sqlState != "23505") throw e
            val existing = posts.find(postId)
            if (existing != null && existing.authorId == authorId) CreateResult(existing, created = false) else throw mediaInUse()
        }
    }

    suspend fun get(id: Uuid): PostRecord = posts.find(id) ?: throw notFound()

    suspend fun getDto(viewerId: Uuid, id: Uuid): PostDto = get(id).let { it.toDto(id in likedAmong(viewerId, listOf(id))) }

    suspend fun delete(userId: Uuid, id: Uuid) {
        val fileKeys = suspendTransaction(db) {
            val post = with(posts) { findIn(id) } ?: throw notFound()
            if (post.authorId != userId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "You can only delete your own posts")
            with(posts) { deleteIn(id) }
            with(mediaRepository) { deleteIn(post.mediaId) }
        }
        mediaService.deleteFiles(fileKeys) // only after the rows are really gone
        cache.invalidateCounts(userId)
    }

    suspend fun byAuthor(viewerId: Uuid, authorId: Uuid, page: PageRequest): Page<PostDto> = toPage(viewerId, posts.byAuthor(authorId, page), page)

    suspend fun feed(viewerId: Uuid, page: PageRequest): Page<PostDto> = toPage(viewerId, posts.feed(viewerId, page), page)

    suspend fun explore(viewerId: Uuid, page: PageRequest): Page<PostDto> = toPage(viewerId, posts.explore(viewerId, page), page)

    private suspend fun toPage(viewerId: Uuid, rows: List<PostRecord>, page: PageRequest): Page<PostDto> {
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { Cursor(it.createdAt, it.id).encode() } else null
        val liked = likedAmong(viewerId, items.map { it.id })
        return Page(items.map { it.toDto(it.id in liked) }, next)
    }

    /** One query per page: which of these posts the viewer has liked. */
    private suspend fun likedAmong(viewerId: Uuid, postIds: List<Uuid>): Set<Uuid> =
        if (postIds.isEmpty()) emptySet() else suspendTransaction(db) {
            Likes.select(Likes.postId).where { (Likes.userId eq viewerId) and (Likes.postId inList postIds) }.map { it[Likes.postId] }.toSet()
        }

    private fun notFound() = ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Post not found")
    private fun idConflict() = ApiException(HttpStatusCode.Conflict, "POST_ID_CONFLICT", "Post id already used")
    private fun mediaInUse() = ApiException(HttpStatusCode.Conflict, "MEDIA_IN_USE", "This image is already attached to a post")

    companion object {
        const val CAPTION_MAX = 2200
    }
}
