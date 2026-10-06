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

    private fun draft(id: String, createdAt: Long) = PostDraftEntity(id, "caption $id", createdAt = createdAt)

    @Test
    fun upsertReplacesAndObserveOrdersByCreation() = runTest {
        dao.upsert(draft("b", createdAt = 2))
        dao.upsert(draft("a", createdAt = 1))
        dao.upsert(draft("b", createdAt = 2).copy(state = DraftState.FAILED, error = "x"))

        val all = dao.observeAll().first()
        assertEquals(listOf("a", "b"), all.map { it.id })
        assertEquals("x", all.last().error)
        assertEquals(DraftState.FAILED, dao.get("b")?.state)
    }

    @Test
    fun itemsKeepTheirOrderAndRememberUploadsPerPhoto() = runTest {
        dao.upsertItems((2 downTo 0).map { DraftItemEntity("d", it, "/files/$it.jpg") } + DraftItemEntity("other", 0, "/o.jpg"))
        dao.setItemMedia("d", 1, "m1")
        assertEquals(listOf(0, 1, 2), dao.items("d").map { it.position })
        assertEquals(listOf(null, "m1", null), dao.items("d").map { it.mediaId })

        dao.clearItemMedia("d")
        assertEquals(listOf(null, null, null), dao.items("d").map { it.mediaId })

        dao.deleteItems("d")
        assertEquals(listOf("other"), dao.allItems().map { it.draftId })
        dao.deleteAllItems()
        assertEquals(0, dao.allItems().size)
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
