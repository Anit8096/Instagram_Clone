package com.android.insta.feature.post.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.android.insta.core.database.DraftState
import com.android.insta.core.database.PostDraftEntity
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.Session
import com.android.insta.testutil.FakeCompressor
import com.android.insta.testutil.FakeDraftDao
import com.android.insta.testutil.FakeScheduler
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.TEST_USER
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Robolectric only because the repository's API mentions android.net.Uri. */
@RunWith(AndroidJUnit4::class)
class DefaultPostRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val postJson = """{"id":"d1","author":{"id":"u1","username":"jane.doe","displayName":"Jane"},
        "imageUrl":"/api/v1/media/m1/full","thumbUrl":"/api/v1/media/m1/thumb","width":1080,"height":1080,
        "caption":"hi","createdAt":"2026-10-04T00:00:00Z"}"""

    private val dao = FakeDraftDao()
    private val scheduler = FakeScheduler()
    private val requests = mutableListOf<String>()

    /** Scripted backend: [uploadStatus] for POST /media, [createStatus]/[createBody] for PUT /posts. */
    private fun repository(
        uploadStatus: HttpStatusCode = HttpStatusCode.Created,
        createStatus: HttpStatusCode = HttpStatusCode.Created,
        createBody: String = postJson,
        offline: Boolean = false,
    ) = DefaultPostRepository(
        api = PostApi(
            createHttpClient(
                engine = MockEngine { request ->
                    if (offline) throw java.io.IOException("no network")
                    requests += "${request.method.value} ${request.url.encodedPath}"
                    when {
                        request.url.encodedPath == "/api/v1/media" -> respond(
                            """{"id":"m1","url":"/api/v1/media/m1/full","thumbUrl":"/api/v1/media/m1/thumb","width":1080,"height":1080}""",
                            uploadStatus, jsonHeaders,
                        )
                        request.method == HttpMethod.Put -> respond(createBody, createStatus, jsonHeaders)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
                baseUrl = "http://test",
                sessionStore = FakeSessionStore(Session("a", "r", TEST_USER)),
                json = json,
                enableLogging = false,
            ),
        ),
        drafts = dao,
        compressor = FakeCompressor(tmp.root),
        scheduler = scheduler,
        urls = UrlResolver("http://test"),
        now = { 1L },
    )

    private suspend fun seedDraft(mediaId: String? = null): PostDraftEntity {
        val file = tmp.newFile("d1.jpg").apply { writeBytes(byteArrayOf(9, 9)) }
        return PostDraftEntity("d1", file.path, "hi", mediaId = mediaId, createdAt = 1L).also { dao.upsert(it) }
    }

    @Test
    fun `createPost stores a draft and schedules its upload`() = runTest {
        val result = repository().createPost(Uri.parse("content://picked/1"), "  hello  ")

        assertTrue(result.isSuccess)
        val draft = dao.all().single()
        assertEquals("hello", draft.caption)
        assertEquals(listOf(draft.id), scheduler.enqueued)
    }

    @Test
    fun `unreadable image fails without creating a draft`() = runTest {
        val repo = DefaultPostRepository(PostApi(createHttpClient(MockEngine { respond("") }, "http://test", FakeSessionStore(), json, false)),
            dao, FakeCompressor(tmp.root, fail = true), scheduler, UrlResolver("http://test"))
        assertTrue(repo.createPost(Uri.parse("content://x"), "c").isFailure)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun `publish uploads the image, creates the post, then removes the draft and file`() = runTest {
        val draft = seedDraft()
        val repo = repository()

        repo.postsChanged.test {
            assertEquals(PublishOutcome.Published, repo.publishDraft("d1"))
            awaitItem()
        }
        assertEquals(listOf("POST /api/v1/media", "PUT /api/v1/posts/d1"), requests)
        assertNull(dao.get("d1"))
        assertFalse(File(draft.imagePath).exists())
    }

    @Test
    fun `a retry after the upload succeeded skips straight to creating the post`() = runTest {
        seedDraft()
        repository(createStatus = HttpStatusCode.ServiceUnavailable, createBody = """{"error":{"code":"X","message":"down"}}""").let {
            assertEquals(PublishOutcome.Retry, it.publishDraft("d1"))
        }
        assertEquals("m1", dao.get("d1")?.mediaId)

        requests.clear()
        assertEquals(PublishOutcome.Published, repository().publishDraft("d1"))
        assertEquals(listOf("PUT /api/v1/posts/d1"), requests)
    }

    @Test
    fun `no network means retry later, rejected content means failure`() = runTest {
        seedDraft()
        assertEquals(PublishOutcome.Retry, repository(offline = true).publishDraft("d1"))

        val rejected = repository(uploadStatus = HttpStatusCode.UnsupportedMediaType).let {
            // Error body for the media call comes from the same scripted response.
            it.publishDraft("d1")
        }
        assertTrue(rejected is PublishOutcome.Failed)
    }

    @Test
    fun `invalid media on create drops the saved media id so the next attempt re-uploads`() = runTest {
        seedDraft(mediaId = "stale")
        val outcome = repository(
            createStatus = HttpStatusCode.UnprocessableEntity,
            createBody = """{"error":{"code":"INVALID_MEDIA","message":"gone"}}""",
        ).publishDraft("d1")

        assertEquals(PublishOutcome.Retry, outcome)
        assertNull(dao.get("d1")?.mediaId)
    }

    @Test
    fun `retry resets a failed draft and reschedules it`() = runTest {
        dao.upsert(PostDraftEntity("d1", "x", "c", state = DraftState.FAILED, error = "boom", createdAt = 1L))
        repository().retry("d1")
        assertEquals(DraftState.PENDING, dao.get("d1")?.state)
        assertEquals(listOf("d1"), scheduler.enqueued)
    }

    @Test
    fun `clearDrafts cancels uploads and deletes drafts`() = runTest {
        seedDraft()
        repository().clearDrafts()
        assertTrue(scheduler.cancelled)
        assertTrue(dao.all().isEmpty())
    }
}
