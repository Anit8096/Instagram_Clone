package com.android.insta.server.auth

import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.support.FakeGoogleVerifier
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.json
import com.android.insta.server.support.nextTestPhone
import com.android.insta.server.support.signUp
import com.android.insta.server.users.MeDto
import com.android.insta.server.users.ProfileDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A clock tests can move forward (code expiry, resend cooldown). Starts at the real time because access tokens it
 * signs are still checked against the real clock.
 */
class MutableClock(var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
}

class AuthRoutesTest : IntegrationTest() {

    private suspend fun io.ktor.client.HttpClient.google(token: String) =
        json("/api/v1/auth/google", GoogleLoginRequest(token)).body<GoogleAuthResult>()

    private suspend fun io.ktor.client.HttpClient.phoneOtp(phone: String) = json("/api/v1/auth/phone/otp", PhoneOtpRequest(phone))

    @Test
    fun `new google user onboards with a verified phone, then google and phone both sign in`() {
        val google = FakeGoogleVerifier(mapOf("sam" to GoogleIdentity("google-sub-1", "sam.smith@gmail.com", emailVerified = true, name = "Sam Smith")))
        withApp(google = google) { client ->
            val onboarding = client.google("sam") as GoogleAuthResult.NeedsOnboarding
            assertEquals("sam.smith", onboarding.suggestedUsername)
            assertEquals("Sam Smith", onboarding.displayName)

            val phone = "+91 98765 43210"
            val challenge = client.json("/api/v1/auth/onboarding/otp", OnboardingOtpRequest(onboarding.onboardingToken, phone)).body<OtpChallengeDto>()
            assertEquals("+91 ••••••3210", challenge.sentTo)
            val created = client.json(
                "/api/v1/auth/onboarding/complete",
                CompleteOnboardingRequest(onboarding.onboardingToken, challenge.challengeId, challenge.devCode!!, " Sam.Smith ", "Sam"),
            )
            assertEquals(HttpStatusCode.Created, created.status)
            val auth = created.body<AuthResponse>()
            assertTrue(auth.isNewUser)
            assertEquals("sam.smith" to "+919876543210", auth.user.username to auth.user.phone)
            assertEquals("sam.smith@gmail.com", auth.user.email)

            // The same Google account now signs straight in…
            val again = client.google("sam") as GoogleAuthResult.SignedIn
            assertEquals(auth.user.id, again.auth.user.id)
            assertFalse(again.auth.isNewUser)

            // …and so does the verified phone.
            val login = client.phoneOtp("+919876543210").body<OtpChallengeDto>()
            val viaPhone = client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(login.challengeId, login.devCode!!)).body<AuthResponse>()
            assertEquals(auth.user.id, viaPhone.user.id)

            // The phone is private: the account owner sees it, the public profile doesn't.
            assertEquals("+919876543210", client.get("/api/v1/me") { bearerAuth(auth.accessToken) }.body<MeDto>().phone)
            val publicJson = client.get("/api/v1/users/sam.smith") { bearerAuth(auth.accessToken) }
            assertFalse("phone" in publicJson.body<String>())
            assertEquals("sam.smith", publicJson.body<ProfileDto>().user.username)
        }
    }

    @Test
    fun `phone sign-in only works for linked numbers`() = withApp { client ->
        val unknown = client.phoneOtp(nextTestPhone())
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("NO_LINKED_ACCOUNT", unknown.body<ErrorEnvelope>().error.code)

        val invalid = client.phoneOtp("12345")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(setOf("phone"), invalid.body<ErrorEnvelope>().error.details?.keys)
    }

    @Test
    fun `onboarding rejects taken phones and usernames and bad tokens`() = withApp { client ->
        val phone = nextTestPhone()
        client.signUp("jane", phone = phone)

        val bob = client.google("google:bob") as GoogleAuthResult.NeedsOnboarding
        val taken = client.json("/api/v1/auth/onboarding/otp", OnboardingOtpRequest(bob.onboardingToken, phone))
        assertEquals("PHONE_IN_USE", taken.body<ErrorEnvelope>().error.code)

        val challenge = client.json("/api/v1/auth/onboarding/otp", OnboardingOtpRequest(bob.onboardingToken, nextTestPhone())).body<OtpChallengeDto>()
        val sameName = client.json("/api/v1/auth/onboarding/complete", CompleteOnboardingRequest(bob.onboardingToken, challenge.challengeId, challenge.devCode!!, "jane"))
        assertEquals(HttpStatusCode.Conflict, sameName.status)
        assertEquals("USERNAME_TAKEN", sameName.body<ErrorEnvelope>().error.code)
        val badName = client.json("/api/v1/auth/onboarding/complete", CompleteOnboardingRequest(bob.onboardingToken, challenge.challengeId, challenge.devCode, "x!"))
        assertEquals(setOf("username"), badName.body<ErrorEnvelope>().error.details?.keys)

        val forged = client.json("/api/v1/auth/onboarding/otp", OnboardingOtpRequest("not-a-token", nextTestPhone()))
        assertEquals("INVALID_ONBOARDING_TOKEN", forged.body<ErrorEnvelope>().error.code)

        // An onboarding token is not an access token.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(bob.onboardingToken) }.status)
    }

    @Test
    fun `codes are single use, limited in attempts, bound to their purpose, and expire`() {
        val clock = MutableClock()
        withApp(config = testConfig().copy(otp = realOtp), extraModules = listOf(module { single<Clock> { clock } })) { client ->
            val phone = nextTestPhone()
            val jane = client.signUp("jane", phone = phone)

            clock.advanceSeconds(60)
            val first = client.phoneOtp(phone).body<OtpChallengeDto>()
            val wrong = client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(first.challengeId, "000000".takeIf { it != first.devCode } ?: "111111"))
            assertEquals("OTP_INVALID", wrong.body<ErrorEnvelope>().error.code)
            assertEquals("4", wrong.body<ErrorEnvelope>().error.details?.get("attemptsLeft"))
            assertEquals(HttpStatusCode.OK, client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(first.challengeId, first.devCode!!)).status)
            assertEquals("OTP_USED", client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(first.challengeId, first.devCode)).body<ErrorEnvelope>().error.code)

            // Resend cooldown, then a fresh code that expires after 5 minutes.
            val tooSoon = client.phoneOtp(phone)
            assertEquals(HttpStatusCode.TooManyRequests, tooSoon.status)
            assertEquals("OTP_RATE_LIMITED", tooSoon.body<ErrorEnvelope>().error.code)
            clock.advanceSeconds(31)
            val second = client.phoneOtp(phone).body<OtpChallengeDto>()
            clock.advanceSeconds(301)
            assertEquals("OTP_EXPIRED", client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(second.challengeId, second.devCode!!)).body<ErrorEnvelope>().error.code)

            // Five wrong guesses kill a code, even if the sixth is right.
            clock.advanceSeconds(31)
            val third = client.phoneOtp(phone).body<OtpChallengeDto>()
            val bad = if (third.devCode == "999999") "888888" else "999999"
            repeat(5) { client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(third.challengeId, bad)) }
            assertEquals("OTP_TOO_MANY_ATTEMPTS", client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(third.challengeId, third.devCode!!)).body<ErrorEnvelope>().error.code)

            // A delete-account code can't sign anyone in.
            clock.advanceSeconds(31)
            val deleteCode = client.post("/api/v1/me/delete/otp") { bearerAuth(jane.accessToken) }.body<OtpChallengeDto>()
            assertEquals("OTP_INVALID", client.json("/api/v1/auth/phone/verify", VerifyOtpRequest(deleteCode.challengeId, deleteCode.devCode!!)).body<ErrorEnvelope>().error.code)
        }
    }

    @Test
    fun `at most five codes per number per hour`() {
        val clock = MutableClock()
        withApp(config = testConfig().copy(otp = realOtp), extraModules = listOf(module { single<Clock> { clock } })) { client ->
            val phone = nextTestPhone()
            client.signUp("jane", phone = phone) // onboarding sent code #1 for this number
            repeat(4) {
                clock.advanceSeconds(31)
                assertEquals(HttpStatusCode.OK, client.phoneOtp(phone).status)
            }
            clock.advanceSeconds(31)
            assertEquals(HttpStatusCode.TooManyRequests, client.phoneOtp(phone).status)
            clock.advanceSeconds(3600)
            assertEquals(HttpStatusCode.OK, client.phoneOtp(phone).status)
        }
    }

    @Test
    fun `codes are only echoed in dev mode`() = withApp(config = testConfig().copy(otp = com.android.insta.server.config.OtpConfig(devEcho = false))) { client ->
        val onboarding = client.google("google:jane") as GoogleAuthResult.NeedsOnboarding
        val challenge = client.json("/api/v1/auth/onboarding/otp", OnboardingOtpRequest(onboarding.onboardingToken, nextTestPhone())).body<OtpChallengeDto>()
        assertNull(challenge.devCode)
    }

    @Test
    fun `me requires a valid access token`() = withApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me").status)
        val bad = client.get("/api/v1/me") { bearerAuth("not-a-jwt") }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("UNAUTHORIZED", bad.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `refresh rotates and reusing an old token revokes the whole session`() = withApp { client ->
        val first = client.signUp("jane")

        val second = client.json("/api/v1/auth/refresh", RefreshRequest(first.refreshToken))
        assertEquals(HttpStatusCode.OK, second.status)
        val rotated = second.body<AuthResponse>()
        assertNotEquals(first.refreshToken, rotated.refreshToken)

        // Replaying the first token looks like theft: rejected, and its successor dies too.
        assertEquals(HttpStatusCode.Unauthorized, client.json("/api/v1/auth/refresh", RefreshRequest(first.refreshToken)).status)
        val successor = client.json("/api/v1/auth/refresh", RefreshRequest(rotated.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, successor.status)
        assertEquals("INVALID_REFRESH_TOKEN", successor.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `sessions are independent and logout revokes only its own`() = withApp { client ->
        val phone = client.signUp("jane")
        val tablet = (client.google("google:jane") as GoogleAuthResult.SignedIn).auth

        assertEquals(HttpStatusCode.NoContent, client.json("/api/v1/auth/logout", RefreshRequest(phone.refreshToken)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.json("/api/v1/auth/refresh", RefreshRequest(phone.refreshToken)).status)
        assertEquals(HttpStatusCode.OK, client.json("/api/v1/auth/refresh", RefreshRequest(tablet.refreshToken)).status)
    }

    @Test
    fun `password endpoints are gone and forged google tokens are rejected`() = withApp { client ->
        assertEquals(HttpStatusCode.NotFound, client.json("/api/v1/auth/login", mapOf("login" to "x", "password" to "y")).status)
        assertEquals(HttpStatusCode.NotFound, client.json("/api/v1/auth/register", mapOf("username" to "x")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.json("/api/v1/auth/google", GoogleLoginRequest("forged")).status)
    }

    @Test
    fun `auth endpoints are rate limited`() = withApp(config = testConfig(authRequestsPerMinute = 3)) { client ->
        repeat(3) { client.phoneOtp(nextTestPhone()) }
        val limited = client.phoneOtp(nextTestPhone())
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", limited.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `malformed body is a 400 envelope`() = withApp { client ->
        val response = client.post("/api/v1/auth/phone/otp") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.body<ErrorEnvelope>().error.code)
    }

    @Test
    fun `health reports database up and swagger is served`() = withApp { client ->
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/docs").status)
    }
}
