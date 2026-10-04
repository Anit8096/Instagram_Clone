package com.android.insta.core.database

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * A post waiting to be published. [id] is the post's final id (sent as `PUT /posts/{id}`), so a
 * retried upload can never create a duplicate. [mediaId] is saved after the image upload, so a retry
 * skips straight to creating the post.
 */
@Entity(tableName = "post_drafts")
data class PostDraftEntity(
    @PrimaryKey val id: String,
    val imagePath: String,
    val caption: String,
    val state: String = DraftState.PENDING,
    val mediaId: String? = null,
    val error: String? = null,
    val createdAt: Long,
)

object DraftState {
    const val PENDING = "pending"
    const val FAILED = "failed"
}

@Dao
interface PostDraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: PostDraftEntity)

    @Query("SELECT * FROM post_drafts WHERE id = :id")
    suspend fun get(id: String): PostDraftEntity?

    @Query("SELECT * FROM post_drafts ORDER BY createdAt")
    fun observeAll(): Flow<List<PostDraftEntity>>

    @Query("SELECT * FROM post_drafts")
    suspend fun all(): List<PostDraftEntity>

    @Query("DELETE FROM post_drafts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM post_drafts")
    suspend fun deleteAll()
}

/** Schemas are exported to app/schemas; v1→v2 only adds the feed cache tables, so Room migrates it automatically. */
@Database(
    entities = [PostDraftEntity::class, FeedPostEntity::class, RemoteKeyEntity::class, PendingActionEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDraftDao(): PostDraftDao
    abstract fun feedDao(): FeedDao
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder<AppDatabase>(context, "insta.db")
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
