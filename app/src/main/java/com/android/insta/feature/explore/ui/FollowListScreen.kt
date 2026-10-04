package com.android.insta.feature.explore.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.feature.social.data.SocialRepository
import com.android.insta.feature.social.data.UserSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class FollowListViewModel(
    username: String,
    followers: Boolean,
    private val social: SocialRepository,
    sessionManager: SessionManager,
) : ViewModel() {
    val me: String? = (sessionManager.state.value as? SessionState.LoggedIn)?.user?.username
    val users: Flow<PagingData<UserSummary>> =
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) { social.relationsSource(username, followers) }.flow.cachedIn(viewModelScope)

    /** Follow state changed in this screen, layered over the paged data (optimistic, reverted on failure). */
    private val _overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

    fun toggle(user: UserSummary) {
        val target = !(_overrides.value[user.username] ?: user.isFollowing)
        _overrides.update { it + (user.username to target) }
        viewModelScope.launch {
            if (social.setFollowing(user.username, target) is ApiResult.Failure) {
                _overrides.update { it + (user.username to !target) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListScreen(
    username: String,
    followers: Boolean,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: FollowListViewModel = koinViewModel(key = "follows-$username-$followers") { parametersOf(username, followers) },
) {
    val users = viewModel.users.collectAsLazyPagingItems()
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (followers) R.string.stat_followers else R.string.stat_following)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        when {
            users.itemCount == 0 && users.loadState.refresh is LoadState.Loading ->
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            users.itemCount == 0 && users.loadState.refresh is LoadState.NotLoading ->
                Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.follow_list_empty)) }
            else -> LazyColumn(contentPadding = innerPadding) {
                items(users.itemCount, key = users.itemKey { it.id }) { index ->
                    users[index]?.let { user ->
                        val following = overrides[user.username] ?: user.isFollowing
                        UserRow(user, onClick = { onUserClick(user.username) }, trailing = if (user.username == viewModel.me) null else ({
                            if (following) OutlinedButton(onClick = { viewModel.toggle(user) }) { Text(stringResource(R.string.action_following)) }
                            else Button(onClick = { viewModel.toggle(user) }) { Text(stringResource(R.string.action_follow)) }
                        }))
                    }
                }
            }
        }
    }
}
