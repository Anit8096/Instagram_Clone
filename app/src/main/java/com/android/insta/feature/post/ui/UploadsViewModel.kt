package com.android.insta.feature.post.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.feature.post.data.PendingUpload
import com.android.insta.feature.post.data.PostRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the "Posting…" banner in the main shell. */
class UploadsViewModel(private val repository: PostRepository) : ViewModel() {

    val uploads: StateFlow<List<PendingUpload>> =
        repository.pendingUploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(id: String) {
        viewModelScope.launch { repository.retry(id) }
    }

    fun discard(id: String) {
        viewModelScope.launch { repository.discard(id) }
    }
}
