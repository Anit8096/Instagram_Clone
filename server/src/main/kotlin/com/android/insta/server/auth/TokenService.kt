package com.android.insta.server.auth

import com.android.insta.server.common.ApiException
import com.android.insta.server.config.JwtConfig
import io.ktor.http.HttpStatusCode
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

    /**
     * Short-lived proof that Google verified this person, used only to finish onboarding (pick a username, verify a
     * phone). Its own type claim means the access-token [verifier] rejects it.
     */
    fun createOnboardingToken(identity: GoogleIdentity): String {
        val now = clock.instant()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(identity.subject)
            .withClaim(CLAIM_TYPE, TYPE_ONBOARDING)
            .withClaim(CLAIM_EMAIL, identity.email?.takeIf { identity.emailVerified })
            .withClaim(CLAIM_NAME, identity.name)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(ONBOARDING_TTL)))
            .sign(algorithm)
    }

    /** Returns the onboarding identity, or throws 401 `INVALID_ONBOARDING_TOKEN` (wrong type, expired, tampered). */
    fun verifyOnboardingToken(token: String): GoogleIdentity = try {
        val jwt = onboardingVerifier.verify(token)
        val email = jwt.getClaim(CLAIM_EMAIL).asString()
        GoogleIdentity(jwt.subject, email, emailVerified = email != null, name = jwt.getClaim(CLAIM_NAME).asString())
    } catch (e: Exception) {
        throw ApiException(HttpStatusCode.Unauthorized, "INVALID_ONBOARDING_TOKEN", "Sign-up session expired. Sign in with Google again.")
    }

    private val onboardingVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim(CLAIM_TYPE, TYPE_ONBOARDING)
        .build()

    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun refreshExpiry() = clock.instant().plus(config.refreshTtl.toJavaDuration())

    companion object {
        const val CLAIM_TYPE = "typ"
        const val TYPE_ACCESS = "access"
        const val TYPE_ONBOARDING = "onboarding"
        private const val CLAIM_EMAIL = "email"
        private const val CLAIM_NAME = "name"
        private val ONBOARDING_TTL = java.time.Duration.ofMinutes(15)

        fun hashRefreshToken(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
