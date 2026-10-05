package com.android.insta.feature.profile.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.session.SessionManager
import com.android.insta.core.session.SessionState
import com.android.insta.core.ui.UiMessage
import com.android.insta.core.ui.toUiMessage
import com.android.insta.feature.profile.data.ProfileRepository
import com.android.insta.feature.profile.data.UploadedAvatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditProfileUiState(
    val isLoading: Boolean = true,
    val displayName: String = "",
    val bio: String = "",
    val currentAvatarUrl: String? = null,
    val newAvatar: UploadedAvatar? = null,
    val removeAvatar: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val displayNameError: UiMessage? = null,
    val bioError: UiMessage? = null,
    val error: UiMessage? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    /** The account's phone, formatted; changed on its own screen (it needs a code). */
    val phone: String? = null,
) {
    val shownAvatarUrl: String? get() = newAvatar?.url ?: currentAvatarUrl.takeUnless { removeAvatar }
    val canSave: Boolean get() = !isLoading && !isSaving && !isUploadingAvatar && displayNameError == null && bioError == null
}

sealed interface EditProfileEvent {
    data class DisplayNameChanged(val value: String) : EditProfileEvent
    data class BioChanged(val value: String) : EditProfileEvent
    data class AvatarPicked(val uri: Uri) : EditProfileEvent
    data object RemoveAvatar : EditProfileEvent
    data object Save : EditProfileEvent
}

class EditProfileViewModel(
    private val profiles: ProfileRepository,
    sessionManager: SessionManager,
    formatPhone: (String) -> String = { it },
) : ViewModel() {

    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()

    init {
        // Follows the session so a number changed on the Change phone screen shows up on return.
        viewModelScope.launch {
            sessionManager.state.collect { session ->
                val phone = (session as? SessionState.LoggedIn)?.user?.phone?.let(formatPhone)
                _state.update { it.copy(phone = phone) }
            }
        }
        val username = (sessionManager.state.value as? SessionState.LoggedIn)?.user?.username
        viewModelScope.launch {
            when (val result = username?.let { profiles.profile(it) }) {
                is ApiResult.Success -> _state.update {
                    it.copy(isLoading = false, displayName = result.value.displayName, bio = result.value.bio, currentAvatarUrl = result.value.avatarUrl)
                }
                is ApiResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error.toUiMessage()) }
                null -> _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onEvent(event: EditProfileEvent) {
        when (event) {
            is EditProfileEvent.DisplayNameChanged -> _state.update {
                it.copy(displayName = event.value, displayNameError = lengthError(event.value, DISPLAY_NAME_MAX, R.string.error_display_name_length))
            }
            is EditProfileEvent.BioChanged -> _state.update {
                it.copy(bio = event.value, bioError = lengthError(event.value, BIO_MAX, R.string.error_bio_length))
            }
            is EditProfileEvent.AvatarPicked -> uploadAvatar(event.uri)
            EditProfileEvent.RemoveAvatar -> _state.update { it.copy(newAvatar = null, removeAvatar = true) }
            EditProfileEvent.Save -> save()
        }
    }

    private fun uploadAvatar(uri: Uri) {
        _state.update { it.copy(isUploadingAvatar = true, error = null) }
        viewModelScope.launch {
            when (val result = profiles.uploadAvatar(uri)) {
                is ApiResult.Success -> _state.update { it.copy(isUploadingAvatar = false, newAvatar = result.value, removeAvatar = false) }
                is ApiResult.Failure -> _state.update { it.copy(isUploadingAvatar = false, error = result.error.toUiMessage()) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val result = profiles.updateProfile(
                displayName = s.displayName.trim(),
                bio = s.bio.trim(),
                avatarMediaId = s.newAvatar?.mediaId,
                removeAvatar = s.removeAvatar && s.newAvatar == null,
            )
            when (result) {
                is ApiResult.Success -> _state.update { it.copy(isSaving = false, saved = true) }
                is ApiResult.Failure -> _state.update { it.copy(isSaving = false, error = result.error.toUiMessage()) }
            }
        }
    }

    private fun lengthError(value: String, max: Int, message: Int): UiMessage? =
        if (value.trim().length > max) UiMessage.Resource(message) else null

    companion object {
        const val DISPLAY_NAME_MAX = 60
        const val BIO_MAX = 150
    }
}
