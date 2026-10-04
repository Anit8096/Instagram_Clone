package com.android.insta.core.database

import androidx.paging.PagingSource
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter

/** Cached home-feed row. [position] preserves server order across pages; URLs are already absolute. */
@Entity(tableName = "feed_posts")
data class FeedPostEntity(
    @PrimaryKey val postId: String,
    val position: Int,
    val authorId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String?,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val caption: String,
    val likeCount: Int,
    val commentCount: Int,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val likedByMe: Boolean = false,
)

/** Where the next feed page starts; a single row keyed by [list]. */
@Entity(tableName = "remote_keys")
data class RemoteKeyEntity(@PrimaryKey val list: String, val nextCursor: String?)

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface FeedDao {
    @Query("SELECT * FROM feed_posts ORDER BY position ASC")
    fun pagingSource(): PagingSource<Int, FeedPostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(posts: List<FeedPostEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM feed_posts")
    suspend fun maxPosition(): Int

    @Query("DELETE FROM feed_posts")
    suspend fun clearPosts()

    @Query("DELETE FROM feed_posts WHERE postId = :postId")
    suspend fun deletePost(postId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putKey(key: RemoteKeyEntity)

    @Query("SELECT * FROM remote_keys WHERE list = :list")
    suspend fun key(list: String): RemoteKeyEntity?

    @Query("DELETE FROM remote_keys")
    suspend fun clearKeys()

    /** Optimistic like toggle on the cached feed. */
    @Query("UPDATE feed_posts SET likedByMe = :liked, likeCount = MAX(likeCount + :delta, 0) WHERE postId = :postId")
    suspend fun setLiked(postId: String, liked: Boolean, delta: Int)

    @Query("UPDATE feed_posts SET commentCount = MAX(commentCount + :delta, 0) WHERE postId = :postId")
    suspend fun addComments(postId: String, delta: Int)

    @Query("SELECT COUNT(*) FROM feed_posts")
    suspend fun count(): Int
}
