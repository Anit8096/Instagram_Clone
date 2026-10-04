package com.android.insta.feature.engagement.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarSmall
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.asString
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.engagement.data.Comment
import com.android.insta.feature.engagement.data.EngagementApi
import com.android.insta.feature.engagement.data.PendingComment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

data class CommentsUiState(
    val comments: List<Comment> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = true,
    val error: UiMessage? = null,
    val input: String = "",
)

class CommentsViewModel(
    private val postId: String,
    private val api: EngagementApi,
    private val queue: ActionQueue,
    sessionManager: SessionManager,
) : ViewModel() {
    val myUserId: String? = (sessionManager.state.value as? SessionState.LoggedIn)?.user?.id
    private val _state = MutableStateFlow(CommentsUiState())
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    /** Comments still in the offline queue ("Sending…" / "Couldn't send"). */
    val pending: StateFlow<List<PendingComment>> =
        queue.pendingComments(postId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load(cursor = null)
        // A queued comment reached the server: reload so it shows as a normal comment.
        viewModelScope.launch { queue.commentsSynced.filter { it == postId }.collect { load(cursor = null) } }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value.take(BODY_MAX)) }

    fun send() {
        val body = _state.value.input.trim()
        if (body.isEmpty()) return
        _state.update { it.copy(input = "") }
        viewModelScope.launch { queue.addComment(postId, body) }
    }

    fun loadMore() = _state.value.nextCursor?.let { load(it) }

    fun retryLoad() = load(cursor = null)

    fun retry(id: String) = viewModelScope.launch { queue.retry(id) }

    fun discard(id: String) = viewModelScope.launch { queue.discard(id) }

    fun delete(comment: Comment) {
        viewModelScope.launch {
            when (val result = api.deleteComment(postId, comment.id)) {
                is ApiResult.Success -> _state.update { it.copy(comments = it.comments - comment) }
                is ApiResult.Failure -> _state.update { it.copy(error = result.error.toUiMessage()) }
            }
        }
    }

    private fun load(cursor: String?) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = api.comments(postId, cursor)) {
                is ApiResult.Success -> _state.update { s ->
                    val page = with(queue) { result.value.items.map { it.toComment() } }
                    val merged = if (cursor == null) page else (s.comments + page).distinctBy(Comment::id)
                    s.copy(comments = merged, nextCursor = result.value.nextCursor, isLoading = false)
                }
                is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error.toUiMessage()) }
            }
        }
    }

    companion object {
        const val BODY_MAX = 1000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    postId: String,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: CommentsViewModel = koinViewModel(key = "comments-$postId") { parametersOf(postId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    Scaffold(
        // safeDrawing includes the IME: the input bar rides above the keyboard (edge-to-edge guidance).
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.comments_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.comments.isEmpty() && pending.isEmpty() && !state.isLoading && state.error == null) {
                    item { Text(stringResource(R.string.comments_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(state.comments, key = { it.id }) { comment ->
                    ListItem(
                        leadingContent = { Avatar(comment.authorAvatarUrl, AvatarSmall) },
                        headlineContent = { Text(comment.authorUsername, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp)) },
                        supportingContent = { Text(comment.body) },
                        trailingContent = if (comment.authorId == viewModel.myUserId) ({
                            IconButton(onClick = { viewModel.delete(comment) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete_comment)) }
                        }) else null,
                    )
                }
                if (state.nextCursor != null) {
                    item { TextButton(onClick = viewModel::loadMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_load_more)) } }
                }
                items(pending, key = { "pending-${it.id}" }) { comment ->
                    ListItem(
                        headlineContent = { Text(comment.body) },
                        supportingContent = {
                            Text(
                                if (comment.failed) stringResource(R.string.comment_failed) else stringResource(R.string.comment_sending),
                                color = if (comment.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = if (comment.failed) ({
                            Row {
                                TextButton(onClick = { viewModel.discard(comment.id) }) { Text(stringResource(R.string.action_discard)) }
                                TextButton(onClick = { viewModel.retry(comment.id) }) { Text(stringResource(R.string.action_retry)) }
                            }
                        }) else null,
                    )
                }
                if (state.isLoading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.error?.let { error ->
                    item {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error.asString(), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryLoad) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    placeholder = { Text(stringResource(R.string.comment_hint)) },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::send, enabled = state.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_post_comment))
                }
            }
        }
    }
}
