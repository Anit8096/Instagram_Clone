package com.android.insta.feature.post.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.core.ui.asString
import com.android.insta.feature.post.data.CropAspect
import com.android.insta.feature.post.data.MAX_POST_ITEMS
import org.koin.androidx.compose.koinViewModel

@Composable
fun CreatePostScreen(onShared: () -> Unit, viewModel: CreatePostViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // System photo picker: no storage permission needed. It allows at most MAX_POST_ITEMS per pick; the
    // ViewModel caps the total when adding to photos already chosen.
    val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia(MAX_POST_ITEMS)) { uris ->
        if (uris.isNotEmpty()) viewModel.onEvent(CreatePostEvent.ImagesPicked(uris))
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
        onPickImages = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
    )
}

@Composable
fun CreatePostContent(state: CreatePostUiState, onEvent: (CreatePostEvent) -> Unit, onPickImages: () -> Unit) {
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
        if (state.images.isEmpty()) {
            Box(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.placeholder_create), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onPickImages) { Text(stringResource(R.string.action_choose_photos)) }
                }
            }
            state.error?.let { ErrorText(it.asString()) }
            return@Column
        }

        SelectedPreview(state)
        CropChoice(state.aspect, enabled = !state.isSharing, onChange = { onEvent(CreatePostEvent.AspectChanged(it)) })
        PhotoStrip(state, onSelect = { onEvent(CreatePostEvent.Select(it)) })
        Text(
            pluralStringResource(R.plurals.create_photo_count, MAX_POST_ITEMS, state.images.size, MAX_POST_ITEMS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val editable = !state.isSharing
            TextButton(onClick = { onEvent(CreatePostEvent.MoveSelected(-1)) }, enabled = editable && state.selected > 0) {
                Text(stringResource(R.string.action_move_earlier))
            }
            TextButton(onClick = { onEvent(CreatePostEvent.MoveSelected(1)) }, enabled = editable && state.selected < state.images.lastIndex) {
                Text(stringResource(R.string.action_move_later))
            }
            TextButton(onClick = { onEvent(CreatePostEvent.RemoveSelected) }, enabled = editable) { Text(stringResource(R.string.action_remove)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickImages, enabled = state.canAddMore) { Text(stringResource(R.string.action_add_photos)) }
            TextButton(onClick = { onEvent(CreatePostEvent.ClearImages) }, enabled = !state.isSharing) {
                Text(stringResource(R.string.action_remove_all))
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
        state.error?.let { ErrorText(it.asString()) }
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

/** The selected photo, shown in the chosen crop (Original: the whole photo, fitted). */
@Composable
private fun SelectedPreview(state: CreatePostUiState) {
    val uri = state.images[state.selected]
    val ratio = state.aspect.ratio
    AsyncImage(
        model = uri,
        contentDescription = stringResource(R.string.cd_selected_photo_n, state.selected + 1, state.images.size),
        contentScale = if (ratio == null) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .aspectRatio(ratio ?: 1f)
            .clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun CropChoice(selected: CropAspect, enabled: Boolean, onChange: (CropAspect) -> Unit) {
    val options = listOf(
        CropAspect.ORIGINAL to (R.string.crop_original to R.string.cd_crop_original),
        CropAspect.SQUARE to (R.string.crop_square to R.string.cd_crop_square),
        CropAspect.PORTRAIT to (R.string.crop_portrait to R.string.cd_crop_portrait),
        CropAspect.LANDSCAPE to (R.string.crop_landscape to R.string.cd_crop_landscape),
    )
    val group = stringResource(R.string.crop_label)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().semantics { contentDescription = group }) {
        options.forEachIndexed { index, (aspect, labels) ->
            val description = stringResource(labels.second)
            SegmentedButton(
                selected = aspect == selected,
                onClick = { onChange(aspect) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.semantics { contentDescription = description },
                label = { Text(stringResource(labels.first)) },
            )
        }
    }
}

/** Thumbnails in post order; tapping one selects it for the preview and the move/remove actions. */
@Composable
private fun PhotoStrip(state: CreatePostUiState, onSelect: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.images, key = { _, uri: Uri -> uri.toString() }) { index, uri ->
            val selected = index == state.selected
            val description = stringResource(
                if (selected) R.string.cd_selected_photo_n else R.string.cd_photo_n,
                index + 1,
                state.images.size,
            )
            AsyncImage(
                model = uri,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // A thin outline keeps mostly-white photos visible; the selected one gets a thick primary border.
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
            )
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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
