package com.android.insta.feature.explore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.asString
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.social.data.SocialRepository
import com.android.insta.feature.social.data.UserSummary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

data class SearchState(val loading: Boolean = false, val results: List<UserSummary> = emptyList(), val error: UiMessage? = null)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExploreViewModel(private val social: SocialRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Searches 300 ms after typing stops; a newer query cancels the older request. */
    val search: StateFlow<SearchState> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            if (q.isEmpty()) {
                flow { emit(SearchState()) }
            } else {
                flow {
                    emit(SearchState(loading = true))
                    emit(
                        when (val result = social.search(q)) {
                            is ApiResult.Success -> SearchState(results = result.value)
                            is ApiResult.Failure -> SearchState(error = result.error.toUiMessage())
                        },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    val explore: Flow<PagingData<Post>> =
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) { social.exploreSource() }.flow.cachedIn(viewModelScope)

    /** Following someone moves their posts out of Explore; same "stale until visible" pattern as the feed. */
    private val _needsRefresh = MutableStateFlow(false)
    val needsRefresh: StateFlow<Boolean> = _needsRefresh.asStateFlow()

    init {
        viewModelScope.launch { social.followChanged.collect { _needsRefresh.value = true } }
    }

    fun onRefreshHandled() {
        _needsRefresh.value = false
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

@Composable
fun ExploreScreen(onUserClick: (String) -> Unit, onPostClick: (String) -> Unit, viewModel: ExploreViewModel = koinViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val grid = viewModel.explore.collectAsLazyPagingItems()
    val needsRefresh by viewModel.needsRefresh.collectAsStateWithLifecycle()
    LaunchedEffect(needsRefresh) {
        if (needsRefresh) {
            grid.refresh()
            viewModel.onRefreshHandled()
        }
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_search)) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (query.isBlank()) {
            when {
                grid.itemCount == 0 && grid.loadState.refresh is LoadState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                grid.itemCount == 0 && grid.loadState.refresh is LoadState.NotLoading ->
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.explore_empty)) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(grid.itemCount, key = grid.itemKey { it.id }) { index ->
                        grid[index]?.let { post ->
                            AsyncImage(
                                model = post.thumbUrl,
                                contentDescription = post.caption.ifBlank { stringResource(R.string.cd_post_photo, post.authorUsername) },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.aspectRatio(1f).clickable { onPostClick(post.id) },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()) {
                if (search.loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                search.error?.let { error -> item { Text(error.asString(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                if (!search.loading && search.error == null && search.results.isEmpty()) {
                    item { Text(stringResource(R.string.search_no_results), modifier = Modifier.padding(16.dp)) }
                }
                items(search.results, key = { it.id }) { user -> UserRow(user, onClick = { onUserClick(user.username) }) }
            }
        }
    }
}

@Composable
internal fun UserRow(user: UserSummary, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(user.username) },
        supportingContent = user.displayName.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        leadingContent = { Avatar(user.avatarUrl, AvatarSmall) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
