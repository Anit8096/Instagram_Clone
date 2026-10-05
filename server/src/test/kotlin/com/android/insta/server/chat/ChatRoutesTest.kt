package com.android.insta.server.chat

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.common.AppJson
import com.android.insta.server.common.Page
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.signUp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class ChatRoutesTest : IntegrationTest() {

    private suspend fun HttpClient.register(name: String): AuthResponse = signUp(name)

    private suspend fun HttpClient.open(token: String, username: String): ConversationDto = post("/api/v1/conversations") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(OpenConversationRequest(username))
    }.body()

    private suspend fun HttpClient.send(token: String, conversation: String, id: String, body: String) =
        put("/api/v1/conversations/$conversation/messages/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(SendMessageRequest(body)) }

    @Test
    fun `conversation is shared, messages are idempotent, unread and seen work`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val conv = client.open(jane.accessToken, "bob")
        assertEquals(conv.id, client.open(bob.accessToken, "jane").id) // same pair, whoever opens

        val m1 = Uuid.random().toString()
        assertEquals(HttpStatusCode.Created, client.send(jane.accessToken, conv.id, m1, "hi bob").status)
        assertEquals(HttpStatusCode.OK, client.send(jane.accessToken, conv.id, m1, "hi bob").status) // replay
        client.send(jane.accessToken, conv.id, Uuid.random().toString(), "you there?")

        val bobInbox = client.get("/api/v1/conversations") { bearerAuth(bob.accessToken) }.body<List<ConversationDto>>()
        assertEquals(2, bobInbox.single().unreadCount)
        assertEquals("you there?", bobInbox.single().lastMessage?.body)

        val history = client.get("/api/v1/conversations/${conv.id}/messages?limit=1") { bearerAuth(bob.accessToken) }.body<Page<MessageDto>>()
        assertEquals(listOf("you there?"), history.items.map { it.body })
        assertNotNull(history.nextCursor)

        client.post("/api/v1/conversations/${conv.id}/read") { bearerAuth(bob.accessToken) }
        assertEquals(0, client.get("/api/v1/conversations") { bearerAuth(bob.accessToken) }.body<List<ConversationDto>>().single().unreadCount)
        assertNotNull(client.get("/api/v1/conversations") { bearerAuth(jane.accessToken) }.body<List<ConversationDto>>().single().peerLastReadAt)

        // Outsiders can't read or write.
        val carol = client.register("carol")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/conversations/${conv.id}/messages") { bearerAuth(carol.accessToken) }.status)
        assertEquals(HttpStatusCode.NotFound, client.send(carol.accessToken, conv.id, Uuid.random().toString(), "x").status)
    }

    @Test
    fun `new messages are pushed to the recipient's open socket`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val conv = client.open(jane.accessToken, "bob")
        val ws = createClient { install(WebSockets) }

        ws.webSocket("/api/v1/ws", request = { bearerAuth(bob.accessToken) }) {
            client.send(jane.accessToken, conv.id, Uuid.random().toString(), "realtime!")
            val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
            val event = AppJson.decodeFromString(RealtimeEvent.serializer(), frame.readText())
            assertEquals("realtime!", (event as RealtimeEvent.MessageNew).message.body)
        }
    }
}
