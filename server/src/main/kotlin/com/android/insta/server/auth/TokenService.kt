package com.android.insta.server.auth

import com.android.insta.server.config.JwtConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.Date
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

/**
 * Access tokens are short-lived HS256 JWTs. Refresh tokens are opaque random strings; only their
 * SHA-256 hash is stored, so a database leak does not leak usable tokens.
 */
class TokenService(private val config: JwtConfig, private val clock: Clock) {
    private val algorithm = Algorithm.HMAC256(config.secret)
    private val random = SecureRandom()

    val accessTtlSeconds: Long get() = config.accessTtl.inWholeSeconds

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim(CLAIM_TYPE, TYPE_ACCESS)
        .build()

    fun createAccessToken(userId: Uuid): String {
        val now = clock.instant()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId.toString())
            .withClaim(CLAIM_TYPE, TYPE_ACCESS)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(config.accessTtl.toJavaDuration())))
            .sign(algorithm)
    }

    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun refreshExpiry() = clock.instant().plus(config.refreshTtl.toJavaDuration())

    companion object {
        const val CLAIM_TYPE = "typ"
        const val TYPE_ACCESS = "access"

        fun hashRefreshToken(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
