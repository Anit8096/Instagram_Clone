package com.android.insta.server.users

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.auth.GoogleIdentity
import com.android.insta.server.auth.GoogleLoginRequest
import com.android.insta.server.auth.LoginRequest
import com.android.insta.server.auth.RefreshRequest
import com.android.insta.server.auth.RegisterRequest
import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.media.MediaDto
import com.android.insta.server.media.testImage
import com.android.insta.server.posts.CreateCommentRequest
import com.android.insta.server.posts.CreatePostRequest
import com.android.insta.server.posts.PostDto
import com.android.insta.server.support.FakeGoogleVerifier
import com.android.insta.server.support.IntegrationTest
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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AccountDeletionTest : IntegrationTest() {

    private suspend fun HttpClient.register(name: String): AuthResponse = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(name, "$name@example.com", "correct-horse"))
    }.body()

    private suspend fun HttpClient.newPost(token: String): Pair<String, MediaDto> {
        val media = submitFormWithBinaryData("/api/v1/media", formData {
            append("file", testImage(300, 300), Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"p.jpg\"")
            })
        }) { bearerAuth(token) }.body<MediaDto>()
        val id = Uuid.random().toString()
        put("/api/v1/posts/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreatePostRequest(media.id, "p")) }
        return id to media
    }

    private suspend fun HttpClient.deleteAccount(token: String, body: DeleteAccountRequest) = delete("/api/v1/me") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun `deleting an account needs the password, removes everything and fixes other people's counts`() = withApp { client ->
        val jane = client.register("jane")
        val bob = client.register("bob")
        val (janePost, _) = client.newPost(jane.accessToken)
        val (_, bobMedia) = client.newPost(bob.accessToken)
        client.put("/api/v1/posts/$janePost/like") { bearerAuth(bob.accessToken) }
        repeat(2) {
            client.put("/api/v1/posts/$janePost/comments/${Uuid.random()}") {
                bearerAuth(bob.accessToken); contentType(ContentType.Application.Json); setBody(CreateCommentRequest("hi"))
            }
        }
        client.put("/api/v1/users/jane/follow") { bearerAuth(bob.accessToken) }

        val wrong = client.deleteAccount(bob.accessToken, DeleteAccountRequest(password = "nope-nope"))
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("REAUTH_FAILED", wrong.body<ErrorEnvelope>().error.code)
        assertEquals(HttpStatusCode.Forbidden, client.deleteAccount(bob.accessToken, DeleteAccountRequest()).status)

        assertEquals(HttpStatusCode.NoContent, client.deleteAccount(bob.accessToken, DeleteAccountRequest(password = "correct-horse")).status)

        val post = client.get("/api/v1/posts/$janePost") { bearerAuth(jane.accessToken) }.body<PostDto>()
        assertEquals(0 to 0, post.likeCount to post.commentCount)
        assertEquals(0L, client.get("/api/v1/users/jane") { bearerAuth(jane.accessToken) }.body<ProfileDto>().followerCount)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users/bob") { bearerAuth(jane.accessToken) }.status)
        assertTrue(Files.walk(mediaRoot).noneMatch { it.fileName.toString().startsWith(bobMedia.id) }, "media files removed")

        // Every session is gone and the credentials no longer work.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json); setBody(RefreshRequest(bob.refreshToken))
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json); setBody(LoginRequest("bob", "correct-horse"))
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(bob.accessToken) }.status)
    }

    @Test
    fun `google-only accounts confirm with a fresh google token for the same account`() = withApp(
        google = FakeGoogleVerifier(mapOf(
            "sam-token" to GoogleIdentity("sub-sam", "sam@gmail.com", emailVerified = true, name = "Sam"),
            "other-token" to GoogleIdentity("sub-other", "other@gmail.com", emailVerified = true, name = "Other"),
        )),
    ) { client ->
        val sam = client.post("/api/v1/auth/google") { contentType(ContentType.Application.Json); setBody(GoogleLoginRequest("sam-token")) }.body<AuthResponse>()

        assertEquals(HttpStatusCode.Forbidden, client.deleteAccount(sam.accessToken, DeleteAccountRequest(googleIdToken = "other-token")).status)
        assertEquals(HttpStatusCode.Forbidden, client.deleteAccount(sam.accessToken, DeleteAccountRequest(password = "anything-at-all")).status)
        assertEquals(HttpStatusCode.NoContent, client.deleteAccount(sam.accessToken, DeleteAccountRequest(googleIdToken = "sam-token")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(sam.accessToken) }.status)
    }
}
