package com.android.insta.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.core.network.ApiResult
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.PostRepository
import com.android.insta.feature.profile.data.Profile
import com.android.insta.feature.profile.data.ProfileRepository
import com.android.insta.feature.social.data.SocialRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: Profile? = null,
    val posts: List<Post> = emptyList(),
    val nextCursor: String? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: UiMessage? = null,
    val loadMoreError: UiMessage? = null,
) {
    val canLoadMore: Boolean get() = nextCursor != null && !isLoadingMore && loadMoreError == null
}

sealed interface ProfileEvent {
    data object Refresh : ProfileEvent
    data object LoadMore : ProfileEvent
    data object ToggleFollow : ProfileEvent
}

/** Profile header + post grid for any user ([username]). Sign-out and account deletion live in Settings. */
class ProfileViewModel(
    private val username: String,
    private val profiles: ProfileRepository,
    posts: PostRepository,
    private val social: SocialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh(initial = true)
        // New/deleted posts and profile edits made on this device. An explicit signal, because the
        // cached session user doesn't carry every field (a bio-only edit wouldn't change it).
        viewModelScope.launch { posts.postsChanged.collect { refresh() } }
        viewModelScope.launch { profiles.profileChanged.collect { refresh() } }
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.Refresh -> refresh()
            ProfileEvent.LoadMore -> loadMore()
            ProfileEvent.ToggleFollow -> toggleFollow()
        }
    }

    private fun refresh(initial: Boolean = false) {
        loadJob?.cancel()
        _state.update { it.copy(isLoading = initial && it.profile == null, isRefreshing = !initial, error = null, loadMoreError = null) }
        loadJob = viewModelScope.launch {
            val profile = profiles.profile(username)
            val page = profiles.posts(username, cursor = null)
            _state.update { current ->
                when {
                    profile is ApiResult.Failure -> current.copy(isLoading = false, isRefreshing = false, error = profile.error.toUiMessage())
                    page is ApiResult.Failure -> current.copy(
                        profile = (profile as ApiResult.Success).value,
                        isLoading = false,
                        isRefreshing = false,
                        error = page.error.toUiMessage(),
                    )
                    else -> current.copy(
                        profile = (profile as ApiResult.Success).value,
                        posts = (page as ApiResult.Success).value.items,
                        nextCursor = page.value.nextCursor,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    private fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        if (!_state.value.canLoadMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val page = profiles.posts(username, cursor)) {
                is ApiResult.Success -> _state.update {
                    // De-duplicate in case a refresh raced with this page.
                    val known = it.posts.map(Post::id).toSet()
                    it.copy(posts = it.posts + page.value.items.filterNot { p -> p.id in known }, nextCursor = page.value.nextCursor, isLoadingMore = false)
                }
                is ApiResult.Failure -> _state.update { it.copy(isLoadingMore = false, loadMoreError = page.error.toUiMessage()) }
            }
        }
    }

    /** Optimistic: flip the button and count now, revert if the server refuses. */
    private fun toggleFollow() {
        val profile = _state.value.profile ?: return
        if (profile.isMe) return
        val target = !profile.isFollowing
        val optimistic = profile.copy(isFollowing = target, followerCount = (profile.followerCount + if (target) 1 else -1).coerceAtLeast(0))
        _state.update { it.copy(profile = optimistic, error = null) }
        viewModelScope.launch {
            when (val result = social.setFollowing(profile.username, target)) {
                is ApiResult.Success -> _state.update { s -> s.copy(profile = s.profile?.copy(isFollowing = result.value.isFollowing, followerCount = result.value.followerCount)) }
                is ApiResult.Failure -> _state.update { it.copy(profile = profile, error = result.error.toUiMessage()) }
            }
        }
    }
}
