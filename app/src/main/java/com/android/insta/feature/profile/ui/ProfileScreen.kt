package com.android.insta.feature.profile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarLarge
import com.android.insta.core.ui.asString
import com.android.insta.feature.profile.data.Profile
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ProfileScreen(
    username: String,
    onPostClick: (String) -> Unit,
    onEditProfile: () -> Unit,
    onFollowsClick: (followers: Boolean) -> Unit = {},
    onCreatePost: () -> Unit,
    onBack: (() -> Unit)? = null,
    onMessageClick: (String) -> Unit = {},
    viewModel: ProfileViewModel = koinViewModel(key = "profile-$username") { parametersOf(username) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileContent(state, viewModel::onEvent, onPostClick, onEditProfile, onCreatePost, onBack, onFollowsClick, onMessageClick)
}

@Composable
fun ProfileContent(
    state: ProfileUiState,
    onEvent: (ProfileEvent) -> Unit,
    onPostClick: (String) -> Unit,
    onEditProfile: () -> Unit,
    onCreatePost: () -> Unit,
    onBack: (() -> Unit)? = null,
    onFollowsClick: (followers: Boolean) -> Unit = {},
    onMessageClick: (String) -> Unit = {},
) {
    val profile = state.profile
    if (profile == null) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                    TextButton(onClick = { onEvent(ProfileEvent.Refresh) }) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
        return
    }

    val gridState = rememberLazyGridState()
    // Load the next page when the last few cells come into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.canLoadMore) {
        if (nearEnd && state.canLoadMore) onEvent(ProfileEvent.LoadMore)
    }

    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = { onEvent(ProfileEvent.Refresh) }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            // Insets go on contentPadding so the grid can still scroll behind the system bars.
            contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                ProfileHeader(profile, state, onEvent, onEditProfile, onBack, onFollowsClick, onMessageClick)
            }
            if (state.posts.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.profile_no_posts), style = MaterialTheme.typography.titleMedium)
                        if (profile.isMe) Button(onClick = onCreatePost) { Text(stringResource(R.string.action_share_first_photo)) }
                    }
                }
            }
            items(state.posts, key = { it.id }) { post ->
                AsyncImage(
                    model = post.thumbUrl,
                    contentDescription = post.caption.ifBlank { stringResource(R.string.cd_post_photo, post.authorUsername) },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(1f).clickable { onPostClick(post.id) },
                )
            }
            if (state.isLoadingMore || state.loadMoreError != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            TextButton(onClick = { onEvent(ProfileEvent.LoadMore) }) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    profile: Profile,
    state: ProfileUiState,
    onEvent: (ProfileEvent) -> Unit,
    onEditProfile: () -> Unit,
    onBack: (() -> Unit)?,
    onFollowsClick: (followers: Boolean) -> Unit,
    onMessageClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
            Text(profile.username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(profile.avatarUrl, AvatarLarge)
            Stat(profile.postCount, stringResource(R.string.stat_posts), Modifier.weight(1f))
            Stat(profile.followerCount, stringResource(R.string.stat_followers), Modifier.weight(1f).clickable { onFollowsClick(true) })
            Stat(profile.followingCount, stringResource(R.string.stat_following), Modifier.weight(1f).clickable { onFollowsClick(false) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (profile.displayName.isNotBlank()) Text(profile.displayName, style = MaterialTheme.typography.titleSmall)
            if (profile.bio.isNotBlank()) Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
        }
        if (!profile.isMe) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.isFollowing) {
                    OutlinedButton(onClick = { onEvent(ProfileEvent.ToggleFollow) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_following)) }
                } else {
                    Button(onClick = { onEvent(ProfileEvent.ToggleFollow) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_follow)) }
                }
                OutlinedButton(onClick = { onMessageClick(profile.username) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_message)) }
            }
        }
        if (profile.isMe) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditProfile, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_edit_profile)) }
                OutlinedButton(onClick = { onEvent(ProfileEvent.Logout) }, enabled = !state.isLoggingOut, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_log_out))
                }
            }
        }
        state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Stat(value: Long, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
