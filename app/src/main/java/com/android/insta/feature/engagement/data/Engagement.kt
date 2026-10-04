package com.android.insta.feature.engagement.data

import android.content.Context
import androidx.room3.withWriteTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.android.insta.core.database.ActionState
import com.android.insta.core.database.ActionType
import com.android.insta.core.database.AppDatabase
import com.android.insta.core.database.PendingActionEntity
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.jsonBody
import com.android.insta.core.network.map
import com.android.insta.core.network.safeApiCall
import com.android.insta.feature.chat.data.ChatApi
import com.android.insta.feature.post.data.AuthorDto
import com.android.insta.feature.post.data.PageDto
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class LikeStateDto(val liked: Boolean, val likeCount: Int)

@Serializable
data class CreateCommentRequest(val body: String)

@Serializable
data class CommentDto(val id: String, val postId: String, val author: AuthorDto, val body: String, val createdAt: String)

data class Comment(val id: String, val authorId: String, val authorUsername: String, val authorAvatarUrl: String?, val body: String, val createdAt: Instant)

data class PendingComment(val id: String, val body: String, val failed: Boolean, val error: String?)

class EngagementApi(private val client: HttpClient) {
    suspend fun setLiked(postId: String, liked: Boolean): ApiResult<LikeStateDto> = safeApiCall {
        if (liked) client.put("api/v1/posts/$postId/like") else client.delete("api/v1/posts/$postId/like")
    }

    suspend fun addComment(postId: String, commentId: String, body: String): ApiResult<CommentDto> =
        safeApiCall { client.put("api/v1/posts/$postId/comments/$commentId") { jsonBody(CreateCommentRequest(body)) } }

    suspend fun comments(postId: String, cursor: String?): ApiResult<PageDto<CommentDto>> = safeApiCall {
        client.get("api/v1/posts/$postId/comments") { cursor?.let { parameter("cursor", it) } }
    }

    suspend fun deleteComment(postId: String, commentId: String): ApiResult<Unit> =
        safeApiCall { client.delete("api/v1/posts/$postId/comments/$commentId") }
}

/** Sends queued actions in the background (WorkManager in the app). */
fun interface SyncScheduler {
    fun schedule()
}

sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data object Retry : SyncOutcome
}

/**
 * Offline action queue. UI changes are applied immediately (optimistic) to the Room cache; the queue then delivers
 * the actions to the server in order. Every endpoint it calls is idempotent, so replays after a crash or timeout are safe.
 */
