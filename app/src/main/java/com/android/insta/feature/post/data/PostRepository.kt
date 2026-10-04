package com.android.insta.feature.post.data

import android.net.Uri
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
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** A post as the UI needs it: absolute image URLs, parsed timestamp. */
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
)

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
)

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

    /** Compresses the picked image, stores a draft and schedules the upload. */
    suspend fun createPost(imageUri: Uri, caption: String): Result<Unit>

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

    override suspend fun createPost(imageUri: Uri, caption: String): Result<Unit> = try {
        val file = compressor.compress(imageUri)
        val draft = PostDraftEntity(id = UUID.randomUUID().toString(), imagePath = file.path, caption = caption.trim(), createdAt = now())
        drafts.upsert(draft)
        scheduler.enqueue(draft.id)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Could not prepare image")
        Result.failure(e)
    }

    override suspend fun publishDraft(draftId: String): PublishOutcome {
        val draft = drafts.get(draftId) ?: return PublishOutcome.Published // discarded meanwhile
        val mediaId = draft.mediaId ?: run {
            val file = File(draft.imagePath)
            if (!file.exists()) return PublishOutcome.Failed("The photo is no longer available")
            when (val upload = api.uploadMedia(file.readBytes(), MediaKind.POST)) {
                is ApiResult.Success -> upload.value.id.also { drafts.upsert(draft.copy(mediaId = it)) }
                is ApiResult.Failure -> return upload.error.toOutcome()
            }
        }
        return when (val created = api.createPost(draft.id, mediaId, draft.caption)) {
            is ApiResult.Success -> {
                drafts.delete(draft.id)
                File(draft.imagePath).delete()
                _postsChanged.tryEmit(Unit)
                PublishOutcome.Published
            }
            is ApiResult.Failure -> {
                val error = created.error
                if (error is AppError.Api && error.code == "INVALID_MEDIA") {
                    // Uploaded image is gone server-side: upload it again on the next attempt.
                    drafts.upsert(draft.copy(mediaId = null))
                    PublishOutcome.Retry
                } else {
                    error.toOutcome()
                }
            }
        }
    }

    override suspend fun markFailed(draftId: String, message: String) {
        drafts.get(draftId)?.let { drafts.upsert(it.copy(state = DraftState.FAILED, error = message)) }
    }

    override suspend fun retry(draftId: String) {
        val draft = drafts.get(draftId) ?: return
        drafts.upsert(draft.copy(state = DraftState.PENDING, error = null))
        scheduler.enqueue(draftId)
    }

    override suspend fun discard(draftId: String) {
        drafts.get(draftId)?.let { File(it.imagePath).delete() }
        drafts.delete(draftId)
    }

    override suspend fun clearDrafts() {
        scheduler.cancelAll()
        drafts.all().forEach { File(it.imagePath).delete() }
        drafts.deleteAll()
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
