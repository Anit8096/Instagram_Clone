package com.android.insta.feature.notifications.ui

import android.Manifest
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarSmall
import com.android.insta.feature.notifications.data.ActivityBadge
import com.android.insta.feature.notifications.data.ActivityItem
import com.android.insta.feature.notifications.data.ActivityType
import com.android.insta.feature.notifications.data.NotificationsRepository
import com.android.insta.feature.notifications.push.SystemNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class ActivityViewModel(
    private val repository: NotificationsRepository,
    private val badge: ActivityBadge,
    private val notifier: SystemNotifier,
) : ViewModel() {
    val items: Flow<PagingData<ActivityItem>> =
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) { repository.activitySource() }.flow.cachedIn(viewModelScope)

    val unread: StateFlow<Long> = badge.unread

    /** Clears the badge as soon as the tab is looked at; rows loaded before this keep their "new" highlight. */
    fun markSeen() {
        badge.clear()
        viewModelScope.launch { if (repository.markAllRead() is ApiResult.Failure) badge.refresh() }
    }

    fun shouldAskForNotifications(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifier.canPost()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onPostClick: (String) -> Unit,
    onCommentsClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: ActivityViewModel = koinViewModel(),
) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val unread by viewModel.unread.collectAsStateWithLifecycle()

    // Something new arrived (or the tab just opened with a badge): reload, then mark seen. Waiting for the reload
    // keeps the new rows highlighted, because they were fetched while still unread.
    LaunchedEffect(unread) {
        if (unread > 0) {
            items.refresh()
            snapshotFlow { items.loadState.refresh }.dropWhile { it !is LoadState.Loading }.first { it !is LoadState.Loading }
            viewModel.markSeen()
        }
    }

    // Asked in context (here, where notifications are the topic) rather than at app start; dismissible.
    var askForPermission by rememberSaveable { mutableStateOf(viewModel.shouldAskForNotifications()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askForPermission = false }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.activity_title)) }) }) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = items.loadState.refresh is LoadState.Loading && items.itemCount > 0,
            onRefresh = { items.refresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (askForPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    item(key = "permission") {
                        NotificationsPrompt(
                            onEnable = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            onDismiss = { askForPermission = false },
                        )
                    }
                }
                when {
                    items.itemCount == 0 && items.loadState.refresh is LoadState.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    items.itemCount == 0 && items.loadState.refresh is LoadState.Error -> item(key = "error") {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.error_network), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { items.retry() }) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                    items.itemCount == 0 -> item(key = "empty") {
                        Text(stringResource(R.string.placeholder_notifications), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { item ->
                        ActivityRow(item, onClick = {
                            when (item.type) {
                                ActivityType.LIKE -> item.postId?.let(onPostClick)
                                ActivityType.COMMENT -> item.postId?.let(onCommentsClick)
                                ActivityType.FOLLOW -> onUserClick(item.actorUsername)
                            }
                        }, onActorClick = { onUserClick(item.actorUsername) })
                    }
                }
                if (items.loadState.append is LoadState.Error) {
                    item(key = "append-error") {
                        TextButton(onClick = { items.retry() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, onClick: () -> Unit, onActorClick: () -> Unit) {
    val action = when (item.type) {
        ActivityType.LIKE -> stringResource(R.string.activity_liked)
        ActivityType.COMMENT -> stringResource(R.string.activity_commented, item.commentBody.orEmpty())
        ActivityType.FOLLOW -> stringResource(R.string.activity_followed)
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(item.actorUsername) }
        append(' ')
        append(action)
    }
    val time = remember(item.createdAt) {
        DateUtils.getRelativeTimeSpanString(item.createdAt.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    ListItem(
        leadingContent = { Box(Modifier.clickable(onClick = onActorClick)) { Avatar(item.actorAvatarUrl, AvatarSmall) } },
        headlineContent = { Text(text, maxLines = 3) },
        supportingContent = { Text(time) },
        trailingContent = item.postThumbUrl?.let { url ->
            {
                AsyncImage(
                    model = url,
                    contentDescription = null, // the row's text already says what the post is
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
                )
            }
        },
        colors = if (item.read) ListItemDefaults.colors() else ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun NotificationsPrompt(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.notifications_prompt_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notifications_prompt_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
                Button(onClick = onEnable) { Text(stringResource(R.string.action_turn_on)) }
            }
        }
    }
}
