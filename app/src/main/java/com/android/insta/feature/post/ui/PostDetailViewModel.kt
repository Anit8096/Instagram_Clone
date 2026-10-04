package com.android.insta.feature.post.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostDetailUiState(
    val post: Post? = null,
    val isMine: Boolean = false,
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val error: UiMessage? = null,
    val isDeleting: Boolean = false,
    /** Post is gone (deleted here); the screen pops itself. */
    val deleted: Boolean = false,
)

sealed interface PostDetailEvent {
    data object Retry : PostDetailEvent
    data object Delete : PostDetailEvent
    data object ToggleLike : PostDetailEvent
}

class PostDetailViewModel(
    private val postId: String,
    private val repository: PostRepository,
    private val sessionManager: SessionManager,
    private val actions: ActionQueue,
) : ViewModel() {

    private val _state = MutableStateFlow(PostDetailUiState())
    val state: StateFlow<PostDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: PostDetailEvent) {
        when (event) {
            PostDetailEvent.Retry -> load()
            PostDetailEvent.Delete -> delete()
            PostDetailEvent.ToggleLike -> toggleLike()
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.getPost(postId)) {
                is ApiResult.Success -> {
                    val me = (sessionManager.state.value as? SessionState.LoggedIn)?.user?.id
                    _state.update { it.copy(post = result.value, isMine = result.value.authorId == me, isLoading = false) }
                }
                is ApiResult.Failure -> {
                    val notFound = (result.error as? AppError.Api)?.status == 404
                    _state.update { it.copy(isLoading = false, notFound = notFound, error = if (notFound) null else result.error.toUiMessage()) }
                }
            }
        }
    }

    private fun toggleLike() {
        val post = _state.value.post ?: return
        val liked = !post.likedByMe
        _state.update { it.copy(post = post.copy(likedByMe = liked, likeCount = (post.likeCount + if (liked) 1 else -1).coerceAtLeast(0))) }
        viewModelScope.launch { actions.setLiked(post.id, liked) }
    }

    private fun delete() {
        if (_state.value.isDeleting || !_state.value.isMine) return
        _state.update { it.copy(isDeleting = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.deletePost(postId)) {
                is ApiResult.Success -> _state.update { it.copy(isDeleting = false, deleted = true) }
                is ApiResult.Failure -> _state.update { it.copy(isDeleting = false, error = result.error.toUiMessage()) }
            }
        }
    }
}
