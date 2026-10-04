package com.android.insta.server.notifications

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.auth.RegisterRequest
import com.android.insta.server.chat.ConversationDto
import com.android.insta.server.chat.OpenConversationRequest
import com.android.insta.server.chat.RealtimeEvent
import com.android.insta.server.chat.SendMessageRequest
import com.android.insta.server.common.AppJson
import com.android.insta.server.common.Page
import com.android.insta.server.media.MediaDto
import com.android.insta.server.media.testImage
import com.android.insta.server.posts.CreateCommentRequest
import com.android.insta.server.posts.CreatePostRequest
import com.android.insta.server.support.IntegrationTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Records pushes; reports `dead-token` as unregistered so pruning can be asserted. */
class RecordingPushSender : PushSender {
    val sent = Channel<Pair<List<String>, PushMessage>>(Channel.UNLIMITED)
    override suspend fun send(tokens: List<String>, message: PushMessage): Set<String> {
        sent.send(tokens to message)
        return tokens.filter { it == "dead-token" }.toSet()
    }
}

class NotificationRoutesTest : IntegrationTest() {

    private val push = RecordingPushSender()
    private val pushModule = module { single<PushSender> { push } }

    private suspend fun HttpClient.register(name: String): String = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(name, "$name@example.com", "correct-horse"))
    }.body<AuthResponse>().accessToken

    private suspend fun HttpClient.newPost(token: String): String {
        val media = submitFormWithBinaryData("/api/v1/media", formData {
            append("file", testImage(300, 300), Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"p.jpg\"")
            })
        }) { bearerAuth(token) }.body<MediaDto>()
        val id = Uuid.random().toString()
        put("/api/v1/posts/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreatePostRequest(media.id, "p")) }
        return id
    }

    private suspend fun HttpClient.activity(token: String) = get("/api/v1/notifications") { bearerAuth(token) }.body<Page<NotificationDto>>().items

    private suspend fun HttpClient.unread(token: String) = get("/api/v1/notifications/unread-count") { bearerAuth(token) }.body<UnreadCountDto>().count

    @Test
    fun `likes, comments and follows notify the recipient once, never themselves`() = withApp(extraModules = listOf(pushModule)) { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val post = client.newPost(jane)

        // like → unlike → like collapses to one row; jane liking her own post adds nothing.
        client.put("/api/v1/posts/$post/like") { bearerAuth(bob) }
        client.delete("/api/v1/posts/$post/like") { bearerAuth(bob) }
        assertEquals(emptyList(), client.activity(jane))
        repeat(2) { client.put("/api/v1/posts/$post/like") { bearerAuth(bob) } }
        client.put("/api/v1/posts/$post/like") { bearerAuth(jane) }

        val commentId = Uuid.random().toString()
        repeat(2) { // replay of the same queued comment
            client.put("/api/v1/posts/$post/comments/$commentId") { bearerAuth(bob); contentType(ContentType.Application.Json); setBody(CreateCommentRequest("nice")) }
        }
        repeat(2) { client.put("/api/v1/users/jane/follow") { bearerAuth(bob) } }

        val items = client.activity(jane)
        assertEquals(listOf("follow", "comment", "like"), items.map { it.type })
        assertEquals("bob", items.first().actor.username)
        assertEquals("nice", items[1].commentBody)
        assertEquals(post, items[2].postId)
        assertEquals(3, client.unread(jane))
        assertEquals(emptyList(), client.activity(bob))

        // Unfollow removes the follow; deleting the comment cascades its notification.
        client.delete("/api/v1/users/jane/follow") { bearerAuth(bob) }
        client.delete("/api/v1/posts/$post/comments/$commentId") { bearerAuth(bob) }
        assertEquals(listOf("like"), client.activity(jane).map { it.type })

        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/notifications/read") { bearerAuth(jane) }.status)
        assertEquals(0, client.unread(jane))
        assertEquals(true, client.activity(jane).single().read)
    }

    @Test
    fun `offline recipients get a push, dead tokens are pruned, online ones get the socket event`() = withApp(extraModules = listOf(pushModule)) { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        listOf("jane-phone", "dead-token").forEach {
            assertEquals(HttpStatusCode.NoContent, client.put("/api/v1/me/devices/$it") { bearerAuth(jane) }.status)
        }

        client.put("/api/v1/users/jane/follow") { bearerAuth(bob) }
        val (tokens, message) = withTimeout(5_000) { push.sent.receive() }
        assertEquals(setOf("jane-phone", "dead-token"), tokens.toSet())
        assertEquals("insta://user/bob", message.link)
        assertEquals("bob started following you.", message.body)

        // The dead token was dropped after the first send; DMs push with a chat link.
        val conv = client.post("/api/v1/conversations") {
            bearerAuth(bob); contentType(ContentType.Application.Json); setBody(OpenConversationRequest("jane"))
        }.body<ConversationDto>()
        client.put("/api/v1/conversations/${conv.id}/messages/${Uuid.random()}") {
            bearerAuth(bob); contentType(ContentType.Application.Json); setBody(SendMessageRequest("hey jane"))
        }
        val (dmTokens, dm) = withTimeout(5_000) { push.sent.receive() }
        assertEquals(listOf("jane-phone"), dmTokens)
        assertEquals("insta://chat/bob" to "hey jane", dm.link to dm.body)

        // While jane has a live socket she gets `notification.new` with the badge count and no push.
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/ws", request = { bearerAuth(jane) }) {
            val post = client.newPost(jane)
            client.put("/api/v1/posts/$post/like") { bearerAuth(bob) }
            val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
            val event = AppJson.decodeFromString(RealtimeEvent.serializer(), frame.readText()) as RealtimeEvent.NotificationNew
            assertEquals("like" to 2L, event.notification.type to event.unreadCount)
        }
        assertNull(withTimeoutOrNull(500) { push.sent.receive() })
    }
}
