package com.android.insta.feature.post.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.core.ui.asString
import org.koin.androidx.compose.koinViewModel

@Composable
fun CreatePostScreen(onShared: () -> Unit, viewModel: CreatePostViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // System photo picker: no storage permission needed.
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { viewModel.onEvent(CreatePostEvent.ImagePicked(it)) }
    }
    LaunchedEffect(state.shared) {
        if (state.shared) {
            viewModel.onEvent(CreatePostEvent.SharedHandled)
            onShared()
        }
    }
    CreatePostContent(
        state = state,
        onEvent = viewModel::onEvent,
        onPickImage = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
    )
}

@Composable
fun CreatePostContent(state: CreatePostUiState, onEvent: (CreatePostEvent) -> Unit, onPickImage: () -> Unit) {
    // safeDrawingPadding includes the IME, and it comes before verticalScroll, so the caption field stays above the keyboard.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.create_title), style = MaterialTheme.typography.titleLarge)
        val uri = state.imageUri
        if (uri == null) {
            Box(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.placeholder_create), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onPickImage) { Text(stringResource(R.string.action_choose_photo)) }
                }
            }
        } else {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.cd_selected_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickImage, enabled = !state.isSharing) { Text(stringResource(R.string.action_change_photo)) }
                TextButton(onClick = { onEvent(CreatePostEvent.ClearImage) }, enabled = !state.isSharing) {
                    Text(stringResource(R.string.action_remove))
                }
            }
            OutlinedTextField(
                value = state.caption,
                onValueChange = { onEvent(CreatePostEvent.CaptionChanged(it)) },
                label = { Text(stringResource(R.string.field_caption)) },
                isError = state.captionError != null,
                supportingText = { Text(state.captionError?.asString() ?: "${state.caption.length} / $CAPTION_MAX") },
                enabled = !state.isSharing,
                minLines = 3,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            )
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { onEvent(CreatePostEvent.Share) },
                enabled = state.canShare,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            ) {
                if (state.isSharing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.action_share))
                }
            }
        }
    }
}

@Composable
internal fun UploadBanner(
    uploading: Int,
    failedCaption: String?,
    failedMessage: String?,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.padding(12.dp).widthIn(max = 520.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        if (failedCaption != null) {
            Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(stringResource(R.string.upload_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    failedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) }
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        } else {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    if (uploading > 1) pluralStringResource(R.plurals.upload_in_progress_many, uploading, uploading) else stringResource(R.string.upload_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
