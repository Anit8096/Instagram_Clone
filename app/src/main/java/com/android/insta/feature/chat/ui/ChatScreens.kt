package com.android.insta.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarSmall
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.asString
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.chat.data.ChatApi
import com.android.insta.feature.chat.data.ConversationDto
import com.android.insta.feature.chat.data.MessageDto
import com.android.insta.feature.chat.data.RealtimeClient
import com.android.insta.feature.chat.data.RealtimeEvent
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.engagement.data.PendingComment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------- Inbox ----------

data class InboxUiState(val conversations: List<ConversationDto> = emptyList(), val isLoading: Boolean = true, val error: UiMessage? = null)

class InboxViewModel(private val api: ChatApi, realtime: RealtimeClient, queue: ActionQueue, val urls: UrlResolver) : ViewModel() {
    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    init {
        load()
        // Any incoming message, read receipt or delivered send changes previews/unread counts.
        viewModelScope.launch { merge(realtime.events, queue.messagesSynced).collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            when (val result = api.inbox()) {
                is ApiResult.Success -> _state.value = InboxUiState(result.value, isLoading = false)
                is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error.toUiMessage()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onBack: () -> Unit, onOpenThread: (String) -> Unit, viewModel: InboxViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.messages_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        )
    }) { innerPadding ->
        LazyColumn(contentPadding = innerPadding, modifier = Modifier.fillMaxSize()) {
            if (state.isLoading && state.conversations.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            state.error?.let { item { Text(it.asString(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (!state.isLoading && state.conversations.isEmpty() && state.error == null) {
                item { Text(stringResource(R.string.messages_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.conversations, key = { it.id }) { conversation ->
                ListItem(
                    leadingContent = { Avatar(viewModel.urls.resolve(conversation.peer.avatarUrl), AvatarSmall) },
                    headlineContent = { Text(conversation.peer.username, fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = conversation.lastMessage?.let { { Text(it.body, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = if (conversation.unreadCount > 0) ({ Badge { Text(conversation.unreadCount.toString()) } }) else null,
                    modifier = Modifier.clickable { onOpenThread(conversation.peer.username) },
                )
            }
        }
    }
}

// ---------- Thread ----------

data class ThreadUiState(
    val conversationId: String? = null,
    /** Newest first (the list is reverse-laid-out). */
    val messages: List<MessageDto> = emptyList(),
    val peerLastReadAt: String? = null,
    val isLoading: Boolean = true,
    val error: UiMessage? = null,
    val input: String = "",
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThreadViewModel(
    private val peerUsername: String,
    private val api: ChatApi,
    private val queue: ActionQueue,
    realtime: RealtimeClient,
    sessionManager: SessionManager,
) : ViewModel() {
    val myId: String? = (sessionManager.state.value as? SessionState.LoggedIn)?.user?.id
    private val _state = MutableStateFlow(ThreadUiState())
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()

    val pending: StateFlow<List<PendingComment>> = _state.map { it.conversationId }.distinctUntilChanged().filterNotNull()
        .flatMapLatest { queue.pendingMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch {
            realtime.events.collect { event ->
                val conversationId = _state.value.conversationId ?: return@collect
                when (event) {
                    is RealtimeEvent.MessageNew -> if (event.message.conversationId == conversationId) {
                        _state.update { s -> s.copy(messages = (listOf(event.message) + s.messages).distinctBy { it.id }) }
                        if (event.message.senderId != myId) api.markRead(conversationId)
                    }
                    is RealtimeEvent.MessageRead -> if (event.conversationId == conversationId && event.userId != myId) {
                        _state.update { it.copy(peerLastReadAt = event.readAt) }
                    }
                }
            }
        }
        // A queued send was delivered: reload so it appears as a normal message (in case the socket was down).
        viewModelScope.launch { queue.messagesSynced.collect { if (it == _state.value.conversationId) reload() } }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value.take(2000)) }

    fun send() {
        val conversationId = _state.value.conversationId ?: return
        val body = _state.value.input.trim()
        if (body.isEmpty()) return
        _state.update { it.copy(input = "") }
        viewModelScope.launch { queue.sendMessage(conversationId, body) }
    }

    fun retry(id: String) = viewModelScope.launch { queue.retry(id) }
    fun discard(id: String) = viewModelScope.launch { queue.discard(id) }

    private suspend fun open() {
        when (val result = api.open(peerUsername)) {
            is ApiResult.Success -> {
                _state.update { it.copy(conversationId = result.value.id, peerLastReadAt = result.value.peerLastReadAt) }
                reload()
                api.markRead(result.value.id)
            }
            is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error.toUiMessage()) }
        }
    }

    private suspend fun reload() {
        val id = _state.value.conversationId ?: return
        when (val result = api.history(id, cursor = null)) {
            is ApiResult.Success -> _state.update { it.copy(messages = result.value.items, isLoading = false, error = null) }
            is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error.toUiMessage()) }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    username: String,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = koinViewModel(key = "thread-$username") { parametersOf(username) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val lastMine = state.messages.firstOrNull { it.senderId == viewModel.myId }
    val seen = lastMine != null && state.peerLastReadAt != null && state.peerLastReadAt!! >= lastMine.createdAt
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(username) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pending.reversed(), key = { "p-${it.id}" }) { item ->
                    Bubble(item.body, mine = true, status = stringResource(if (item.failed) R.string.comment_failed else R.string.comment_sending))
                    if (item.failed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.discard(item.id) }) { Text(stringResource(R.string.action_discard)) }
                        TextButton(onClick = { viewModel.retry(item.id) }) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    val mine = message.senderId == viewModel.myId
                    Bubble(message.body, mine, status = if (mine && seen && message.id == lastMine?.id && pending.isEmpty()) stringResource(R.string.message_seen) else null)
                }
                if (state.isLoading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.error?.let { item { Text(it.asString(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::send, enabled = state.input.isNotBlank() && state.conversationId != null) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send_message))
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, status: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(
            text,
            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
