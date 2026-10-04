package com.android.insta.feature.post.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.post.data.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreatePostUiState(
    val imageUri: Uri? = null,
    val caption: String = "",
    val captionError: UiMessage? = null,
    val error: UiMessage? = null,
    val isSharing: Boolean = false,
    /** Set once the draft is queued; the screen navigates away and acknowledges with [CreatePostEvent.SharedHandled]. */
    val shared: Boolean = false,
) {
    val canShare: Boolean get() = imageUri != null && !isSharing && caption.length <= CAPTION_MAX
}

const val CAPTION_MAX = 2200

sealed interface CreatePostEvent {
    data class ImagePicked(val uri: Uri) : CreatePostEvent
    data object ClearImage : CreatePostEvent
    data class CaptionChanged(val value: String) : CreatePostEvent
    data object Share : CreatePostEvent
    data object SharedHandled : CreatePostEvent
}

class CreatePostViewModel(private val repository: PostRepository) : ViewModel() {

    private val _state = MutableStateFlow(CreatePostUiState())
    val state: StateFlow<CreatePostUiState> = _state.asStateFlow()

    fun onEvent(event: CreatePostEvent) {
        when (event) {
            is CreatePostEvent.ImagePicked -> _state.update { it.copy(imageUri = event.uri, error = null) }
            CreatePostEvent.ClearImage -> _state.update { it.copy(imageUri = null) }
            is CreatePostEvent.CaptionChanged -> _state.update {
                it.copy(
                    caption = event.value,
                    captionError = if (event.value.length > CAPTION_MAX) UiMessage.Resource(R.string.error_caption_length) else null,
                )
            }
            CreatePostEvent.Share -> share()
            CreatePostEvent.SharedHandled -> _state.value = CreatePostUiState()
        }
    }

    private fun share() {
        val current = _state.value
        val uri = current.imageUri ?: return
        if (!current.canShare) return
        _state.update { it.copy(isSharing = true, error = null) }
        viewModelScope.launch {
            repository.createPost(uri, current.caption).fold(
                onSuccess = { _state.update { it.copy(isSharing = false, shared = true) } },
                onFailure = { _state.update { it.copy(isSharing = false, error = UiMessage.Resource(R.string.error_image_unreadable)) } },
            )
        }
    }
}
