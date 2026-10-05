package com.android.insta.server.users

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.auth.GoogleAuthResult
import com.android.insta.server.auth.GoogleLoginRequest
import com.android.insta.server.auth.OtpChallengeDto
import com.android.insta.server.auth.PhoneOtpRequest
import com.android.insta.server.auth.RefreshRequest
import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.media.MediaDto
import com.android.insta.server.media.testImage
import com.android.insta.server.posts.CreateCommentRequest
import com.android.insta.server.posts.CreatePostRequest
import com.android.insta.server.posts.PostDto
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.json
import com.android.insta.server.support.nextTestPhone
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

    private suspend fun HttpClient.deleteCode(token: String) = post("/api/v1/me/delete/otp") { bearerAuth(token) }.body<OtpChallengeDto>()

    @Test
    fun `deleting an account needs a code from its phone, removes everything and fixes other people's counts`() = withApp { client ->
        val jane = client.signUp("jane")
        val bobPhone = nextTestPhone()
        val bob = client.signUp("bob", phone = bobPhone)
        val (janePost, _) = client.newPost(jane.accessToken)
        val (_, bobMedia) = client.newPost(bob.accessToken)
        client.put("/api/v1/posts/$janePost/like") { bearerAuth(bob.accessToken) }
        repeat(2) {
            client.put("/api/v1/posts/$janePost/comments/${Uuid.random()}") {
                bearerAuth(bob.accessToken); contentType(ContentType.Application.Json); setBody(CreateCommentRequest("hi"))
            }
        }
        client.put("/api/v1/users/jane/follow") { bearerAuth(bob.accessToken) }

        assertEquals(HttpStatusCode.Forbidden, client.deleteAccount(bob.accessToken, DeleteAccountRequest()).status)
        val code = client.deleteCode(bob.accessToken)
        assertEquals("+91 ••••••${bobPhone.takeLast(4)}", code.sentTo)
        val wrong = client.deleteAccount(bob.accessToken, DeleteAccountRequest(code.challengeId, if (code.devCode == "000000") "111111" else "000000"))
        assertEquals("OTP_INVALID", wrong.body<ErrorEnvelope>().error.code)
        // Jane can't use Bob's code (or vice versa): codes are bound to their account.
        assertEquals("OTP_INVALID", client.deleteAccount(jane.accessToken, DeleteAccountRequest(code.challengeId, code.devCode)).body<ErrorEnvelope>().error.code)

        assertEquals(HttpStatusCode.NoContent, client.deleteAccount(bob.accessToken, DeleteAccountRequest(code.challengeId, code.devCode)).status)

        val post = client.get("/api/v1/posts/$janePost") { bearerAuth(jane.accessToken) }.body<PostDto>()
        assertEquals(0 to 0, post.likeCount to post.commentCount)
        assertEquals(0L, client.get("/api/v1/users/jane") { bearerAuth(jane.accessToken) }.body<ProfileDto>().followerCount)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users/bob") { bearerAuth(jane.accessToken) }.status)
        assertTrue(Files.walk(mediaRoot).noneMatch { it.fileName.toString().startsWith(bobMedia.id) }, "media files removed")

        // Every session is gone and the phone is no longer linked.
        assertEquals(HttpStatusCode.Unauthorized, client.json("/api/v1/auth/refresh", RefreshRequest(bob.refreshToken)).status)
        assertEquals("NO_LINKED_ACCOUNT", client.json("/api/v1/auth/phone/otp", PhoneOtpRequest(bobPhone)).body<ErrorEnvelope>().error.code)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(bob.accessToken) }.status)
        // Signing in with the same Google account again starts a fresh onboarding.
        assertTrue(client.json("/api/v1/auth/google", GoogleLoginRequest("google:bob")).body<GoogleAuthResult>() is GoogleAuthResult.NeedsOnboarding)
    }

    @Test
    fun `google confirmation must be the account's own google identity`() = withApp { client ->
        val sam = client.signUp("sam")
        client.signUp("other")

        assertEquals(HttpStatusCode.Forbidden, client.deleteAccount(sam.accessToken, DeleteAccountRequest(googleIdToken = "google:other")).status)
        assertEquals(HttpStatusCode.Forbidden, client.deleteAccount(sam.accessToken, DeleteAccountRequest(googleIdToken = "forged")).status)
        assertEquals(HttpStatusCode.NoContent, client.deleteAccount(sam.accessToken, DeleteAccountRequest(googleIdToken = "google:sam")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(sam.accessToken) }.status)
    }
}

class ChangePhoneTest : IntegrationTest() {

    @Test
    fun `phone changes only after the new number is verified`() = withApp { client ->
        val oldPhone = nextTestPhone()
        val jane = client.signUp("jane", phone = oldPhone)
        val takenPhone = nextTestPhone()
        client.signUp("bob", phone = takenPhone)
        suspend fun request(phone: String) = client.post("/api/v1/me/phone/otp") {
            bearerAuth(jane.accessToken); contentType(ContentType.Application.Json); setBody(PhoneOtpRequest(phone))
        }

        assertEquals("PHONE_IN_USE", request(takenPhone).body<ErrorEnvelope>().error.code)
        assertEquals(setOf("phone"), request(oldPhone).body<ErrorEnvelope>().error.details?.keys) // already yours

        val newPhone = nextTestPhone()
        val challenge = request(newPhone).body<OtpChallengeDto>()
        val updated = client.put("/api/v1/me/phone") {
            bearerAuth(jane.accessToken); contentType(ContentType.Application.Json)
            setBody(com.android.insta.server.auth.VerifyOtpRequest(challenge.challengeId, challenge.devCode!!))
        }.body<MeDto>()
        assertEquals(newPhone, updated.phone)

        // The old number no longer signs in; the new one does.
        assertEquals("NO_LINKED_ACCOUNT", client.json("/api/v1/auth/phone/otp", PhoneOtpRequest(oldPhone)).body<ErrorEnvelope>().error.code)
        val login = client.json("/api/v1/auth/phone/otp", PhoneOtpRequest(newPhone)).body<OtpChallengeDto>()
        val auth = client.json("/api/v1/auth/phone/verify", com.android.insta.server.auth.VerifyOtpRequest(login.challengeId, login.devCode!!)).body<AuthResponse>()
        assertEquals(jane.user.id, auth.user.id)
    }
}
