package com.android.insta.feature.post.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.android.insta.core.database.DraftItemEntity
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
import io.ktor.client.engine.mock.toByteArray
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
    private val compressor by lazy { FakeCompressor(tmp.root) }
    private val requests = mutableListOf<String>()
    private val createBodies = mutableListOf<String>()
    private var uploads = 0

    /**
     * Scripted backend: uploads answer m1, m2, … ([uploadStatus], or [failUploadNumber] fails just that upload),
     * PUT /posts answers [createStatus] / [createBody].
     */
    private fun repository(
        uploadStatus: HttpStatusCode = HttpStatusCode.Created,
        failUploadNumber: Int? = null,
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
                        request.url.encodedPath == "/api/v1/media" -> {
                            val n = ++uploads
                            val status = if (n == failUploadNumber) HttpStatusCode.ServiceUnavailable else uploadStatus
                            respond(
                                """{"id":"m$n","url":"/api/v1/media/m$n/full","thumbUrl":"/api/v1/media/m$n/thumb","width":1080,"height":1080}""",
                                status, jsonHeaders,
                            )
                        }
                        request.method == HttpMethod.Put -> {
                            createBodies += String(request.body.toByteArray())
                            respond(createBody, createStatus, jsonHeaders)
                        }
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
        compressor = compressor,
        scheduler = scheduler,
        urls = UrlResolver("http://test"),
        now = { 1L },
    )

    /** A draft "d1" with [count] photos on disk; [mediaIds] marks the ones already uploaded. */
    private suspend fun seedDraft(count: Int = 1, mediaIds: Map<Int, String> = emptyMap()): List<File> {
        val files = (0 until count).map { tmp.newFile("d1-$it.jpg").apply { writeBytes(byteArrayOf(9, 9)) } }
        dao.upsertItems(files.mapIndexed { i, file -> DraftItemEntity("d1", i, file.path, mediaIds[i]) })
        dao.upsert(PostDraftEntity("d1", "hi", createdAt = 1L))
        return files
    }

    private fun uris(count: Int) = (1..count).map { Uri.parse("content://picked/$it") }

    @Test
    fun `createPost stores one draft with its photos in order, cropped to the chosen shape`() = runTest {
        val result = repository().createPost(uris(3), "  hello  ", CropAspect.PORTRAIT)

        assertTrue(result.isSuccess)
        val draft = dao.all().single()
        assertEquals("hello", draft.caption)
        assertEquals(listOf(0, 1, 2), dao.items(draft.id).map { it.position })
        assertEquals(compressor.written.map { it.path }, dao.items(draft.id).map { it.localPath })
        assertEquals(listOf(0.8f, 0.8f, 0.8f), compressor.aspects)
        assertEquals(listOf(draft.id), scheduler.enqueued)
    }

    @Test
    fun `an unreadable photo fails the whole post and cleans up the ones already prepared`() = runTest {
        compressor.failOn = Uri.parse("content://picked/3")
        assertTrue(repository().createPost(uris(3), "c").isFailure)
        assertTrue(dao.all().isEmpty())
        assertTrue(dao.allItems().isEmpty())
        assertEquals(2, compressor.written.size)
        assertTrue(compressor.written.none(File::exists))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `publish uploads every photo in order, creates the post, then removes the draft and files`() = runTest {
        val files = seedDraft(count = 3)
        val repo = repository()

        repo.postsChanged.test {
            assertEquals(PublishOutcome.Published, repo.publishDraft("d1"))
            awaitItem()
        }
        assertEquals(List(3) { "POST /api/v1/media" } + "PUT /api/v1/posts/d1", requests)
        assertEquals("""{"mediaIds":["m1","m2","m3"],"caption":"hi"}""", createBodies.single())
        assertNull(dao.get("d1"))
        assertTrue(dao.allItems().isEmpty())
        assertTrue(files.none(File::exists))
    }

    @Test
    fun `a retry continues with the first photo that wasn't uploaded`() = runTest {
        seedDraft(count = 3)
        assertEquals(PublishOutcome.Retry, repository(failUploadNumber = 2).publishDraft("d1"))
        assertEquals(listOf("m1", null, null), dao.items("d1").map { it.mediaId })

        requests.clear()
        assertEquals(PublishOutcome.Published, repository().publishDraft("d1"))
        assertEquals(List(2) { "POST /api/v1/media" } + "PUT /api/v1/posts/d1", requests)
        assertEquals("""{"mediaIds":["m1","m3","m4"],"caption":"hi"}""", createBodies.last())
    }

    @Test
    fun `a retry after every upload succeeded skips straight to creating the post`() = runTest {
        seedDraft(count = 2)
        repository(createStatus = HttpStatusCode.ServiceUnavailable, createBody = """{"error":{"code":"X","message":"down"}}""").let {
            assertEquals(PublishOutcome.Retry, it.publishDraft("d1"))
        }
        assertEquals(listOf("m1", "m2"), dao.items("d1").map { it.mediaId })

        requests.clear()
        assertEquals(PublishOutcome.Published, repository().publishDraft("d1"))
        assertEquals(listOf("PUT /api/v1/posts/d1"), requests)
    }

    @Test
    fun `no network means retry later, rejected content means failure`() = runTest {
        seedDraft()
        assertEquals(PublishOutcome.Retry, repository(offline = true).publishDraft("d1"))
        assertTrue(repository(uploadStatus = HttpStatusCode.UnsupportedMediaType).publishDraft("d1") is PublishOutcome.Failed)
    }

    @Test
    fun `a missing photo file fails instead of retrying forever`() = runTest {
        seedDraft(count = 2).last().delete()
        assertTrue(repository().publishDraft("d1") is PublishOutcome.Failed)
    }

    @Test
    fun `invalid media on create drops every saved media id so the next attempt re-uploads`() = runTest {
        seedDraft(count = 2, mediaIds = mapOf(0 to "stale", 1 to "stale2"))
        val outcome = repository(
            createStatus = HttpStatusCode.UnprocessableEntity,
            createBody = """{"error":{"code":"INVALID_MEDIA","message":"gone"}}""",
        ).publishDraft("d1")

        assertEquals(PublishOutcome.Retry, outcome)
        assertEquals(listOf(null, null), dao.items("d1").map { it.mediaId })
    }

    @Test
    fun `retry resets a failed draft and reschedules it`() = runTest {
        dao.upsert(PostDraftEntity("d1", "c", state = DraftState.FAILED, error = "boom", createdAt = 1L))
        repository().retry("d1")
        assertEquals(DraftState.PENDING, dao.get("d1")?.state)
        assertEquals(listOf("d1"), scheduler.enqueued)
    }

    @Test
    fun `discard and clearDrafts remove drafts, items and files`() = runTest {
        val files = seedDraft(count = 2)
        repository().discard("d1")
        assertNull(dao.get("d1"))
        assertTrue(dao.allItems().isEmpty())
        assertTrue(files.none(File::exists))

        seedDraft(count = 1).also { repository().clearDrafts() }.let { assertFalse(it.single().exists()) }
        assertTrue(scheduler.cancelled)
        assertTrue(dao.all().isEmpty())
        assertTrue(dao.allItems().isEmpty())
    }

    @Test
    fun `server media list maps to absolute item urls, older responses fall back to the cover`() {
        val urls = UrlResolver("http://test")
        val carousel = json.decodeFromString<PostDto>(
            postJson.replace(
                "\"caption\"",
                """"media":[{"id":"m1","type":"photo","url":"/api/v1/media/m1/full","thumbUrl":"/api/v1/media/m1/thumb","width":1080,"height":1350},
                {"id":"m2","url":"/api/v1/media/m2/full","thumbUrl":"/api/v1/media/m2/thumb","width":1080,"height":1350}],"caption"""",
            ),
        ).toPost(urls)
        assertEquals(listOf("http://test/api/v1/media/m1/full", "http://test/api/v1/media/m2/full"), carousel.items.map { it.url })
        assertTrue(carousel.isCarousel)

        val single = json.decodeFromString<PostDto>(postJson).toPost(urls)
        assertEquals(listOf(PostMedia("d1", "http://test/api/v1/media/m1/full", "http://test/api/v1/media/m1/thumb", 1080, 1080)), single.items)
        assertFalse(single.isCarousel)
    }
}
