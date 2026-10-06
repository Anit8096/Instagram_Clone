package com.android.insta.feature.post.data

import android.net.Uri
import com.android.insta.core.database.DraftItemEntity
import com.android.insta.core.database.DraftState
import com.android.insta.core.database.PostDraftDao
import com.android.insta.core.database.PostDraftEntity
import com.android.insta.core.media.ImageCompressor
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** One photo (or, later, video) of a post, with absolute URLs. */
@Serializable
data class PostMedia(val id: String, val url: String, val thumbUrl: String, val width: Int, val height: Int, val isVideo: Boolean = false)

/**
 * A post as the UI needs it: absolute image URLs, parsed timestamp. [imageUrl]…[height] describe the cover;
 * [items] is the full carousel (just the cover for single-photo posts).
 */
data class Post(
    val id: String,
    val authorId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val caption: String,
    val likeCount: Int,
    val commentCount: Int,
    val createdAt: Instant,
    val likedByMe: Boolean = false,
    val media: List<PostMedia> = emptyList(),
) {
    val items: List<PostMedia> get() = media.ifEmpty { listOf(PostMedia(id, imageUrl, thumbUrl, width, height)) }
    val isCarousel: Boolean get() = items.size > 1
}

fun PostDto.toPost(urls: UrlResolver) = Post(
    id = id,
    authorId = author.id,
    authorUsername = author.username,
    authorDisplayName = author.displayName,
    authorAvatarUrl = urls.resolve(author.avatarUrl),
    imageUrl = urls.resolve(imageUrl)!!,
    thumbUrl = urls.resolve(thumbUrl)!!,
    width = width,
    height = height,
    caption = caption,
    likeCount = likeCount,
    commentCount = commentCount,
    createdAt = runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.EPOCH),
    likedByMe = likedByMe,
    media = media.map { PostMedia(it.id, urls.resolve(it.url)!!, urls.resolve(it.thumbUrl)!!, it.width, it.height, it.type == "video") },
)

/** Shape every photo of a new post is cropped to. [ORIGINAL] keeps the cover as is (the server crops the rest to it). */
enum class CropAspect(val ratio: Float?) {
    ORIGINAL(null), SQUARE(1f), PORTRAIT(0.8f), LANDSCAPE(1.91f),
}

const val MAX_POST_ITEMS = 10

data class PendingUpload(val id: String, val caption: String, val failed: Boolean, val error: String?)

sealed interface PublishOutcome {
    data object Published : PublishOutcome
    data object Retry : PublishOutcome
    data class Failed(val message: String) : PublishOutcome
}

/** Runs [PublishOutcome]-producing work in the background (WorkManager in the app, a fake in tests). */
interface UploadScheduler {
    fun enqueue(draftId: String)
    fun cancelAll()
}

interface PostRepository {
    /** Drafts not yet published: shown as the "Posting…" / "Couldn't post" banner. */
    val pendingUploads: Flow<List<PendingUpload>>

    /** Emits after this device publishes or deletes a post, so lists can refresh. */
    val postsChanged: SharedFlow<Unit>

    /** Compresses the picked photos (1–10, cropped to [aspect]), stores a draft and schedules the upload. */
    suspend fun createPost(imageUris: List<Uri>, caption: String, aspect: CropAspect = CropAspect.ORIGINAL): Result<Unit>

    /** One publish attempt for a draft; called by the background worker. */
    suspend fun publishDraft(draftId: String): PublishOutcome
    suspend fun markFailed(draftId: String, message: String)
    suspend fun retry(draftId: String)
    suspend fun discard(draftId: String)

    /** Removes all drafts and cancels uploads (on logout, so they never post under another account). */
    suspend fun clearDrafts()

    suspend fun getPost(id: String): ApiResult<Post>
    suspend fun deletePost(id: String): ApiResult<Unit>
}

