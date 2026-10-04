package com.android.insta.feature.feed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.android.insta.R
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.asString
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.feed.data.FeedRepository
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.PostRepository
import com.android.insta.feature.post.ui.PostCard
import com.android.insta.feature.social.data.AppErrorException
import com.android.insta.feature.social.data.SocialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.merge
import org.koin.androidx.compose.koinViewModel

class FeedViewModel(feed: FeedRepository, posts: PostRepository, social: SocialRepository, private val actions: ActionQueue) : ViewModel() {
    val posts: Flow<PagingData<Post>> = feed.feed().cachedIn(viewModelScope)

    /**
     * Set when this device publishes/deletes a post or changes a follow. Collected here (the ViewModel
     * outlives the composable while another tab is shown) so the signal isn't lost; the screen refreshes
     * when it's next visible and acknowledges with [onRefreshHandled].
     */
    private val _needsRefresh = MutableStateFlow(false)
    val needsRefresh: StateFlow<Boolean> = _needsRefresh.asStateFlow()

    init {
        viewModelScope.launch { merge(posts.postsChanged, social.followChanged).collect { _needsRefresh.value = true } }
    }

    fun onRefreshHandled() {
        _needsRefresh.value = false
    }

    /** Optimistic: the cached row flips now; the queue delivers it when the network allows. */
    fun toggleLike(post: Post) {
        viewModelScope.launch { actions.setLiked(post.id, !post.likedByMe) }
    }
}

fun Throwable.toUiMessage(): UiMessage = (this as? AppErrorException)?.error?.toUiMessage() ?: UiMessage.Resource(R.string.error_generic)

@Composable
fun FeedScreen(
    onAuthorClick: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
    onFindPeople: () -> Unit,
    onCommentsClick: (String) -> Unit,
    onMessagesClick: () -> Unit = {},
    viewModel: FeedViewModel = koinViewModel(),
) {
    val items = viewModel.posts.collectAsLazyPagingItems()
    val needsRefresh by viewModel.needsRefresh.collectAsStateWithLifecycle()
    LaunchedEffect(needsRefresh) {
        if (needsRefresh) {
            items.refresh()
            viewModel.onRefreshHandled()
        }
    }
    Column(Modifier.fillMaxSize()) {
        FeedTopBar(onMessagesClick)
        FeedContent(items, onAuthorClick, onPhotoClick, onFindPeople, viewModel::toggleLike, onCommentsClick)
    }
}

@Composable
fun FeedContent(
    items: LazyPagingItems<Post>,
    onAuthorClick: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
    onFindPeople: () -> Unit,
    onLikeClick: (Post) -> Unit,
    onCommentsClick: (String) -> Unit,
) {
    val refresh = items.loadState.refresh
    // The mediator (network) can fail while the cached pages still show: that's the offline case.
    val networkError = (items.loadState.mediator?.refresh as? LoadState.Error)?.error
    PullToRefreshBox(
        isRefreshing = refresh is LoadState.Loading && items.itemCount > 0,
        onRefresh = { items.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            items.itemCount == 0 && refresh is LoadState.Loading ->
                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.itemCount == 0 && networkError != null ->
                CenteredMessage(networkError.toUiMessage().asString(), stringResource(R.string.action_retry)) { items.retry() }
            items.itemCount == 0 && refresh is LoadState.NotLoading ->
                CenteredMessage(stringResource(R.string.feed_empty), stringResource(R.string.action_find_people), onFindPeople)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom).asPaddingValues(),
            ) {
                if (networkError != null) {
                    item(key = "offline") { OfflineBanner(onRetry = { items.retry() }) }
                }
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { post ->
                        PostCard(post, onAuthorClick, onPhotoClick, onLikeClick, onCommentsClick)
                        HorizontalDivider()
                    }
                }
                when (val append = items.loadState.append) {
                    is LoadState.Loading -> item(key = "append-loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    is LoadState.Error -> item(key = "append-error") {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { items.retry() }) { Text(append.error.toUiMessage().asString() + " · " + stringResource(R.string.action_retry)) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.feed_offline), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
internal fun CenteredMessage(message: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Button(onClick = onAction) { Text(action) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FeedTopBar(onMessagesClick: () -> Unit) {
    androidx.compose.material3.TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            androidx.compose.material3.IconButton(onClick = onMessagesClick) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.messages_title),
                )
            }
        },
    )
}
