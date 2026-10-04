package com.android.insta.feature.feed.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.room3.withWriteTransaction
import com.android.insta.core.database.AppDatabase
import com.android.insta.core.database.FeedDao
import com.android.insta.core.database.FeedPostEntity
import com.android.insta.core.database.RemoteKeyEntity
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.UrlResolver
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.toPost
import com.android.insta.feature.social.data.AppErrorException
import com.android.insta.feature.social.data.SocialApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

const val FEED_KEY = "feed"

/**
 * Network-first home feed backed by Room. Every launch refreshes from the server; the cache is only
 * replaced once a refresh succeeds, so offline users keep seeing the last feed they loaded.
 */
@OptIn(ExperimentalPagingApi::class)
class FeedRemoteMediator(
    private val api: SocialApi,
    private val db: AppDatabase,
    private val urls: UrlResolver,
    private val pendingLikes: suspend () -> Map<String, Boolean> = { emptyMap() },
) : RemoteMediator<Int, FeedPostEntity>() {
    private val dao: FeedDao get() = db.feedDao()

    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, FeedPostEntity>): MediatorResult {
        val cursor = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> dao.key(FEED_KEY)?.nextCursor ?: return MediatorResult.Success(endOfPaginationReached = true)
        }
        return when (val result = api.feed(cursor, state.config.pageSize)) {
            is ApiResult.Failure -> MediatorResult.Error(AppErrorException(result.error))
            is ApiResult.Success -> {
                val page = result.value
                val overrides = pendingLikes()
                db.withWriteTransaction {
                    if (loadType == LoadType.REFRESH) {
                        dao.clearPosts()
                        dao.clearKeys()
                    }
                    val start = dao.maxPosition() + 1
                    dao.insertAll(page.items.mapIndexed { i, dto -> dto.toPost(urls).toEntity(start + i).withPendingLike(overrides[dto.id]) })
                    dao.putKey(RemoteKeyEntity(FEED_KEY, page.nextCursor))
                }
                MediatorResult.Success(endOfPaginationReached = page.nextCursor == null)
            }
        }
    }
}

interface FeedRepository {
    fun feed(): Flow<PagingData<Post>>
    suspend fun removeFromCache(postId: String)
    suspend fun clearCache()
}

@OptIn(ExperimentalPagingApi::class)
class DefaultFeedRepository(
    private val db: AppDatabase,
    private val api: SocialApi,
    private val urls: UrlResolver,
    private val pendingLikes: suspend () -> Map<String, Boolean> = { emptyMap() },
) : FeedRepository {
    override fun feed(): Flow<PagingData<Post>> = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 5, enablePlaceholders = false),
        remoteMediator = FeedRemoteMediator(api, db, urls, pendingLikes),
        pagingSourceFactory = { db.feedDao().pagingSource() },
    ).flow.map { data -> data.map { it.toPost() } }

    override suspend fun removeFromCache(postId: String) = db.feedDao().deletePost(postId)

    override suspend fun clearCache() {
        db.withWriteTransaction {
            db.feedDao().clearPosts()
            db.feedDao().clearKeys()
        }
    }
}

fun Post.toEntity(position: Int) = FeedPostEntity(
    postId = id, position = position, authorId = authorId, authorUsername = authorUsername,
    authorDisplayName = authorDisplayName, authorAvatarUrl = authorAvatarUrl, imageUrl = imageUrl, thumbUrl = thumbUrl,
    width = width, height = height, caption = caption, likeCount = likeCount, commentCount = commentCount,
    createdAt = createdAt.toEpochMilli(), likedByMe = likedByMe,
)

fun FeedPostEntity.toPost() = Post(
    id = postId, authorId = authorId, authorUsername = authorUsername, authorDisplayName = authorDisplayName,
    authorAvatarUrl = authorAvatarUrl, imageUrl = imageUrl, thumbUrl = thumbUrl, width = width, height = height,
    caption = caption, likeCount = likeCount, commentCount = commentCount, createdAt = Instant.ofEpochMilli(createdAt),
    likedByMe = likedByMe,
)

/** Queued (unsent) like/unlike wins over the server's older view of the post. */
fun FeedPostEntity.withPendingLike(desired: Boolean?): FeedPostEntity = when {
    desired == null || desired == likedByMe -> this
    else -> copy(likedByMe = desired, likeCount = (likeCount + if (desired) 1 else -1).coerceAtLeast(0))
}
