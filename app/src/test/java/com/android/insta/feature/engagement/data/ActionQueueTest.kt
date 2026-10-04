package com.android.insta.feature.engagement.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.android.insta.core.database.FeedPostEntity
import com.android.insta.feature.feed.data.withPendingLike
import com.android.insta.testutil.inMemoryDb
import com.android.insta.testutil.testActionQueue
import com.android.insta.testutil.testEngagementApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionQueueTest {
    private val db = inMemoryDb()
    private val sent = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private var offline = false
    private var scheduled = 0
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val queue = testActionQueue(
        db = db,
        api = testEngagementApi(MockEngine { request ->
            if (offline) throw java.io.IOException("offline")
            sent += "${request.method.value} ${request.url.encodedPath}"
            val body = if (request.url.encodedPath.endsWith("/like")) """{"liked":true,"likeCount":1}"""
            else """{"id":"c","postId":"p1","author":{"id":"u1","username":"jane","displayName":"J"},"body":"hi","createdAt":"2026-10-04T00:00:00Z"}"""
            respond(if (status.value < 300) body else """{"error":{"code":"NOT_FOUND","message":"Post not found"}}""", status, json)
        }),
        scheduler = { scheduled++ },
    )

    @After
    fun tearDown() = db.close()

    private suspend fun seedFeedRow(likes: Int = 0, liked: Boolean = false) = db.feedDao().insertAll(listOf(
        FeedPostEntity("p1", 0, "u2", "bob", "Bob", null, "i", "t", 100, 100, "c", likes, 0, 0L, liked),
    ))

    private suspend fun cached() = db.feedDao().pagingSource().load(androidx.paging.PagingSource.LoadParams.Refresh(null, 10, false))
        .let { (it as androidx.paging.PagingSource.LoadResult.Page).data.single() }

    @Test
    fun `like is shown immediately and rapid toggles collapse to the final state`() = runTest {
        seedFeedRow(likes = 3)
        queue.setLiked("p1", true)
        assertEquals(true to 4, cached().likedByMe to cached().likeCount)

        queue.setLiked("p1", false)
        queue.setLiked("p1", true)
        assertEquals(mapOf("p1" to true), queue.pendingLikeOverrides())
        assertEquals(3, scheduled)

        assertEquals(SyncOutcome.Done, queue.sync())
        assertEquals(listOf("PUT /api/v1/posts/p1/like"), sent) // one request for three taps
    }

    @Test
    fun `actions are delivered oldest first and synced comments are announced`() = runTest {
        queue.commentsSynced.test {
            queue.addComment("p1", "first")
            queue.setLiked("p1", true)
            queue.addComment("p1", "second")
            queue.sync()
            assertEquals("p1", awaitItem())
            assertEquals("p1", awaitItem())
        }
        assertEquals(3, sent.size)
        assertTrue(sent[0].startsWith("PUT /api/v1/posts/p1/comments/"))
        assertEquals("PUT /api/v1/posts/p1/like", sent[1])
        assertTrue(queue.pendingComments("p1").first().isEmpty())
    }

    @Test
    fun `offline keeps everything queued and asks to retry`() = runTest {
        offline = true
        queue.addComment("p1", "hello")
        assertEquals(SyncOutcome.Retry, queue.sync())
        val pending = queue.pendingComments("p1").first().single()
        assertEquals("hello" to false, pending.body to pending.failed)

        offline = false
        assertEquals(SyncOutcome.Done, queue.sync())
        assertTrue(queue.pendingComments("p1").first().isEmpty())
    }

    @Test
    fun `rejected like is rolled back and rejected comment is marked failed`() = runTest {
        seedFeedRow(likes = 0)
        status = HttpStatusCode.NotFound
        queue.setLiked("p1", true)
        queue.addComment("p1", "hello")

        assertEquals(SyncOutcome.Done, queue.sync())
        assertEquals(false to 0, cached().likedByMe to cached().likeCount)
        val failed = queue.pendingComments("p1").first().single()
        assertEquals(true, failed.failed)
        assertEquals("Post not found", failed.error)

        status = HttpStatusCode.OK
        queue.retry(failed.id)
        queue.sync()
        assertTrue(queue.pendingComments("p1").first().isEmpty())
    }

    @Test
    fun `queued like wins over an older server view during feed refresh`() {
        val fromServer = FeedPostEntity("p1", 0, "u2", "bob", "Bob", null, "i", "t", 1, 1, "", 5, 0, 0L, likedByMe = false)
        assertEquals(true to 6, fromServer.withPendingLike(true).let { it.likedByMe to it.likeCount })
        assertEquals(fromServer, fromServer.withPendingLike(false))
        assertEquals(fromServer, fromServer.withPendingLike(null))
    }
}
