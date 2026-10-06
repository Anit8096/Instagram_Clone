package com.android.insta.feature.post.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.core.ui.Avatar
import com.android.insta.core.ui.AvatarSmall
import com.android.insta.feature.post.data.Post
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Feed item: author row, photo or carousel (aspect clamped to 4:5…1.91:1, like the server), caption and date. */
@Composable
fun PostCard(
    post: Post,
    onAuthorClick: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
    onLikeClick: (Post) -> Unit,
    onCommentsClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onAuthorClick(post.authorUsername) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(post.authorAvatarUrl, AvatarSmall)
            Text(post.authorUsername, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        PostMediaView(post, onClick = { onPhotoClick(post.id) })
        PostActions(post, onLikeClick, onCommentsClick)
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (post.caption.isNotBlank()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(post.authorUsername) }
                        append("  ")
                        append(post.caption)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(post.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Like toggle, like count and the comments entry point; shared by the feed card and the post screen. */
@Composable
fun PostActions(post: Post, onLikeClick: (Post) -> Unit, onCommentsClick: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconToggleButton(checked = post.likedByMe, onCheckedChange = { onLikeClick(post) }) {
            Icon(
                if (post.likedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(if (post.likedByMe) R.string.action_unlike else R.string.action_like),
                tint = if (post.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(pluralStringResource(R.plurals.like_count, post.likeCount, post.likeCount), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onCommentsClick(post.id) }) {
            Text(
                if (post.commentCount == 0) stringResource(R.string.action_add_comment)
                else pluralStringResource(R.plurals.view_comments, post.commentCount, post.commentCount),
            )
        }
    }
}
