package com.android.insta.core.database

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the generated DAO against the device's real SQLite engine. */
@RunWith(AndroidJUnit4::class)
class PostDraftDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PostDraftDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
        dao = db.postDraftDao()
    }

    @After
    fun tearDown() = db.close()

    private fun draft(id: String, createdAt: Long) = PostDraftEntity(id, "/files/$id.jpg", "caption $id", createdAt = createdAt)

    @Test
    fun upsertReplacesAndObserveOrdersByCreation() = runTest {
        dao.upsert(draft("b", createdAt = 2))
        dao.upsert(draft("a", createdAt = 1))
        dao.upsert(draft("b", createdAt = 2).copy(mediaId = "m1", state = DraftState.FAILED, error = "x"))

        val all = dao.observeAll().first()
        assertEquals(listOf("a", "b"), all.map { it.id })
        assertEquals("m1", all.last().mediaId)
        assertEquals(DraftState.FAILED, dao.get("b")?.state)
    }

    @Test
    fun deleteAndDeleteAll() = runTest {
        dao.upsert(draft("a", 1))
        dao.upsert(draft("b", 2))
        dao.delete("a")
        assertNull(dao.get("a"))
        dao.deleteAll()
        assertEquals(0, dao.all().size)
    }
}
