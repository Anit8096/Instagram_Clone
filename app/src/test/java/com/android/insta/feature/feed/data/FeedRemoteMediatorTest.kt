package com.android.insta.feature.feed.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.insta.core.database.AppDatabase
import com.android.insta.core.database.FeedPostEntity
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.Session
import com.android.insta.feature.social.data.SocialApi
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.TEST_USER
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room (in-memory, Robolectric SQLite) + scripted backend. */
@OptIn(ExperimentalPagingApi::class)
@RunWith(AndroidJUnit4::class)
class FeedRemoteMediatorTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .setDriver(AndroidSQLiteDriver())
        .build()
    private val requests = mutableListOf<String?>()
    private var offline = false
    /** Pages by cursor: null = first page. */
    private val pages = mutableMapOf<String?, Pair<List<String>, String?>>()

    private fun postJson(id: String) = """{"id":"$id","author":{"id":"u2","username":"bob","displayName":"Bob"},
        "imageUrl":"/api/v1/media/m$id/full","thumbUrl":"/api/v1/media/m$id/thumb","width":1080,"height":1080,
        "caption":"c$id","createdAt":"2026-10-04T00:00:00Z"}"""

    private val mediator = FeedRemoteMediator(
        api = SocialApi(
            createHttpClient(
                engine = MockEngine { request ->
                    if (offline) throw java.io.IOException("offline")
                    val cursor = request.url.parameters["cursor"]
                    requests += cursor
                    val (ids, next) = pages.getValue(cursor)
                    val nextJson = next?.let { "\"$it\"" } ?: "null"
                    respond("""{"items":[${ids.joinToString { postJson(it) }}],"nextCursor":$nextJson}""", HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                },
                baseUrl = "http://test",
                sessionStore = FakeSessionStore(Session("a", "r", TEST_USER)),
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                enableLogging = false,
            ),
        ),
        db = db,
        urls = UrlResolver("http://test"),
    )

    private val state = PagingState<Int, FeedPostEntity>(emptyList(), null, PagingConfig(pageSize = 2), 0)

    @After
    fun tearDown() = db.close()

    /** Reads the cache through the same ordered PagingSource the UI uses. */
    private suspend fun cachedIds(): List<String> {
        val page = db.feedDao().pagingSource().load(androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false))
        return (page as androidx.paging.PagingSource.LoadResult.Page).data.map(FeedPostEntity::postId)
    }

    @Test
    fun `refresh caches the first page in order with absolute urls and remembers the cursor`() = runTest {
        pages[null] = listOf("3", "2") to "c1"
        val result = mediator.load(LoadType.REFRESH, state)

        assertTrue(result is RemoteMediator.MediatorResult.Success && !result.endOfPaginationReached)
        assertEquals(listOf("3", "2"), cachedIds())
        assertEquals("c1", db.feedDao().key(FEED_KEY)?.nextCursor)
    }

    @Test
    fun `append continues from the saved cursor and keeps order`() = runTest {
        pages[null] = listOf("3", "2") to "c1"
        pages["c1"] = listOf("1") to null
        mediator.load(LoadType.REFRESH, state)
        val result = mediator.load(LoadType.APPEND, state)

        assertTrue(result is RemoteMediator.MediatorResult.Success && result.endOfPaginationReached)
        assertEquals(listOf(null, "c1"), requests)
        assertEquals(listOf("3", "2", "1"), cachedIds())
    }

    @Test
    fun `failed refresh keeps the cached feed for offline viewing`() = runTest {
        pages[null] = listOf("2", "1") to null
        mediator.load(LoadType.REFRESH, state)

        offline = true
        val result = mediator.load(LoadType.REFRESH, state)
        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(listOf("2", "1"), cachedIds())
    }

    @Test
    fun `successful refresh replaces the old cache`() = runTest {
        pages[null] = listOf("2", "1") to null
        mediator.load(LoadType.REFRESH, state)
        pages[null] = listOf("5") to null
        mediator.load(LoadType.REFRESH, state)
        assertEquals(listOf("5"), cachedIds())
    }

    @Test
    fun `initial refresh is always launched (network first)`() = runTest {
        assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
    }
}
