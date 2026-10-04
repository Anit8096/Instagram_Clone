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

class PasswordHasherTest {
    private val hasher = Argon2PasswordHasher()

    @Test
    fun `hash verifies only the original password`() {
        val hash = hasher.hash("correct-horse")
        assertTrue(hash.startsWith("\$argon2id\$"))
        assertTrue(hasher.verify("correct-horse", hash))
        assertFalse(hasher.verify("wrong-horse", hash))
        assertFalse(hasher.verify("correct-horse", "garbage"))
    }
}

class AuthValidationTest {
    @Test
    fun `normalizes username and email`() {
        val valid = AuthValidation.validate(RegisterRequest("  Jane.Doe ", " JANE@Example.COM ", "password1", " Jane "))
        assertEquals("jane.doe", valid.username)
        assertEquals("jane@example.com", valid.email)
        assertEquals("Jane", valid.displayName)
    }

    @Test
    fun `reports every invalid field`() {
        val error = assertFailsWith<ValidationException> {
            AuthValidation.validate(RegisterRequest("a!", "x@", "short", "n".repeat(61)))
        }
        assertEquals(setOf("username", "email", "password", "displayName"), error.details?.keys)
    }
}
