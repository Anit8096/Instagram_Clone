package com.android.insta.server.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class AppConfig(
    val port: Int,
    val db: DbConfig,
    val jwt: JwtConfig,
    val google: GoogleConfig,
    val rateLimit: RateLimitConfig,
    val mediaRoot: String,
    val maxUploadBytes: Long = 10L * 1024 * 1024,
    /** Firebase service-account JSON; null = push disabled (NoopPushSender). */
    val firebaseCredentialsFile: String? = null,
    val otp: OtpConfig = OtpConfig(),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(key: String, default: String? = null): String =
                env[key]?.takeIf { it.isNotBlank() } ?: default
                    ?: error("Missing required environment variable $key")

            val jwtSecret = get("JWT_SECRET")
            require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }

            return AppConfig(
                port = get("PORT", "8080").toInt(),
                db = DbConfig(
                    url = get("DATABASE_URL", "jdbc:postgresql://localhost:5432/insta"),
                    user = get("DATABASE_USER", "insta"),
                    password = get("DATABASE_PASSWORD", "insta"),
                    maxPoolSize = get("DATABASE_POOL_SIZE", "10").toInt(),
                ),
                jwt = JwtConfig(
                    secret = jwtSecret,
                    issuer = get("JWT_ISSUER", "insta"),
                    audience = get("JWT_AUDIENCE", "insta-app"),
                    accessTtl = get("ACCESS_TOKEN_TTL_MINUTES", "15").toLong().minutes,
                    refreshTtl = get("REFRESH_TOKEN_TTL_DAYS", "30").toLong().days,
                ),
                google = GoogleConfig(
                    clientIds = get("GOOGLE_CLIENT_IDS", "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                ),
                rateLimit = RateLimitConfig(
                    authRequestsPerMinute = get("AUTH_RATE_LIMIT_PER_MINUTE", "20").toInt(),
                ),
                mediaRoot = get("MEDIA_ROOT", "/data/media"),
                maxUploadBytes = get("MAX_UPLOAD_MB", "10").toLong() * 1024 * 1024,
                firebaseCredentialsFile = get("FIREBASE_CREDENTIALS_FILE", "").ifBlank { null },
                otp = OtpConfig(devEcho = get("OTP_DEV_ECHO", "false").toBoolean()),
            )
        }
    }
}

data class DbConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
)

/** Empty [clientIds] disables Google sign-in. */
data class GoogleConfig(val clientIds: List<String>) {
    val enabled: Boolean get() = clientIds.isNotEmpty()
}

data class RateLimitConfig(val authRequestsPerMinute: Int)

/**
 * One-time codes. [devEcho] returns the code in the API response, which is for local demos and tests only: anyone
 * calling the API could then sign in as any number.
 */
data class OtpConfig(
    val devEcho: Boolean = false,
    val codeTtl: Duration = 5.minutes,
    val maxAttempts: Int = 5,
    val resendCooldown: Duration = 30.seconds,
    val maxPerHour: Int = 5,
)