class DefaultPostRepository(
    private val api: PostApi,
    private val drafts: PostDraftDao,
    private val compressor: ImageCompressor,
    private val scheduler: UploadScheduler,
    private val urls: UrlResolver,
    private val now: () -> Long = System::currentTimeMillis,
) : PostRepository {

    private val _postsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val postsChanged: SharedFlow<Unit> = _postsChanged.asSharedFlow()

    override val pendingUploads: Flow<List<PendingUpload>> = drafts.observeAll().map { list ->
        list.map { PendingUpload(it.id, it.caption, it.state == DraftState.FAILED, it.error) }
    }

    override suspend fun createPost(imageUris: List<Uri>, caption: String, aspect: CropAspect): Result<Unit> {
        require(imageUris.size in 1..MAX_POST_ITEMS) { "A post has 1 to $MAX_POST_ITEMS photos" }
        val files = mutableListOf<File>()
        return try {
            imageUris.forEach { files += compressor.compress(it, aspect.ratio) }
            val draftId = UUID.randomUUID().toString()
            // Items first: the draft row is what the upload banner and the worker look for.
            drafts.upsertItems(files.mapIndexed { position, file -> DraftItemEntity(draftId, position, file.path) })
            drafts.upsert(PostDraftEntity(id = draftId, caption = caption.trim(), createdAt = now()))
            scheduler.enqueue(draftId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            files.forEach(File::delete)
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not prepare images")
            files.forEach(File::delete)
            Result.failure(e)
        }
    }

    /** Uploads the photos that have no media id yet (in order, saving each id), then creates the post. */
    override suspend fun publishDraft(draftId: String): PublishOutcome {
        val draft = drafts.get(draftId) ?: return PublishOutcome.Published // discarded meanwhile
        val items = drafts.items(draftId)
        if (items.isEmpty()) return PublishOutcome.Failed("The photos are no longer available")
        val mediaIds = items.map { item ->
            item.mediaId ?: run {
                val file = File(item.localPath)
                if (!file.exists()) return PublishOutcome.Failed("The photos are no longer available")
                when (val upload = api.uploadMedia(file.readBytes(), MediaKind.POST)) {
                    is ApiResult.Success -> upload.value.id.also { drafts.setItemMedia(draftId, item.position, it) }
                    is ApiResult.Failure -> return upload.error.toOutcome()
                }
            }
        }
        return when (val created = api.createPost(draft.id, mediaIds, draft.caption)) {
            is ApiResult.Success -> {
                deleteDraft(draftId, items)
                _postsChanged.tryEmit(Unit)
                PublishOutcome.Published
            }
            is ApiResult.Failure -> {
                val error = created.error
                if (error is AppError.Api && error.code == "INVALID_MEDIA") {
                    // An uploaded photo is gone server-side: upload them all again on the next attempt.
                    drafts.clearItemMedia(draftId)
                    PublishOutcome.Retry
                } else {
                    error.toOutcome()
                }
            }
        }
    }

    private suspend fun deleteDraft(draftId: String, items: List<DraftItemEntity>) {
        drafts.delete(draftId)
        drafts.deleteItems(draftId)
        items.forEach { File(it.localPath).delete() }
    }

    override suspend fun markFailed(draftId: String, message: String) {
        drafts.get(draftId)?.let { drafts.upsert(it.copy(state = DraftState.FAILED, error = message)) }
    }

    override suspend fun retry(draftId: String) {
        val draft = drafts.get(draftId) ?: return
        drafts.upsert(draft.copy(state = DraftState.PENDING, error = null))
        scheduler.enqueue(draftId)
    }

    override suspend fun discard(draftId: String) = deleteDraft(draftId, drafts.items(draftId))

    override suspend fun clearDrafts() {
        scheduler.cancelAll()
        drafts.allItems().forEach { File(it.localPath).delete() }
        drafts.deleteAll()
        drafts.deleteAllItems()
    }

    override suspend fun getPost(id: String): ApiResult<Post> = api.getPost(id).map { it.toPost(urls) }

    override suspend fun deletePost(id: String): ApiResult<Unit> =
        api.deletePost(id).also { if (it is ApiResult.Success) _postsChanged.tryEmit(Unit) }

    /** Transient problems retry with backoff; anything the server rejected won't succeed on retry. */
    private fun AppError.toOutcome(): PublishOutcome = when (this) {
        AppError.Network -> PublishOutcome.Retry
        is AppError.Api -> if (status >= 500 || status == 408 || status == 429) PublishOutcome.Retry else PublishOutcome.Failed(message)
        is AppError.Unexpected -> PublishOutcome.Failed("Something went wrong")
    }
}
