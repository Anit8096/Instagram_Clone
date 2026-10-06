package com.android.insta.core.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades real on-device databases built from the exported schemas (app/schemas). */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        databaseClass = AppDatabase::class,
        driver = AndroidSQLiteDriver(),
        file = instrumentation.targetContext.getDatabasePath(TEST_DB),
    )

    /** Each test builds its own v3 database; a file left by the previous test would be "migrated" instead. */
    @Before
    fun deleteOldDatabase() {
        instrumentation.targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate3To4MovesEachDraftPhotoIntoItemsAndKeepsCachedPosts() = runTest {
        helper.createDatabase(3).use { v3 ->
            v3.execSQL(
                "INSERT INTO post_drafts (id, imagePath, caption, state, mediaId, error, createdAt) " +
                    "VALUES ('d1', '/files/d1.jpg', 'sunset', 'pending', 'm1', NULL, 5), ('d2', '/files/d2.jpg', '', 'failed', NULL, 'boom', 6)",
            )
            v3.execSQL(
                "INSERT INTO feed_posts (postId, position, authorId, authorUsername, authorDisplayName, authorAvatarUrl, imageUrl, " +
                    "thumbUrl, width, height, caption, likeCount, commentCount, createdAt, likedByMe) " +
                    "VALUES ('p1', 0, 'u1', 'jane', 'Jane', NULL, 'http://x/full', 'http://x/thumb', 1080, 1350, 'hi', 2, 1, 7, 1)",
            )
        }

        helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { v4 ->
            assertEquals(
                listOf("d1|0|/files/d1.jpg|m1", "d2|0|/files/d2.jpg|null"),
                v4.rows("SELECT draftId, position, localPath, mediaId FROM draft_items ORDER BY draftId"),
            )
            assertEquals(
                listOf("d1|sunset|pending|null|5", "d2||failed|boom|6"),
                v4.rows("SELECT id, caption, state, error, createdAt FROM post_drafts ORDER BY id"),
            )
            assertEquals(listOf("p1|[]|1"), v4.rows("SELECT postId, media, likedByMe FROM feed_posts"))
        }
    }

    @Test
    fun theAppOpensAMigratedDatabaseAndReadsOldRowsThroughItsDaos() = runTest {
        helper.createDatabase(3).use { v3 ->
            v3.execSQL("INSERT INTO post_drafts (id, imagePath, caption, state, mediaId, error, createdAt) VALUES ('d1', '/f.jpg', 'c', 'pending', NULL, NULL, 1)")
        }
        val db = Room.databaseBuilder<AppDatabase>(instrumentation.targetContext, TEST_DB)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_3_4)
            .build()
        try {
            val dao = db.postDraftDao()
            assertEquals("c", dao.get("d1")?.caption)
            assertEquals(listOf(DraftItemEntity("d1", 0, "/f.jpg", null)), dao.items("d1"))
        } finally {
            db.close()
        }
    }

    private fun SQLiteConnection.rows(sql: String): List<String> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).joinToString("|") { if (statement.isNull(it)) "null" else statement.getText(it) })
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
