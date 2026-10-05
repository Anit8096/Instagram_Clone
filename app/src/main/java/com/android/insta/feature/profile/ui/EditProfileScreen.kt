package com.android.insta.feature.profile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.insta.R
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarLarge
import com.android.insta.core.ui.asString
import org.koin.androidx.compose.koinViewModel

@Composable
fun EditProfileScreen(onDone: () -> Unit, onChangePhone: () -> Unit = {}, viewModel: EditProfileViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { viewModel.onEvent(EditProfileEvent.AvatarPicked(it)) }
    }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    EditProfileContent(
        state = state,
        onEvent = viewModel::onEvent,
        onPickAvatar = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        onBack = onDone,
        onChangePhone = onChangePhone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileContent(
    state: EditProfileUiState,
    onEvent: (EditProfileEvent) -> Unit,
    onPickAvatar: () -> Unit,
    onBack: () -> Unit,
    onChangePhone: () -> Unit = {},
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { onEvent(EditProfileEvent.Save) }, enabled = state.canSave) {
                        if (state.isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(state.shownAvatarUrl, AvatarLarge)
                if (state.isUploadingAvatar) CircularProgressIndicator()
            }
            Row {
                TextButton(onClick = onPickAvatar, enabled = !state.isUploadingAvatar) { Text(stringResource(R.string.action_change_photo)) }
                if (state.shownAvatarUrl != null) {
                    TextButton(onClick = { onEvent(EditProfileEvent.RemoveAvatar) }, enabled = !state.isUploadingAvatar) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            }
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { onEvent(EditProfileEvent.DisplayNameChanged(it)) },
                label = { Text(stringResource(R.string.field_display_name_edit)) },
                isError = state.displayNameError != null,
                supportingText = state.displayNameError?.let { { Text(it.asString()) } },
                singleLine = true,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.bio,
                onValueChange = { onEvent(EditProfileEvent.BioChanged(it)) },
                label = { Text(stringResource(R.string.field_bio)) },
                isError = state.bioError != null,
                supportingText = { Text(state.bioError?.asString() ?: "${state.bio.length} / ${EditProfileViewModel.BIO_MAX}") },
                minLines = 2,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.field_phone)) },
                supportingContent = { Text(state.phone ?: stringResource(R.string.phone_not_set)) },
                trailingContent = { TextButton(onClick = onChangePhone) { Text(stringResource(R.string.action_change)) } },
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            )
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        }
    }
}