class ActionQueue(
    private val db: AppDatabase,
    private val api: EngagementApi,
    private val urls: UrlResolver,
    private val scheduler: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val chat: ChatApi? = null,
) {
    private val dao get() = db.pendingActionDao()
    private val feed get() = db.feedDao()

    private val _commentsSynced = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val _messagesSynced = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a conversation id after one of its queued messages reached the server. */
    val messagesSynced: SharedFlow<String> = _messagesSynced.asSharedFlow()

    /** Emits a post id after one of its queued comments reached the server. */
    val commentsSynced: SharedFlow<String> = _commentsSynced.asSharedFlow()

    /**
     * Records the user's latest choice for [postId]. Earlier unsent like/unlike actions for the post are dropped:
     * like→unlike→like collapses to a single PUT, and because the endpoints are idempotent only the final state matters.
     */
    suspend fun setLiked(postId: String, liked: Boolean) {
        db.withWriteTransaction {
            dao.deletePendingLikes(postId)
            dao.upsert(PendingActionEntity(UUID.randomUUID().toString(), if (liked) ActionType.LIKE else ActionType.UNLIKE, postId, createdAt = now()))
            feed.setLiked(postId, liked, if (liked) 1 else -1)
        }
        scheduler.schedule()
    }

    suspend fun addComment(postId: String, body: String) {
        db.withWriteTransaction {
            dao.upsert(PendingActionEntity(UUID.randomUUID().toString(), ActionType.COMMENT, postId, body.trim(), now()))
            feed.addComments(postId, 1)
        }
        scheduler.schedule()
    }

    /** DMs go through the same queue: shown at once as "Sending…", delivered in order, idempotent by message id. */
    suspend fun sendMessage(conversationId: String, body: String) {
        dao.upsert(PendingActionEntity(UUID.randomUUID().toString(), ActionType.MESSAGE, conversationId, body.trim(), now()))
        scheduler.schedule()
    }

    fun pendingMessages(conversationId: String): Flow<List<PendingComment>> = dao.observeByType(ActionType.MESSAGE, conversationId).map { list ->
        list.map { PendingComment(it.id, it.body.orEmpty(), it.state == ActionState.FAILED, it.error) }
    }

    fun pendingComments(postId: String): Flow<List<PendingComment>> = dao.observeComments(postId).map { list ->
        list.map { PendingComment(it.id, it.body.orEmpty(), it.state == ActionState.FAILED, it.error) }
    }

    suspend fun retry(actionId: String) {
        dao.get(actionId)?.let { dao.upsert(it.copy(state = ActionState.PENDING, error = null)) }
        scheduler.schedule()
    }

    suspend fun discard(actionId: String) {
        val action = dao.get(actionId) ?: return
        dao.delete(actionId)
        if (action.type == ActionType.COMMENT) feed.addComments(action.postId, -1)
    }

    /** Desired like state per post for actions not yet sent; re-applied over fresh server data so the UI never flickers back. */
    suspend fun pendingLikeOverrides(): Map<String, Boolean> = dao.pendingLikes().associate { it.postId to (it.type == ActionType.LIKE) }

    suspend fun clear() = dao.deleteAll()

    /** Drains the queue oldest-first. Stops at the first transient failure so ordering is preserved. */
    suspend fun sync(): SyncOutcome {
        while (true) {
            val action = dao.nextPending() ?: return SyncOutcome.Done
            val result = when (action.type) {
                ActionType.LIKE, ActionType.UNLIKE -> api.setLiked(action.postId, action.type == ActionType.LIKE).map { }
                ActionType.MESSAGE -> chat?.send(action.postId, action.id, action.body.orEmpty())?.map { } ?: ApiResult.Failure(AppError.Unexpected())
                else -> api.addComment(action.postId, action.id, action.body.orEmpty()).map { }
            }
            when (result) {
                is ApiResult.Success -> {
                    dao.delete(action.id)
                    if (action.type == ActionType.COMMENT) _commentsSynced.tryEmit(action.postId)
                    if (action.type == ActionType.MESSAGE) _messagesSynced.tryEmit(action.postId)
                }
                is ApiResult.Failure -> if (result.error.isTransient()) return SyncOutcome.Retry else reject(action, result.error)
            }
        }
    }

    /** The server refused (e.g. the post was deleted): undo the optimistic change or surface the failure. */
    private suspend fun reject(action: PendingActionEntity, error: AppError) {
        when (action.type) {
            ActionType.LIKE, ActionType.UNLIKE -> {
                dao.delete(action.id)
                val liked = action.type == ActionType.LIKE
                feed.setLiked(action.postId, !liked, if (liked) -1 else 1)
            }
            else -> dao.upsert(action.copy(state = ActionState.FAILED, error = (error as? AppError.Api)?.message ?: "Couldn't send"))
        }
    }

    // Comment conversion lives here so the comments screen and queue share URL resolution.
    fun CommentDto.toComment() = Comment(id, author.id, author.username, urls.resolve(author.avatarUrl), body,
        runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.EPOCH))

    private fun AppError.isTransient() = this == AppError.Network ||
        (this is AppError.Api && (status >= 500 || status == 408 || status == 429)) || this is AppError.Unexpected
}

class ActionSyncWorker(context: Context, params: WorkerParameters, private val queue: ActionQueue) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (queue.sync()) {
        SyncOutcome.Done -> Result.success()
        SyncOutcome.Retry -> Result.retry()
    }
}

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<ActionSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a run is chained after the current one, so actions queued mid-sync are never missed.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    private companion object {
        const val WORK_NAME = "action-sync"
    }
}
