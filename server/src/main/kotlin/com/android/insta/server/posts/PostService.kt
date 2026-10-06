package com.android.insta.server.posts

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.Cursor
import com.android.insta.server.common.Page
import com.android.insta.server.common.PageRequest
import com.android.insta.server.common.ValidationException
import com.android.insta.server.media.CroppedFile
import com.android.insta.server.media.MediaKind
import com.android.insta.server.media.MediaRepository
import com.android.insta.server.media.MediaService
import com.android.insta.server.media.MediaStatus
import com.android.insta.server.media.MediaType
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

/** [mediaIds] in display order (1–10). [mediaId] is the single-photo form older clients send. */
@Serializable
data class CreatePostRequest(val mediaId: String? = null, val caption: String = "", val mediaIds: List<String>? = null)

@Serializable
data class AuthorDto(val id: String, val username: String, val displayName: String, val avatarUrl: String?)

@Serializable
data class PostMediaDto(val id: String, val type: String, val url: String, val thumbUrl: String, val width: Int, val height: Int)

/** [imageUrl], [thumbUrl], [width] and [height] describe the cover (first item), so single-photo clients still work. */
@Serializable
data class PostDto(
    val id: String,
    val author: AuthorDto,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val media: List<PostMediaDto>,
    val caption: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean = false,
    val createdAt: String,
)

fun PostRecord.toDto(likedByMe: Boolean = false) = PostDto(
    id = id.toString(),
    author = AuthorDto(authorId.toString(), authorUsername, authorDisplayName, authorAvatarMediaId?.let { mediaUrl(it, "thumb") }),
    imageUrl = mediaUrl(cover.id, "full"),
    thumbUrl = mediaUrl(cover.id, "thumb"),
    width = cover.width,
    height = cover.height,
    media = media.map { PostMediaDto(it.id.toString(), it.type.value, mediaUrl(it.id, "full"), mediaUrl(it.id, "thumb"), it.width, it.height) },
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

    /**
     * Validates the items, center-crops items 2…n to the cover's aspect ratio (new files, outside the transaction),
     * then inserts the post and points the media rows at the cropped files in one transaction. Replaced files are
     * deleted after the commit; new ones are deleted if anything fails.
     */
    private suspend fun insert(authorId: Uuid, postId: Uuid, request: CreatePostRequest): CreateResult {
        val caption = request.caption.trim()
        if (caption.length > CAPTION_MAX) throw ValidationException(mapOf("caption" to "At most $CAPTION_MAX characters"))
        val mediaIds = mediaIdsOf(request)

        posts.find(postId)?.let { return existing(it, authorId) }
        val records = mediaRepository.findAll(mediaIds)
        mediaIds.forEach { id ->
            val media = records[id]
            if (media == null || media.ownerId != authorId || media.kind != MediaKind.POST) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_MEDIA", "Upload each image with kind=post first")
            }
            if (media.type != MediaType.PHOTO) throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_MEDIA", "Videos can't be posted yet")
            if (media.status != MediaStatus.READY) throw ApiException(HttpStatusCode.UnprocessableEntity, "MEDIA_NOT_READY", "This media is still processing")
        }
        if (suspendTransaction(db) { with(posts) { usedAmongIn(mediaIds) } }.isNotEmpty()) throw mediaInUse()

        val cover = records.getValue(mediaIds.first())
        val aspect = cover.width.toDouble() / cover.height
        val crops = mutableListOf<CroppedFile>()
        val result = try {
            mediaIds.drop(1).forEach { id -> mediaService.cropToAspect(records.getValue(id), aspect)?.let(crops::add) }
            suspendTransaction(db) {
                with(posts) {
                    findIn(postId)?.let { return@suspendTransaction existing(it, authorId) }
                    if (usedAmongIn(mediaIds).isNotEmpty()) throw mediaInUse()
                    crops.forEach { with(mediaRepository) { replaceFullIn(it.mediaId, it.newKey, it.width, it.height) } }
                    insertIn(postId, authorId, mediaIds, caption, OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)))
                    CreateResult(findIn(postId)!!, created = true)
                }
            }
        } catch (e: ExposedSQLException) {
            mediaService.deleteFiles(crops.map { it.newKey })
            // Lost a race with a concurrent identical request: answer like the retry it is.
            if (e.sqlState != "23505") throw e
            val raced = posts.find(postId)
            return if (raced != null && raced.authorId == authorId) CreateResult(raced, created = false) else throw mediaInUse()
        } catch (e: Throwable) {
            mediaService.deleteFiles(crops.map { it.newKey })
            throw e
        }
        mediaService.deleteFiles(crops.map { if (result.created) it.oldKey else it.newKey })
        return result
    }

    private fun existing(post: PostRecord, authorId: Uuid): CreateResult =
        if (post.authorId == authorId) CreateResult(post, created = false) else throw idConflict()

    private fun mediaIdsOf(request: CreatePostRequest): List<Uuid> {
        val raw = when {
            request.mediaIds != null && request.mediaId != null -> null
            request.mediaIds != null -> request.mediaIds
            request.mediaId != null -> listOf(request.mediaId)
            else -> null
        }
        if (raw == null || raw.size !in 1..MAX_ITEMS) {
            throw ValidationException(mapOf("mediaIds" to "Send 1 to $MAX_ITEMS media ids in mediaIds"))
        }
        val ids = raw.map { runCatching { Uuid.parse(it) }.getOrNull() ?: throw ValidationException(mapOf("mediaIds" to "Invalid media id")) }
        if (ids.toSet().size != ids.size) throw ValidationException(mapOf("mediaIds" to "Each media item can appear only once"))
        return ids
    }

    suspend fun get(id: Uuid): PostRecord = posts.find(id) ?: throw notFound()

    suspend fun getDto(viewerId: Uuid, id: Uuid): PostDto = get(id).let { it.toDto(id in likedAmong(viewerId, listOf(id))) }

    suspend fun delete(userId: Uuid, id: Uuid) {
        val fileKeys = suspendTransaction(db) {
            val post = with(posts) { findIn(id) } ?: throw notFound()
            if (post.authorId != userId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "You can only delete your own posts")
            with(posts) { deleteIn(id) }
            post.media.flatMap { with(mediaRepository) { deleteIn(it.id) } }
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
    private fun mediaInUse() = ApiException(HttpStatusCode.Conflict, "MEDIA_IN_USE", "An image is already attached to a post")

    companion object {
        const val CAPTION_MAX = 2200
        const val MAX_ITEMS = 10
    }
}
