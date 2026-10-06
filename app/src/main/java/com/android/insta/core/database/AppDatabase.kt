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
import androidx.room3.Transaction
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.android.insta.feature.post.data.PostMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * A post waiting to be published. [id] is the post's final id (sent as `PUT /posts/{id}`), so a
 * retried upload can never create a duplicate. Its photos are [DraftItemEntity] rows.
 */
@Entity(tableName = "post_drafts")
data class PostDraftEntity(
    @PrimaryKey val id: String,
    val caption: String,
    val state: String = DraftState.PENDING,
    val error: String? = null,
    val createdAt: Long,
)

/**
 * One photo of a draft, in carousel order. [mediaId] is saved as soon as that photo is uploaded, so a retry
 * (or a restart after process death) continues with the first photo that has none.
 */
@Entity(tableName = "draft_items", primaryKeys = ["draftId", "position"])
data class DraftItemEntity(
    val draftId: String,
    val position: Int,
    val localPath: String,
    val mediaId: String? = null,
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

    @Query("SELECT * FROM draft_items WHERE draftId = :draftId ORDER BY position")
    suspend fun items(draftId: String): List<DraftItemEntity>

    @Query("SELECT * FROM draft_items")
    suspend fun allItems(): List<DraftItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<DraftItemEntity>)

    @Query("UPDATE draft_items SET mediaId = :mediaId WHERE draftId = :draftId AND position = :position")
    suspend fun setItemMedia(draftId: String, position: Int, mediaId: String)

    /** The server lost an upload: every photo is uploaded again on the next attempt. */
    @Query("UPDATE draft_items SET mediaId = NULL WHERE draftId = :draftId")
    suspend fun clearItemMedia(draftId: String)

    @Query("DELETE FROM draft_items WHERE draftId = :draftId")
    suspend fun deleteItems(draftId: String)

    @Query("DELETE FROM draft_items")
    suspend fun deleteAllItems()

    /** Draft and photos together, so a crash can't leave items without their draft (or the reverse). */
    @Transaction
    suspend fun insertDraftWithItems(draft: PostDraftEntity, items: List<DraftItemEntity>) {
        upsertItems(items)
        upsert(draft)
    }

    @Transaction
    suspend fun deleteDraftWithItems(draftId: String) {
        deleteItems(draftId)
        delete(draftId)
    }
}

/**
 * v3 → v4 (carousels): each draft's single photo moves into `draft_items`, `post_drafts` drops its photo columns,
 * and cached feed rows get a `media` list (empty for old rows: the cover fields still describe them).
 * Hand-written because rows move between tables; checked by `AppDatabaseMigrationTest`.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `draft_items` (`draftId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`localPath` TEXT NOT NULL, `mediaId` TEXT, PRIMARY KEY(`draftId`, `position`))",
        )
        connection.execSQL("INSERT INTO `draft_items` (`draftId`, `position`, `localPath`, `mediaId`) SELECT `id`, 0, `imagePath`, `mediaId` FROM `post_drafts`")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `_new_post_drafts` (`id` TEXT NOT NULL, `caption` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                "`error` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("INSERT INTO `_new_post_drafts` (`id`, `caption`, `state`, `error`, `createdAt`) SELECT `id`, `caption`, `state`, `error`, `createdAt` FROM `post_drafts`")
        connection.execSQL("DROP TABLE `post_drafts`")
        connection.execSQL("ALTER TABLE `_new_post_drafts` RENAME TO `post_drafts`")
        connection.execSQL("ALTER TABLE `feed_posts` ADD COLUMN `media` TEXT NOT NULL DEFAULT '[]'")
    }
}

/** Schemas are exported to app/schemas; v1→v2 only adds the feed cache tables, so Room migrates it automatically. */
@Database(
    entities = [PostDraftEntity::class, DraftItemEntity::class, FeedPostEntity::class, RemoteKeyEntity::class, PendingActionEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@ColumnTypeConverters(MediaListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDraftDao(): PostDraftDao
    abstract fun feedDao(): FeedDao
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder<AppDatabase>(context, "insta.db")
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_3_4)
                .build()
    }
}

/** Stores a post's media items as JSON in one column (they're always read and written with the post). */
class MediaListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @ColumnTypeConverter
    fun fromList(items: List<PostMedia>): String = json.encodeToString(items)

    @ColumnTypeConverter
    fun toList(raw: String): List<PostMedia> = runCatching { json.decodeFromString<List<PostMedia>>(raw) }.getOrDefault(emptyList())
}
