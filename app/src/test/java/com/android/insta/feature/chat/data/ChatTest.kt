package com.android.insta.feature.chat.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.Session
import com.android.insta.feature.engagement.data.ActionQueue
import com.android.insta.feature.engagement.data.SyncOutcome
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.TEST_USER
import com.android.insta.testutil.inMemoryDb
import com.android.insta.testutil.testEngagementApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Regression: without the plugin every `webSocket()` call throws and realtime silently never connects. */
    @Test
    fun `shared http client supports websockets`() {
        val client = createHttpClient(MockEngine { respond("") }, "http://test", FakeSessionStore(), json, false)
        assertTrue(client.pluginOrNull(io.ktor.client.plugins.websocket.WebSockets) != null)
    }

    @Test
    fun `server realtime events decode by type`() {
        val new = json.decodeFromString(RealtimeEvent.serializer(),
            """{"type":"message.new","message":{"id":"m1","conversationId":"c1","senderId":"u2","body":"hi","createdAt":"2026-10-04T00:00:00Z"}}""")
        assertEquals("hi", (new as RealtimeEvent.MessageNew).message.body)
        val read = json.decodeFromString(RealtimeEvent.serializer(), """{"type":"message.read","conversationId":"c1","userId":"u2","readAt":"2026-10-04T00:00:01Z"}""")
        assertEquals("u2", (read as RealtimeEvent.MessageRead).userId)
    }

    @Test
    fun `queued message is delivered with its client id and announced`() = runTest {
        val sent = mutableListOf<String>()
        val chat = ChatApi(createHttpClient(
            MockEngine { request ->
                sent += "${request.method.value} ${request.url.encodedPath}"
                respond("""{"id":"x","conversationId":"c1","senderId":"u1","body":"hello","createdAt":"2026-10-04T00:00:00Z"}""",
                    HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://test", FakeSessionStore(Session("a", "r", TEST_USER)), json, false,
        ))
        val queue = ActionQueue(inMemoryDb(), testEngagementApi(), UrlResolver("http://test"), { }, chat = chat)

        queue.messagesSynced.test {
            queue.sendMessage("c1", " hello ")
            assertEquals("hello", queue.pendingMessages("c1").first().single().body)
            assertEquals(SyncOutcome.Done, queue.sync())
            assertEquals("c1", awaitItem())
        }
        assertTrue(sent.single().startsWith("PUT /api/v1/conversations/c1/messages/"))
        assertTrue(queue.pendingMessages("c1").first().isEmpty())
    }
}
