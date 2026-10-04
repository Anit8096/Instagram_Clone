package com.android.insta.server.posts

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.auth.RegisterRequest
import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.common.Page
import com.android.insta.server.media.MediaDto
import com.android.insta.server.media.testImage
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.users.ProfileDto
import com.android.insta.server.users.UpdateProfileRequest
import com.android.insta.server.users.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostRoutesTest : IntegrationTest() {

    private suspend fun HttpClient.register(name: String): AuthResponse = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(name, "$name@example.com", "correct-horse"))
    }.body()

    private suspend fun HttpClient.upload(token: String, bytes: ByteArray = testImage(1600, 1200), kind: String = "post"): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/v1/media?kind=$kind",
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
                })
            },
        ) { bearerAuth(token) }

    private suspend fun HttpClient.createPost(token: String, id: String, mediaId: String, caption: String = "hello"): HttpResponse =
        put("/api/v1/posts/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreatePostRequest(mediaId, caption))
        }

    @Test
    fun `upload processes the image and serves cacheable variants`() = withApp { client ->
        val jane = client.register("jane")
        val response = client.upload(jane.accessToken)
        assertEquals(HttpStatusCode.Created, response.status)
        val media = response.body<MediaDto>()
        assertEquals(1080 to 810, media.width to media.height)

        val thumb = client.get(media.thumbUrl)
        assertEquals(HttpStatusCode.OK, thumb.status)
        assertEquals(ContentType.Image.JPEG, thumb.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
        assertTrue(thumb.headers[HttpHeaders.CacheControl]!!.contains("immutable"))

        val cached = client.get(media.thumbUrl) { header(HttpHeaders.IfNoneMatch, thumb.headers[HttpHeaders.ETag]) }
        assertEquals(HttpStatusCode.NotModified, cached.status)
    }

    @Test
    fun `upload rejects non images and requires auth`() = withApp { client ->
        val jane = client.register("jane")
        val bad = client.upload(jane.accessToken, "definitely not an image".toByteArray())
        assertEquals(HttpStatusCode.UnsupportedMediaType, bad.status)
        assertEquals("UNSUPPORTED_MEDIA", bad.body<ErrorEnvelope>().error.code)

        assertEquals(HttpStatusCode.Unauthorized, client.upload("not-a-token").status)
    }

    @Test
    fun `creating a post is idempotent per client id`() = withApp { client ->
        val jane = client.register("jane")
        val media = client.upload(jane.accessToken).body<MediaDto>()
        val postId = Uuid.random().toString()

        val first = client.createPost(jane.accessToken, postId, media.id, "  first post  ")
        assertEquals(HttpStatusCode.Created, first.status)
        val post = first.body<PostDto>()
        assertEquals("first post", post.caption)
        assertEquals("jane", post.author.username)

        val retry = client.createPost(jane.accessToken, postId, media.id, "first post")
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(post.id, retry.body<PostDto>().id)
    }

    @Test
    fun `post ownership rules`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val janeMedia = client.upload(jane.accessToken).body<MediaDto>()
        val postId = Uuid.random().toString()

        // Bob can't attach Jane's upload.
        val stolen = client.createPost(bob.accessToken, Uuid.random().toString(), janeMedia.id)
        assertEquals("INVALID_MEDIA", stolen.body<ErrorEnvelope>().error.code)

        client.createPost(jane.accessToken, postId, janeMedia.id)
        // Same media can't back two posts; same id can't be claimed by another user.
        assertEquals("MEDIA_IN_USE", client.createPost(jane.accessToken, Uuid.random().toString(), janeMedia.id).body<ErrorEnvelope>().error.code)
        val bobMedia = client.upload(bob.accessToken).body<MediaDto>()
        assertEquals(HttpStatusCode.Conflict, client.createPost(bob.accessToken, postId, bobMedia.id).status)

        // Only the author deletes; deleting removes the files.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/posts/$postId") { bearerAuth(bob.accessToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/$postId") { bearerAuth(jane.accessToken) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/posts/$postId") { bearerAuth(jane.accessToken) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(janeMedia.thumbUrl).status)
        assertTrue(Files.walk(mediaRoot).noneMatch { it.fileName.toString().startsWith(janeMedia.id) })
    }

    @Test
    fun `caption longer than 2200 characters is rejected`() = withApp { client ->
        val jane = client.register("jane")
        val media = client.upload(jane.accessToken).body<MediaDto>()
        val response = client.createPost(jane.accessToken, Uuid.random().toString(), media.id, "x".repeat(2201))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(setOf("caption"), response.body<ErrorEnvelope>().error.details?.keys)
    }

    @Test
    fun `profile shows counts and pages through posts newest first`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val ids = (1..3).map {
            val media = client.upload(jane.accessToken).body<MediaDto>()
            Uuid.random().toString().also { id -> client.createPost(jane.accessToken, id, media.id, "post $it") }
        }

        val profile = client.get("/api/v1/users/JANE") { bearerAuth(bob.accessToken) }.body<ProfileDto>()
        assertEquals(3, profile.postCount)
        assertEquals(false, profile.isMe)

        val first = client.get("/api/v1/users/jane/posts?limit=2") { bearerAuth(bob.accessToken) }.body<Page<PostDto>>()
        assertEquals(listOf(ids[2], ids[1]), first.items.map { it.id })
        val cursor = assertNotNull(first.nextCursor)
        val second = client.get("/api/v1/users/jane/posts?limit=2&cursor=$cursor") { bearerAuth(bob.accessToken) }.body<Page<PostDto>>()
        assertEquals(listOf(ids[0]), second.items.map { it.id })
        assertNull(second.nextCursor)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users/nobody") { bearerAuth(bob.accessToken) }.status)
    }

    @Test
    fun `patch me updates profile fields and avatar`() = withApp { client ->
        val jane = client.register("jane")
        val avatar = client.upload(jane.accessToken, testImage(500, 500), kind = "avatar").body<MediaDto>()

        val updated = client.patch("/api/v1/me") {
            bearerAuth(jane.accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(displayName = "Jane D", bio = "Hello!", avatarMediaId = avatar.id))
        }.body<UserDto>()
        assertEquals("Jane D", updated.displayName)
        assertEquals("Hello!", updated.bio)
        assertEquals(avatar.thumbUrl, updated.avatarUrl)

        val postMediaAsAvatar = client.upload(jane.accessToken).body<MediaDto>()
        val wrongKind = client.patch("/api/v1/me") {
            bearerAuth(jane.accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(avatarMediaId = postMediaAsAvatar.id))
        }
        assertEquals("INVALID_MEDIA", wrongKind.body<ErrorEnvelope>().error.code)

        val cleared = client.patch("/api/v1/me") {
            bearerAuth(jane.accessToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(removeAvatar = true))
        }.body<UserDto>()
        assertNull(cleared.avatarUrl)
        assertEquals(HttpStatusCode.NotFound, client.get(avatar.thumbUrl).status) // old avatar files cleaned up
    }
}
