package com.android.insta.feature.post.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarSmall
import com.android.insta.core.ui.asString
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PostDetailScreen(
    postId: String,
    onBack: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onCommentsClick: (String) -> Unit,
    viewModel: PostDetailViewModel = koinViewModel(key = "post-$postId") { parametersOf(postId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    PostDetailContent(state, viewModel::onEvent, onBack, onAuthorClick, onCommentsClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailContent(
    state: PostDetailUiState,
    onEvent: (PostDetailEvent) -> Unit,
    onBack: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onCommentsClick: (String) -> Unit = {},
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.post_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.isMine) {
                        IconButton(onClick = { confirmDelete = true }, enabled = !state.isDeleting) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete_post))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            val post = state.post
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.notFound -> Text(stringResource(R.string.post_not_found), modifier = Modifier.align(Alignment.Center))
                post == null -> Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { onEvent(PostDetailEvent.Retry) }) { Text(stringResource(R.string.action_retry)) }
                }
                else -> Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onAuthorClick(post.authorUsername) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Avatar(post.authorAvatarUrl, AvatarSmall)
                        Text(post.authorUsername, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    AsyncImage(
                        model = post.imageUrl,
                        contentDescription = post.caption.ifBlank { stringResource(R.string.cd_post_photo, post.authorUsername) },
                        contentScale = ContentScale.Crop,
                        // Same 4:5…1.91:1 range the server crops to; guards against older/odd images.
                        modifier = Modifier.fillMaxWidth().aspectRatio((post.width.toFloat() / post.height.coerceAtLeast(1)).coerceIn(0.8f, 1.91f)),
                    )
                    PostActions(post, onLikeClick = { onEvent(PostDetailEvent.ToggleLike) }, onCommentsClick = onCommentsClick)
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (post.caption.isNotBlank()) {
                            Text(post.caption, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(post.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_post_title)) },
            text = { Text(stringResource(R.string.delete_post_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onEvent(PostDetailEvent.Delete)
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
