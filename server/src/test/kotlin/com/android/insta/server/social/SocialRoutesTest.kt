package com.android.insta.server.social

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.common.Page
import com.android.insta.server.media.MediaDto
import com.android.insta.server.media.testImage
import com.android.insta.server.posts.CreatePostRequest
import com.android.insta.server.posts.PostDto
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.signUp
import com.android.insta.server.users.ProfileDto
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
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SocialRoutesTest : IntegrationTest() {

    private suspend fun HttpClient.register(name: String, displayName: String? = null): String = signUp(name, displayName.orEmpty()).accessToken

    private suspend fun HttpClient.post(token: String, caption: String): String {
        val media = submitFormWithBinaryData("/api/v1/media", formData {
            append("file", testImage(400, 400), Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"p.jpg\"")
            })
        }) { bearerAuth(token) }.body<MediaDto>()
        val id = Uuid.random().toString()
        put("/api/v1/posts/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreatePostRequest(media.id, caption))
        }
        return id
    }

    private suspend inline fun <reified T> HttpClient.getAs(token: String, path: String): T = get(path) { bearerAuth(token) }.body()

    @Test
    fun `follow is idempotent, counted, and self-follow is rejected`() = withApp { client ->
        val jane = client.register("jane")
        client.register("bob")

        repeat(2) {
            val state = client.put("/api/v1/users/bob/follow") { bearerAuth(jane) }.body<FollowStateDto>()
            assertEquals(FollowStateDto(isFollowing = true, followerCount = 1), state)
        }
        val bob = client.getAs<ProfileDto>(jane, "/api/v1/users/bob")
        assertEquals(1, bob.followerCount)
        assertEquals(true, bob.isFollowing)
        assertEquals(1, client.getAs<ProfileDto>(jane, "/api/v1/users/jane").followingCount)

        val self = client.put("/api/v1/users/jane/follow") { bearerAuth(jane) }
        assertEquals("CANNOT_FOLLOW_SELF", self.body<ErrorEnvelope>().error.code)

        repeat(2) {
            assertEquals(FollowStateDto(false, 0), client.delete("/api/v1/users/bob/follow") { bearerAuth(jane) }.body<FollowStateDto>())
        }
        assertEquals(HttpStatusCode.NotFound, client.put("/api/v1/users/nobody/follow") { bearerAuth(jane) }.status)
    }

    @Test
    fun `feed has own and followed posts newest first, explore has the rest`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val carol = client.register("carol")
        val janePost = client.post(jane, "jane 1")
        val bobPost = client.post(bob, "bob 1")
        val carolPost = client.post(carol, "carol 1")

        // Before following: feed = own posts only; explore = everyone else.
        assertEquals(listOf(janePost), client.getAs<Page<PostDto>>(jane, "/api/v1/feed").items.map { it.id })
        assertEquals(listOf(carolPost, bobPost), client.getAs<Page<PostDto>>(jane, "/api/v1/explore").items.map { it.id })

        client.put("/api/v1/users/bob/follow") { bearerAuth(jane) }
        assertEquals(listOf(bobPost, janePost), client.getAs<Page<PostDto>>(jane, "/api/v1/feed").items.map { it.id })
        assertEquals(listOf(carolPost), client.getAs<Page<PostDto>>(jane, "/api/v1/explore").items.map { it.id })

        // Paging works on the feed.
        val first = client.getAs<Page<PostDto>>(jane, "/api/v1/feed?limit=1")
        val next = client.getAs<Page<PostDto>>(jane, "/api/v1/feed?limit=1&cursor=${assertNotNull(first.nextCursor)}")
        assertEquals(listOf(janePost), next.items.map { it.id })
        assertNull(next.nextCursor)

        client.delete("/api/v1/users/bob/follow") { bearerAuth(jane) }
        assertEquals(listOf(janePost), client.getAs<Page<PostDto>>(jane, "/api/v1/feed").items.map { it.id })
    }

    @Test
    fun `search ranks exact and prefix username matches first and flags who you follow`() = withApp { client ->
        val jane = client.register("jane")
        client.register("sam", displayName = "Sam Lee")
        client.register("samantha")
        client.register("bob_sammy")
        client.register("lee.r", displayName = "Robert Lee")
        client.put("/api/v1/users/samantha/follow") { bearerAuth(jane) }

        val results = client.getAs<List<UserSummaryDto>>(jane, "/api/v1/search/users?q=%40Sam")
        assertEquals(listOf("sam", "samantha", "bob_sammy"), results.map { it.username })
        assertEquals(listOf(false, true, false), results.map { it.isFollowing })

        assertEquals(setOf("sam", "lee.r"), client.getAs<List<UserSummaryDto>>(jane, "/api/v1/search/users?q=lee").map { it.username }.toSet())
        assertEquals(emptyList(), client.getAs<List<UserSummaryDto>>(jane, "/api/v1/search/users?q=%25"))
        assertEquals(emptyList(), client.getAs<List<UserSummaryDto>>(jane, "/api/v1/search/users?q=%20"))
    }

    @Test
    fun `followers and following lists page and show the viewer's follow state`() = withApp { client ->
        val bob = client.register("bob")
        val fans = listOf("ann", "ben", "cat").map { name -> name to client.register(name) }
        fans.forEach { (_, token) -> client.put("/api/v1/users/bob/follow") { bearerAuth(token) } }
        client.put("/api/v1/users/ann/follow") { bearerAuth(bob) }

        val first = client.getAs<Page<UserSummaryDto>>(bob, "/api/v1/users/bob/followers?limit=2")
        assertEquals(listOf("cat", "ben"), first.items.map { it.username })
        val rest = client.getAs<Page<UserSummaryDto>>(bob, "/api/v1/users/bob/followers?limit=2&cursor=${assertNotNull(first.nextCursor)}")
        assertEquals(listOf("ann"), rest.items.map { it.username })
        assertEquals(true, rest.items.single().isFollowing) // bob follows ann back

        assertEquals(listOf("ann"), client.getAs<Page<UserSummaryDto>>(bob, "/api/v1/users/bob/following").items.map { it.username })
    }
}
