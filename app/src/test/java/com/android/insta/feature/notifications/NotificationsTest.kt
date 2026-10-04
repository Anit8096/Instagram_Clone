package com.android.insta.feature.notifications

import com.android.insta.core.network.createHttpClient
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionManager
import com.android.insta.feature.chat.data.RealtimeClient
import com.android.insta.feature.chat.data.RealtimeEvent
import com.android.insta.feature.notifications.data.ActivityBadge
import com.android.insta.feature.notifications.data.NotificationsApi
import com.android.insta.feature.notifications.data.PushRegistrar
import com.android.insta.feature.notifications.data.PushTokens
import com.android.insta.feature.notifications.push.PushPayload
import com.android.insta.navigation.DeepLinks
import com.android.insta.navigation.DetailRoute
import com.android.insta.navigation.MainRoute
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.TEST_USER
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `deep links map to routes and reject anything malformed`() {
        val postId = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(DetailRoute.PostDetail(postId), DeepLinks.parse("insta://post/$postId"))
        assertEquals(DetailRoute.UserProfile("jane.doe"), DeepLinks.parse("insta://user/jane.doe"))
        assertEquals(DetailRoute.Thread("bob_1"), DeepLinks.parse("insta://chat/bob_1"))
        assertEquals(MainRoute.Notifications, DeepLinks.parse("insta://activity"))

        listOf(
            "https://post/$postId", // wrong scheme
            "insta://post/not-a-uuid",
            "insta://user/Jane", // usernames are lowercase
            "insta://user/a/b", // extra segments
            "insta://chat/",
            "insta://unknown/x",
            "not a uri",
        ).forEach { assertNull(it, DeepLinks.parse(it)) }
    }

    @Test
    fun `push payload needs a title and one of our links`() {
        val data = mapOf("title" to "bob", "body" to "hey", "link" to "insta://chat/bob", "tag" to "chat:bob", "type" to "message")
        val payload = PushPayload.from(data)!!
        assertEquals(true, payload.isMessage)
        assertEquals("chat:bob", payload.tag)
        assertNull(PushPayload.from(data - "title"))
        assertNull(PushPayload.from(data + ("link" to "https://evil.example")))
        assertEquals("insta://post/1", PushPayload.from(mapOf("title" to "t", "link" to "insta://post/1"))!!.tag) // tag defaults to the link
    }

    @Test
    fun `notification socket events decode`() {
        val event = json.decodeFromString(
            RealtimeEvent.serializer(),
            """{"type":"notification.new","unreadCount":3,"notification":{"id":"n1","type":"like","actor":{"id":"u2","username":"bob","displayName":"Bob"},
               "postId":"p1","read":false,"createdAt":"2026-10-04T00:00:00Z"}}""",
        ) as RealtimeEvent.NotificationNew
        assertEquals("bob" to 3L, event.notification.actor.username to event.unreadCount)
        assertEquals(0L, (json.decodeFromString(RealtimeEvent.serializer(), """{"type":"badge","unreadNotifications":0}""") as RealtimeEvent.Badge).unreadNotifications)
    }

    @Test
    fun `device token is registered on sign-in and rotation, and deleted locally on sign-out`() = runTest {
        val calls = mutableListOf<String>()
        val store = FakeSessionStore()
        val api = notificationsApi(store) { method, path -> calls += "$method $path"; "" }
        val tokens = FakePushTokens("tok:1")
        val registrar = PushRegistrar(api, tokens)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        registrar.bind(scope, SessionManager(store, scope))

        registrar.onNewToken("ignored-while-signed-out")
        assertEquals(emptyList<String>(), calls)

        store.save(Session("a", "r", TEST_USER))
        // The sign-in registration runs on the HTTP engine's thread; wait for it in real time.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (calls.isEmpty()) delay(10) } }
        registrar.onNewToken("tok:2")
        assertEquals(listOf("PUT /api/v1/me/devices/tok:1", "PUT /api/v1/me/devices/tok:2"), calls)

        registrar.unregister()
        assertEquals(1, tokens.deletes)
        registrar.onNewToken("tok:3") // the session is ending; no more registrations
        assertEquals(2, calls.size)
    }

    @Test
    fun `badge reads the unread count from the server and clears locally`() = runTest {
        val store = FakeSessionStore(Session("a", "r", TEST_USER))
        val api = notificationsApi(store) { _, path -> if (path.endsWith("unread-count")) """{"count":4}""" else "" }
        val badge = ActivityBadge(api, RealtimeClient(createHttpClient(MockEngine { respond("") }, "http://test", store, json, false), json, "ws://test/ws"))

        badge.refresh()
        assertEquals(4L, badge.unread.value)
        badge.clear()
        assertEquals(0L, badge.unread.value)
    }

    private fun notificationsApi(store: FakeSessionStore, handler: (method: String, path: String) -> String) = NotificationsApi(
        createHttpClient(
            MockEngine { request ->
                val body = handler(request.method.value, request.url.encodedPath)
                if (body.isEmpty()) respond("", HttpStatusCode.NoContent)
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://test", store, json, false,
        ),
    )

    private class FakePushTokens(private val token: String?) : PushTokens {
        var deletes = 0
        override suspend fun current(): String? = token
        override suspend fun delete() { deletes++ }
    }
}
