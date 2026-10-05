package com.android.insta.server.posts

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.common.Page
import com.android.insta.server.media.MediaDto
import com.android.insta.server.media.testImage
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.signUp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class EngagementRoutesTest : IntegrationTest() {

    private suspend fun HttpClient.register(name: String): String = signUp(name).accessToken

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

    private suspend fun HttpClient.comment(token: String, postId: String, id: String, body: String): HttpResponse =
        put("/api/v1/posts/$postId/comments/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateCommentRequest(body)) }

    @Test
    fun `likes are idempotent, counted, and reported per viewer`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val post = client.newPost(jane)

        repeat(2) { assertEquals(LikeStateDto(true, 1), client.put("/api/v1/posts/$post/like") { bearerAuth(bob) }.body<LikeStateDto>()) }
        assertEquals(LikeStateDto(true, 2), client.put("/api/v1/posts/$post/like") { bearerAuth(jane) }.body<LikeStateDto>())

        assertEquals(true, client.get("/api/v1/posts/$post") { bearerAuth(bob) }.body<PostDto>().likedByMe)
        repeat(2) { assertEquals(LikeStateDto(false, 1), client.delete("/api/v1/posts/$post/like") { bearerAuth(bob) }.body<LikeStateDto>()) }

        val asBob = client.get("/api/v1/posts/$post") { bearerAuth(bob) }.body<PostDto>()
        assertEquals(false to 1, asBob.likedByMe to asBob.likeCount)
        val janeFeed = client.get("/api/v1/feed") { bearerAuth(jane) }.body<Page<PostDto>>()
        assertEquals(true, janeFeed.items.single().likedByMe)

        assertEquals(HttpStatusCode.NotFound, client.put("/api/v1/posts/${Uuid.random()}/like") { bearerAuth(bob) }.status)
    }

    @Test
    fun `comments are idempotent, oldest first, paged and counted`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val post = client.newPost(jane)
        val ids = List(3) { Uuid.random().toString() }

        assertEquals(HttpStatusCode.Created, client.comment(bob, post, ids[0], "  first  ").status)
        assertEquals(HttpStatusCode.OK, client.comment(bob, post, ids[0], "first").status) // replay
        client.comment(jane, post, ids[1], "second")
        client.comment(bob, post, ids[2], "third")
        assertEquals(HttpStatusCode.Conflict, client.comment(jane, post, ids[0], "steal").status)

        val first = client.get("/api/v1/posts/$post/comments?limit=2") { bearerAuth(jane) }.body<Page<CommentDto>>()
        assertEquals(listOf("first", "second"), first.items.map { it.body })
        val rest = client.get("/api/v1/posts/$post/comments?limit=2&cursor=${assertNotNull(first.nextCursor)}") { bearerAuth(jane) }.body<Page<CommentDto>>()
        assertEquals(listOf("third"), rest.items.map { it.body })
        assertEquals(3, client.get("/api/v1/posts/$post") { bearerAuth(jane) }.body<PostDto>().commentCount)

        val empty = client.comment(bob, post, Uuid.random().toString(), "   ")
        assertEquals(setOf("body"), empty.body<ErrorEnvelope>().error.details?.keys)
    }

    @Test
    fun `comment author or post author can delete, others cannot`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val carol = client.register("carol")
        val post = client.newPost(jane)
        val bobComment = Uuid.random().toString()
        val carolComment = Uuid.random().toString()
        client.comment(bob, post, bobComment, "bob says")
        client.comment(carol, post, carolComment, "carol says")

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/posts/$post/comments/$bobComment") { bearerAuth(carol) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/$post/comments/$bobComment") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/$post/comments/$bobComment") { bearerAuth(bob) }.status) // idempotent
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/$post/comments/$carolComment") { bearerAuth(jane) }.status)
        assertEquals(0, client.get("/api/v1/posts/$post") { bearerAuth(jane) }.body<PostDto>().commentCount)
    }
}
