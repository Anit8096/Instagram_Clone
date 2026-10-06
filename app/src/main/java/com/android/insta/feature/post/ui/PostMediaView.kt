package com.android.insta.feature.post.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.insta.R
import com.android.insta.feature.post.data.Post

/** Shape of a post on screen: the cover's, clamped to the server's 4:5…1.91:1 range (guards odd/older images). */
fun Post.displayAspect(): Float = (width.toFloat() / height.coerceAtLeast(1)).coerceIn(0.8f, 1.91f)

/**
 * A post's photo, or for a carousel a swipeable pager with a "2/5" chip and position dots. Every item shares the
 * cover's shape (the server crops them to it). [onClick] opens the post; null leaves the media non-clickable.
 */
@Composable
fun PostMediaView(post: Post, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val items = post.items
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    if (items.size == 1) {
        AsyncImage(
            model = items.single().url,
            contentDescription = post.caption.ifBlank { stringResource(R.string.cd_post_photo, post.authorUsername) },
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().aspectRatio(post.displayAspect()).then(clickModifier),
        )
        return
    }
    // Per list item: the feed and grids key items by post id, so a page position never moves to another post.
    val pager = rememberPagerState(pageCount = { items.size })
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(post.displayAspect())) {
            HorizontalPager(state = pager, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = items[page].url,
                    contentDescription = stringResource(R.string.cd_post_photo_n, page + 1, items.size, post.authorUsername),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(clickModifier),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clearAndSetSemantics { },
            ) {
                Text(
                    stringResource(R.string.carousel_position, pager.currentPage + 1, items.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        PagerDots(pager, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}

/** Decorative position dots; the pages themselves announce "Photo n of m". */
@Composable
private fun PagerDots(pager: PagerState, modifier: Modifier = Modifier) {
    Row(modifier = modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(pager.pageCount) { page ->
            val color = if (page == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
    }
}

/** Grid thumbnail: square crop of the cover, with a stacked-photos badge on carousels. */
@Composable
fun PostGridThumbnail(post: Post, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        AsyncImage(
            model = post.thumbUrl,
            contentDescription = post.caption.ifBlank { stringResource(R.string.cd_post_photo, post.authorUsername) },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (post.isCarousel) {
            // The clickable Box merges it into the thumbnail's announcement ("caption, Carousel post").
            Icon(
                painter = painterResource(R.drawable.ic_carousel),
                contentDescription = stringResource(R.string.cd_carousel),
                tint = Color.White, // on top of a photo, like the platform's own media badges
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
            )
        }
    }
}
