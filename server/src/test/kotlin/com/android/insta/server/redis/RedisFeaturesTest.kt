package com.android.insta.server.redis

import com.android.insta.server.auth.MutableClock
import com.android.insta.server.auth.PhoneOtpRequest
import com.android.insta.server.auth.RefreshRequest
import com.android.insta.server.common.ErrorEnvelope
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.TestRedis
import com.android.insta.server.support.json
import com.android.insta.server.support.nextTestPhone
import com.android.insta.server.support.signUp
import com.android.insta.server.users.ProfileDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.lettuce.core.SetArgs
import org.koin.dsl.module
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedisFeaturesTest : IntegrationTest() {

    private suspend fun HttpClient.profile(token: String, username: String) =
        get("/api/v1/users/$username") { bearerAuth(token) }.body<ProfileDto>()

    @Test
    fun `auth endpoints share one budget per IP and a 429 says when to retry`() {
        // Pinned clock: the whole test stays inside one fixed window.
        val clock = MutableClock(Instant.parse("2026-10-06T12:00:10Z"))
        withApp(config = testConfig(authRequestsPerMinute = 2), extraModules = listOf(module { single<Clock> { clock } })) { client ->
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.json("/api/v1/auth/refresh", RefreshRequest("nope")).status) }

            val limited = client.json("/api/v1/auth/phone/otp", PhoneOtpRequest(nextTestPhone()))
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("RATE_LIMITED", limited.body<ErrorEnvelope>().error.code)
            assertEquals("50", limited.headers[HttpHeaders.RetryAfter]) // window ends at 12:01:00

            clock.advanceSeconds(50) // next window
            assertEquals(HttpStatusCode.Unauthorized, client.json("/api/v1/auth/refresh", RefreshRequest("nope")).status)
        }
    }

    @Test
    fun `while Redis is frozen OTP sends fail closed but the rest of the API keeps working`() = withApp { client ->
        val phone = nextTestPhone()
        val jane = client.signUp("jane", phone = phone).accessToken

        TestRedis.paused {
            // Not 429 (the IP limit fails open) and not 500: a deliberate 503 from the OTP throttle.
            val otp = client.json("/api/v1/auth/phone/otp", PhoneOtpRequest(phone))
            assertEquals(HttpStatusCode.ServiceUnavailable, otp.status)
            assertEquals("OTP_UNAVAILABLE", otp.body<ErrorEnvelope>().error.code)

            // Cache misses fall through to Postgres.
            assertEquals(0, client.profile(jane, "jane").postCount)
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }

        assertEquals(HttpStatusCode.OK, client.json("/api/v1/auth/phone/otp", PhoneOtpRequest(phone)).status)
    }

    @Test
    fun `profile counts are served from the cache and follows invalidate both sides`() = withApp { client ->
        val jane = client.signUp("jane").accessToken
        val bob = client.signUp("bob")

        assertEquals(0, client.profile(jane, "bob").followerCount)
        assertEquals(0, client.profile(jane, "jane").followingCount)

        // Proof the cache is read: a planted value shows up.
        val redis = koin<Redis>()
        val key = RedisKeys.counts(bob.user.id)
        assertNotNull(redis.commands().get(key))
        redis.commands().set(key, """{"posts":0,"followers":42,"following":0}""", SetArgs.Builder.ex(60))
        assertEquals(42, client.profile(jane, "bob").followerCount)

        client.put("/api/v1/users/bob/follow") { bearerAuth(jane) }

        assertEquals(1, client.profile(jane, "bob").followerCount)
        assertEquals(1, client.profile(jane, "jane").followingCount)
        assertTrue(redis.commands().pttl(key)!! > 0, "reloaded entry has a TTL")
    }
}
