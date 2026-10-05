package com.android.insta.server.auth

import com.android.insta.server.common.ValidationException
import com.android.insta.server.config.JwtConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class TokenServiceTest {
    private val config = JwtConfig("unit-test-secret-that-is-long-enough!!", "insta", "insta-app", 15.minutes, 30.days)

    @Test
    fun `access token verifies and carries the user id`() {
        val tokens = TokenService(config, Clock.systemUTC())
        val userId = Uuid.random()
        val decoded = tokens.verifier.verify(tokens.createAccessToken(userId))
        assertEquals(userId.toString(), decoded.subject)
    }

    @Test
    fun `expired access token is rejected`() {
        val past = Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC)
        val token = TokenService(config, past).createAccessToken(Uuid.random())
        assertFailsWith<JWTVerificationException> { TokenService(config, Clock.systemUTC()).verifier.verify(token) }
    }

    @Test
    fun `token without the access type claim is rejected`() {
        val forged = JWT.create().withIssuer("insta").withAudience("insta-app").withSubject(Uuid.random().toString())
            .sign(Algorithm.HMAC256(config.secret))
        assertFailsWith<JWTVerificationException> { TokenService(config, Clock.systemUTC()).verifier.verify(forged) }
    }

    @Test
    fun `refresh tokens are random and hashed deterministically`() {
        val tokens = TokenService(config, Clock.systemUTC())
        val a = tokens.newRefreshToken()
        assertNotEquals(a, tokens.newRefreshToken())
        assertEquals(64, TokenService.hashRefreshToken(a).length)
        assertEquals(TokenService.hashRefreshToken(a), TokenService.hashRefreshToken(a))
    }
}

class OnboardingTokenTest {
    private val config = JwtConfig("unit-test-secret-that-is-long-enough!!", "insta", "insta-app", 15.minutes, 30.days)
    private val identity = GoogleIdentity("sub-1", "sam@gmail.com", emailVerified = true, name = "Sam")

    @Test
    fun `round-trips the google identity but never works as an access token`() {
        val tokens = TokenService(config, Clock.systemUTC())
        val token = tokens.createOnboardingToken(identity)
        assertEquals(identity, tokens.verifyOnboardingToken(token))
        assertFailsWith<JWTVerificationException> { tokens.verifier.verify(token) }
        assertFailsWith<com.android.insta.server.common.ApiException> { tokens.verifyOnboardingToken(tokens.createAccessToken(Uuid.random())) }
    }

    @Test
    fun `expires after 15 minutes and drops unverified emails`() {
        val past = Clock.fixed(Instant.now().minusSeconds(16 * 60), ZoneOffset.UTC)
        val old = TokenService(config, past).createOnboardingToken(identity)
        assertFailsWith<com.android.insta.server.common.ApiException> { TokenService(config, Clock.systemUTC()).verifyOnboardingToken(old) }

        val tokens = TokenService(config, Clock.systemUTC())
        val unverified = tokens.verifyOnboardingToken(tokens.createOnboardingToken(identity.copy(emailVerified = false)))
        assertEquals(null, unverified.email)
    }
}

class AuthValidationTest {
    @Test
    fun `normalizes the profile and reports every invalid field`() {
        assertEquals("jane.doe" to "Jane", AuthValidation.validateProfile("  Jane.Doe ", " Jane "))
        val error = assertFailsWith<ValidationException> { AuthValidation.validateProfile("a!", "n".repeat(61)) }
        assertEquals(setOf("username", "displayName"), error.details?.keys)
    }

    @Test
    fun `phone numbers normalise to E164 and need a country code`() {
        assertEquals("+919876543210", PhoneNumbers.normalize(" +91 98765-43210 "))
        assertEquals("+12015550101", PhoneNumbers.normalize("+1 (201) 555-0101")) // demo seed range
        listOf("9876543210", "+91 123", "+1 555", "hello").forEach { raw ->
            assertTrue(assertFailsWith<ValidationException>(raw) { PhoneNumbers.normalize(raw) }.details!!.containsKey("phone"))
        }
    }

    @Test
    fun `masked numbers show only the country code and last four digits`() {
        assertEquals("+91 ••••••3210", OtpService.mask("+919876543210"))
        assertEquals("+1 ••••••0101", OtpService.mask("+12015550101"))
    }
}
