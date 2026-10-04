package com.android.insta.feature.chat.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.jsonBody
import com.android.insta.core.network.safeApiCall
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.feature.post.data.PageDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlinx.coroutines.Dispatchers

@Serializable
data class PeerDto(val id: String, val username: String, val displayName: String, val avatarUrl: String? = null)

@Serializable
data class MessageDto(val id: String, val conversationId: String, val senderId: String, val body: String, val createdAt: String)

@Serializable
data class ConversationDto(
    val id: String,
    val peer: PeerDto,
    val lastMessage: MessageDto? = null,
    val unreadCount: Int = 0,
    val peerLastReadAt: String? = null,
)

@Serializable
data class OpenConversationRequest(val username: String)

@Serializable
data class SendMessageRequest(val body: String)

/** Server push events (`type` discriminator), mirroring the server's RealtimeEvent. */
@Serializable
sealed interface RealtimeEvent {
    @Serializable @SerialName("message.new")
    data class MessageNew(val message: MessageDto) : RealtimeEvent

    @Serializable @SerialName("message.read")
    data class MessageRead(val conversationId: String, val userId: String, val readAt: String) : RealtimeEvent
}

class ChatApi(private val client: HttpClient) {
    suspend fun inbox(): ApiResult<List<ConversationDto>> = safeApiCall { client.get("api/v1/conversations") }
    suspend fun open(username: String): ApiResult<ConversationDto> =
        safeApiCall { client.post("api/v1/conversations") { jsonBody(OpenConversationRequest(username)) } }

    suspend fun history(conversationId: String, cursor: String?): ApiResult<PageDto<MessageDto>> = safeApiCall {
        client.get("api/v1/conversations/$conversationId/messages") { cursor?.let { parameter("cursor", it) } }
    }

    suspend fun send(conversationId: String, messageId: String, body: String): ApiResult<MessageDto> =
        safeApiCall { client.put("api/v1/conversations/$conversationId/messages/$messageId") { jsonBody(SendMessageRequest(body)) } }

    suspend fun markRead(conversationId: String): ApiResult<Unit> = safeApiCall { client.post("api/v1/conversations/$conversationId/read") }
}

/**
 * One WebSocket to `/api/v1/ws` while the user is signed in **and** the app is in the foreground (no socket in the
 * background; push notifications cover that later). Reconnects with exponential backoff; events fan out via [events].
 */
class RealtimeClient(
    private val client: HttpClient,
    private val json: Json,
    private val wsUrl: String,
) {
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Call once (e.g. from Application.onCreate); lives as long as [scope]. */
    fun bind(scope: CoroutineScope, sessionManager: SessionManager) {
        val foreground = MutableStateFlow(false)
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().repeatOnLifecycle(Lifecycle.State.STARTED) {
                foreground.value = true
                try { kotlinx.coroutines.awaitCancellation() } finally { foreground.value = false }
            }
        }
        scope.launch {
            combine(foreground, sessionManager.state) { fg, session -> fg && session is SessionState.LoggedIn }
                .distinctUntilChanged()
                .collectLatest { shouldConnect -> if (shouldConnect) connectLoop() }
        }
    }

    private suspend fun connectLoop() {
        var backoffMs = 1_000L
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    client.webSocket(wsUrl) {
                        _connected.value = true
                        backoffMs = 1_000L
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            runCatching { json.decodeFromString(RealtimeEvent.serializer(), frame.readText()) }
                                .onSuccess { _events.emit(it) }
                                .onFailure { Timber.w(it, "Unknown realtime event") }
                        }
                    }
                }
            } catch (e: CancellationException) {
                _connected.value = false
                throw e
            } catch (e: Exception) {
                Timber.d("Realtime socket closed: ${e.message}")
            }
            _connected.value = false
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }
}
