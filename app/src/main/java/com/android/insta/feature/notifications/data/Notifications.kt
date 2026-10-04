package com.android.insta.feature.notifications.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.map
import com.android.insta.core.network.safeApiCall
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.feature.chat.data.RealtimeClient
import com.android.insta.feature.chat.data.RealtimeEvent
import com.android.insta.feature.post.data.AuthorDto
import com.android.insta.feature.post.data.PageDto
import com.android.insta.feature.social.data.CursorPagingSource
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.time.Instant

@Serializable
data class NotificationDto(
    val id: String,
    val type: String,
    val actor: AuthorDto,
    val postId: String? = null,
    val postThumbUrl: String? = null,
    val commentId: String? = null,
    val commentBody: String? = null,
    val read: Boolean = false,
    val createdAt: String,
)

@Serializable
data class UnreadCountDto(val count: Long)

enum class ActivityType { LIKE, COMMENT, FOLLOW }

data class ActivityItem(
    val id: String,
    val type: ActivityType,
    val actorUsername: String,
    val actorAvatarUrl: String?,
    val postId: String?,
    val postThumbUrl: String?,
    val commentBody: String?,
    val read: Boolean,
    val createdAt: Instant,
)

class NotificationsApi(private val client: HttpClient) {
    suspend fun list(cursor: String?): ApiResult<PageDto<NotificationDto>> =
        safeApiCall { client.get("api/v1/notifications") { cursor?.let { parameter("cursor", it) } } }

    suspend fun unreadCount(): ApiResult<UnreadCountDto> = safeApiCall { client.get("api/v1/notifications/unread-count") }
    suspend fun markAllRead(): ApiResult<Unit> = safeApiCall { client.post("api/v1/notifications/read") }
    suspend fun registerDevice(token: String): ApiResult<Unit> = safeApiCall { client.put("api/v1/me/devices/${token.encodeURLPathPart()}") }
    suspend fun unregisterDevice(token: String): ApiResult<Unit> = safeApiCall { client.delete("api/v1/me/devices/${token.encodeURLPathPart()}") }
}

class NotificationsRepository(private val api: NotificationsApi, private val urls: UrlResolver) {
    fun activitySource() = CursorPagingSource { cursor ->
        api.list(cursor).map { page -> page.items.mapNotNull { it.toItem() } to page.nextCursor }
    }

    suspend fun markAllRead() = api.markAllRead()

    private fun NotificationDto.toItem(): ActivityItem? {
        val type = when (type) {
            "like" -> ActivityType.LIKE
            "comment" -> ActivityType.COMMENT
            "follow" -> ActivityType.FOLLOW
            else -> return null // newer server types are skipped, not crashed on
        }
        return ActivityItem(
            id, type, actor.username, urls.resolve(actor.avatarUrl), postId, urls.resolve(postThumbUrl), commentBody, read,
            runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.EPOCH),
        )
    }
}

/**
 * Unread activity count for the tab badge. Fetched when the socket (re)connects, then kept current by
 * `notification.new` / `badge` socket events; cleared when the Activity tab marks everything read.
 */
class ActivityBadge(private val api: NotificationsApi, private val realtime: RealtimeClient) {
    private val _unread = MutableStateFlow(0L)
    val unread: StateFlow<Long> = _unread.asStateFlow()

    fun bind(scope: CoroutineScope, sessionManager: SessionManager) {
        scope.launch {
            sessionManager.state.map { it is SessionState.LoggedIn }.distinctUntilChanged().collectLatest { loggedIn ->
                if (!loggedIn) {
                    _unread.value = 0
                    return@collectLatest
                }
                coroutineScope {
                    launch { realtime.connected.filter { it }.collect { refresh() } }
                    realtime.events.collect { event ->
                        when (event) {
                            is RealtimeEvent.NotificationNew -> _unread.value = event.unreadCount
                            is RealtimeEvent.Badge -> _unread.value = event.unreadNotifications
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    suspend fun refresh() {
        (api.unreadCount() as? ApiResult.Success)?.let { _unread.value = it.value.count }
    }

    fun clear() {
        _unread.value = 0
    }
}

/** The install's push token. Null when push isn't configured (no `google-services.json`). */
interface PushTokens {
    suspend fun current(): String?
    suspend fun delete()
}

/**
 * Keeps the server's device-token list in sync with the session: registers the token after sign-in and whenever FCM
 * rotates it. On sign-out the token is deleted on the device, so a shared device stops receiving the old account's
 * pushes; the server drops it the next time FCM reports it unregistered.
 */
class PushRegistrar(private val api: NotificationsApi, private val tokens: PushTokens) {
    @Volatile private var loggedIn = false

    fun bind(scope: CoroutineScope, sessionManager: SessionManager) {
        scope.launch {
            sessionManager.state.map { it is SessionState.LoggedIn }.distinctUntilChanged().collect { signedIn ->
                loggedIn = signedIn
                if (signedIn) runCatching { tokens.current() }.getOrNull()?.let { register(it) }
            }
        }
    }

    /** From `FirebaseMessagingService.onNewToken`. */
    suspend fun onNewToken(token: String) {
        if (loggedIn) register(token)
    }

    /**
     * Local only: this also runs when the session has already expired server-side, where an API call can't succeed.
     * Best effort.
     */
    suspend fun unregister() {
        loggedIn = false
        runCatching { tokens.delete() }.onFailure { Timber.w(it, "Push token cleanup failed") }
    }

    private suspend fun register(token: String) {
        if (api.registerDevice(token) is ApiResult.Failure) Timber.w("Device token registration failed")
    }
}
