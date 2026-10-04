package com.android.insta.server.auth

import com.android.insta.server.common.ApiException
import com.android.insta.server.config.GoogleConfig
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

data class GoogleIdentity(val subject: String, val email: String?, val emailVerified: Boolean, val name: String?)

fun interface GoogleTokenVerifier {
    /** Returns the verified identity or throws an [ApiException]. */
    suspend fun verify(idToken: String): GoogleIdentity
}

/**
 * Verifies Google ID tokens locally against Google's published signing keys (cached), checking
 * issuer, audience (our OAuth client IDs) and expiry. No Google client library needed.
 */
class JwksGoogleTokenVerifier(
    private val config: GoogleConfig,
    private val jwkProvider: JwkProvider = JwkProviderBuilder(URI(GOOGLE_CERTS_URL).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build(),
) : GoogleTokenVerifier {

    override suspend fun verify(idToken: String): GoogleIdentity {
        if (!config.enabled) {
            throw ApiException(HttpStatusCode.NotImplemented, "GOOGLE_SIGN_IN_DISABLED", "Google sign-in is not configured")
        }
        return withContext(Dispatchers.IO) {
            try {
                val decoded = JWT.decode(idToken)
                val key = jwkProvider.get(decoded.keyId).publicKey as RSAPublicKey
                val verified = JWT.require(Algorithm.RSA256(key, null))
                    .withIssuer("accounts.google.com", "https://accounts.google.com")
                    .withAnyOfAudience(*config.clientIds.toTypedArray())
                    .acceptLeeway(30)
                    .build()
                    .verify(decoded)
                GoogleIdentity(
                    subject = verified.subject,
                    email = verified.getClaim("email").asString()?.lowercase(),
                    emailVerified = verified.getClaim("email_verified").asBoolean() == true,
                    name = verified.getClaim("name").asString(),
                )
            } catch (e: Exception) {
                throw ApiException(HttpStatusCode.Unauthorized, "INVALID_GOOGLE_TOKEN", "Google ID token is invalid")
            }
        }
    }

    private companion object {
        const val GOOGLE_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
    }
}
