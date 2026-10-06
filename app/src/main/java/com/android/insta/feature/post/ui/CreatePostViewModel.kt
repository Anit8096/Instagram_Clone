package com.android.insta.feature.post.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.post.data.CropAspect
import com.android.insta.feature.post.data.MAX_POST_ITEMS
import com.android.insta.feature.post.data.PostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreatePostUiState(
    /** In post order; the first photo is the cover. */
    val images: List<Uri> = emptyList(),
    /** Index in [images] the preview shows and the move/remove actions apply to. */
    val selected: Int = 0,
    val aspect: CropAspect = CropAspect.ORIGINAL,
    val caption: String = "",
    val captionError: UiMessage? = null,
    val error: UiMessage? = null,
    val isSharing: Boolean = false,
    /** Set once the draft is queued; the screen navigates away and acknowledges with [CreatePostEvent.SharedHandled]. */
    val shared: Boolean = false,
) {
    val canShare: Boolean get() = images.isNotEmpty() && !isSharing && caption.length <= CAPTION_MAX
    val canAddMore: Boolean get() = images.size < MAX_POST_ITEMS && !isSharing
}

const val CAPTION_MAX = 2200

sealed interface CreatePostEvent {
    /** Added after the photos already chosen; anything beyond [MAX_POST_ITEMS] is dropped with a message. */
    data class ImagesPicked(val uris: List<Uri>) : CreatePostEvent
    data class Select(val index: Int) : CreatePostEvent
    /** Moves the selected photo by [offset] places (-1 = earlier). */
    data class MoveSelected(val offset: Int) : CreatePostEvent
    data object RemoveSelected : CreatePostEvent
    data object ClearImages : CreatePostEvent
    data class AspectChanged(val aspect: CropAspect) : CreatePostEvent
    data class CaptionChanged(val value: String) : CreatePostEvent
    data object Share : CreatePostEvent
    data object SharedHandled : CreatePostEvent
}

class CreatePostViewModel(private val repository: PostRepository) : ViewModel() {

    private val _state = MutableStateFlow(CreatePostUiState())
    val state: StateFlow<CreatePostUiState> = _state.asStateFlow()

    fun onEvent(event: CreatePostEvent) {
        when (event) {
            is CreatePostEvent.ImagesPicked -> _state.update { current ->
                val fresh = event.uris.filterNot { it in current.images }
                if (fresh.isEmpty()) return@update current
                val combined = current.images + fresh
                current.copy(
                    images = combined.take(MAX_POST_ITEMS),
                    // Jump to the first newly added photo.
                    selected = current.images.size.coerceAtMost(MAX_POST_ITEMS - 1),
                    error = if (combined.size > MAX_POST_ITEMS) UiMessage.Plural(R.plurals.error_max_photos, MAX_POST_ITEMS) else null,
                )
            }
            is CreatePostEvent.Select -> _state.update { it.copy(selected = event.index.coerceIn(0, (it.images.size - 1).coerceAtLeast(0))) }
            is CreatePostEvent.MoveSelected -> _state.update { current ->
                val from = current.selected
                val to = (from + event.offset).coerceIn(0, current.images.lastIndex)
                if (from == to || current.images.isEmpty()) return@update current
                val reordered = current.images.toMutableList().apply { add(to, removeAt(from)) }
                current.copy(images = reordered, selected = to)
            }
            CreatePostEvent.RemoveSelected -> _state.update { current ->
                if (current.images.isEmpty()) return@update current
                val remaining = current.images.toMutableList().apply { removeAt(current.selected) }
                current.copy(images = remaining, selected = current.selected.coerceAtMost((remaining.size - 1).coerceAtLeast(0)), error = null)
            }
            CreatePostEvent.ClearImages -> _state.update { it.copy(images = emptyList(), selected = 0, error = null) }
            is CreatePostEvent.AspectChanged -> _state.update { it.copy(aspect = event.aspect) }
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
        if (!current.canShare) return
        _state.update { it.copy(isSharing = true, error = null) }
        viewModelScope.launch {
            repository.createPost(current.images, current.caption, current.aspect).fold(
                onSuccess = { _state.update { it.copy(isSharing = false, shared = true) } },
                onFailure = { _state.update { it.copy(isSharing = false, error = UiMessage.Resource(R.string.error_image_unreadable)) } },
            )
        }
    }
}
