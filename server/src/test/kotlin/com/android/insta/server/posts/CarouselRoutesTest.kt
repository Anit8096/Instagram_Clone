package com.android.insta.server.posts

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
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CarouselRoutesTest : IntegrationTest() {

    private suspend fun HttpClient.upload(token: String, width: Int, height: Int): MediaDto =
        submitFormWithBinaryData(
            url = "/api/v1/media?kind=post",
            formData = formData {
                append("file", testImage(width, height), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
                })
            },
        ) { bearerAuth(token) }.body()

    private suspend fun HttpClient.create(token: String, id: String, body: CreatePostRequest): HttpResponse =
        put("/api/v1/posts/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.carousel(token: String, id: String, vararg mediaIds: String) =
        create(token, id, CreatePostRequest(caption = "trip", mediaIds = mediaIds.toList()))

    private fun filesOf(mediaId: String) = Files.walk(mediaRoot).use { paths ->
        paths.filter { it.name.startsWith(mediaId) }.map { it.name }.toList().sorted()
    }

    private fun aspect(width: Int, height: Int) = width.toDouble() / height

    @Test
    fun `a carousel keeps its order and later items are cropped to the cover's shape`() = withApp { client ->
        val jane = client.signUp("jane").accessToken
        val cover = client.upload(jane, 1200, 1500) // 4:5 → 1080x1350
        val wide = client.upload(jane, 1600, 1200) // 4:3 → cropped to 4:5
        val square = client.upload(jane, 1000, 1000) // 1:1 → cropped to 4:5
        val postId = Uuid.random().toString()

        val created = client.carousel(jane, postId, cover.id, wide.id, square.id)
        assertEquals(HttpStatusCode.Created, created.status)
        val post = created.body<PostDto>()
        assertEquals(listOf(cover.id, wide.id, square.id), post.media.map { it.id })
        assertTrue(post.media.all { it.type == "photo" })
        // Legacy fields describe the cover.
        assertEquals(cover.url, post.imageUrl)
        assertEquals(1080 to 1350, post.width to post.height)
        post.media.forEach { item ->
            assertTrue(abs(aspect(item.width, item.height) - 0.8) < 0.01, "${item.id} is ${item.width}x${item.height}")
            val image = ImageIO.read(client.get(item.url).body<ByteArray>().inputStream())
            assertEquals(item.width to item.height, image.width to image.height, "served file matches the stored size")
        }

        // The cropped items got a new display file and the old one is gone; the cover is untouched.
        assertTrue(filesOf(wide.id).single { "_full" in it }.contains("_full_"))
        assertEquals(listOf("${cover.id}_full.jpg", "${cover.id}_thumb.jpg"), filesOf(cover.id))

        // A retry is answered from the stored post without cropping again.
        val before = filesOf(wide.id)
        val retry = client.carousel(jane, postId, cover.id, wide.id, square.id)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(post, retry.body<PostDto>())
        assertEquals(before, filesOf(wide.id))

        // Feed and detail carry the same items.
        assertEquals(post.media, client.get("/api/v1/feed") { bearerAuth(jane) }.body<Page<PostDto>>().items.single().media)
        assertEquals(post.media, client.get("/api/v1/posts/$postId") { bearerAuth(jane) }.body<PostDto>().media)
    }

    @Test
    fun `item count, duplicates and request shape are validated`() = withApp { client ->
        val jane = client.signUp("jane").accessToken
        val uploads = (1..11).map { client.upload(jane, 64, 80).id }

        // Each is a 400 with a mediaIds detail.
        suspend fun rejected(body: CreatePostRequest) = client.create(jane, Uuid.random().toString(), body).let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            it.body<ErrorEnvelope>().error.details!!.getValue("mediaIds")
        }

        rejected(CreatePostRequest(mediaIds = emptyList()))
        rejected(CreatePostRequest(mediaIds = uploads)) // 11
        rejected(CreatePostRequest(mediaIds = listOf(uploads[0], uploads[0])))
        rejected(CreatePostRequest(mediaId = uploads[0], mediaIds = listOf(uploads[1])))
        rejected(CreatePostRequest())
        rejected(CreatePostRequest(mediaIds = listOf("nope")))

        val ten = client.create(jane, Uuid.random().toString(), CreatePostRequest(mediaIds = uploads.take(10)))
        assertEquals(HttpStatusCode.Created, ten.status)
        assertEquals(10, ten.body<PostDto>().media.size)
    }

    @Test
    fun `a carousel is all or nothing`() = withApp { client ->
        val jane = client.signUp("jane").accessToken
        val bob = client.signUp("bob").accessToken
        val mine = client.upload(jane, 1200, 1500)
        val wide = client.upload(jane, 1600, 1200)
        val bobs = client.upload(bob, 1200, 1500)
        val used = client.upload(jane, 1200, 1500)
        client.create(jane, Uuid.random().toString(), CreatePostRequest(used.id))

        val foreign = client.carousel(jane, Uuid.random().toString(), mine.id, wide.id, bobs.id)
        assertEquals(HttpStatusCode.UnprocessableEntity, foreign.status)
        assertEquals("INVALID_MEDIA", foreign.body<ErrorEnvelope>().error.code)
        val taken = client.carousel(jane, Uuid.random().toString(), mine.id, wide.id, used.id)
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("MEDIA_IN_USE", taken.body<ErrorEnvelope>().error.code)

        // Nothing was attached or cropped: both items still post, as a fresh carousel.
        assertEquals(listOf("${wide.id}_full.jpg", "${wide.id}_thumb.jpg"), filesOf(wide.id))
        assertEquals(HttpStatusCode.Created, client.carousel(jane, Uuid.random().toString(), mine.id, wide.id).status)
    }

    @Test
    fun `single photo requests still work and deleting a carousel removes every item`() = withApp { client ->
        val jane = client.signUp("jane").accessToken
        val single = client.upload(jane, 1200, 1500)
        val legacy = client.create(jane, Uuid.random().toString(), CreatePostRequest(single.id, "old client")).body<PostDto>()
        assertEquals(listOf(single.id), legacy.media.map { it.id })

        val items = listOf(client.upload(jane, 1200, 1500), client.upload(jane, 1600, 1200), client.upload(jane, 900, 900))
        val postId = Uuid.random().toString()
        client.carousel(jane, postId, *items.map { it.id }.toTypedArray())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/$postId") { bearerAuth(jane) }.status)
        items.forEach { item ->
            assertEquals(HttpStatusCode.NotFound, client.get(item.thumbUrl).status)
            assertEquals(emptyList(), filesOf(item.id))
        }
    }
}
