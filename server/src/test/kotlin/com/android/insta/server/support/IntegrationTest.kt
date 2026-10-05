package com.android.insta.server.support

import com.android.insta.server.auth.GoogleIdentity
import com.android.insta.server.auth.GoogleTokenVerifier
import com.android.insta.server.common.ApiException
import com.android.insta.server.common.AppJson
import com.android.insta.server.config.AppConfig
import com.android.insta.server.config.DbConfig
import com.android.insta.server.config.GoogleConfig
import com.android.insta.server.config.JwtConfig
import com.android.insta.server.config.OtpConfig
import com.android.insta.server.config.RateLimitConfig
import com.android.insta.server.module
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import org.koin.dsl.module as koinModule

/** One Postgres container shared by every integration test in the JVM. */
object TestDatabase {
    val dockerAvailable: Boolean by lazy { runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) }

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17-alpine").apply { start() }
    }

    fun truncateAll() {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            val tables = conn.createStatement().executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            if (tables.isNotEmpty()) conn.createStatement().execute("TRUNCATE ${tables.joinToString()} CASCADE")
        }
    }
}

/**
 * Google verifier for tests: tokens registered in [identities], plus `google:<name>`, which is always a verified
 * Google account (`sub-<name>`, `<name>@example.com`).
 */
class FakeGoogleVerifier(private val identities: Map<String, GoogleIdentity> = emptyMap()) : GoogleTokenVerifier {
    override suspend fun verify(idToken: String): GoogleIdentity =
        identities[idToken]
            ?: idToken.removePrefix("google:").takeIf { idToken.startsWith("google:") }?.let { name ->
                GoogleIdentity("sub-$name", "$name@example.com", emailVerified = true, name = name.replaceFirstChar(Char::uppercase))
            }
            ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_GOOGLE_TOKEN", "Google ID token is invalid")
}

abstract class IntegrationTest {

    @BeforeEach
    fun prepareDatabase() {
        assumeTrue(TestDatabase.dockerAvailable, "Docker is not available; skipping integration test")
        // Starting the app runs Flyway, so tables exist after the first test; truncate is a no-op before that.
        TestDatabase.container
        TestDatabase.truncateAll()
    }

    protected fun testConfig(authRequestsPerMinute: Int = 10_000) = AppConfig(
        port = 0,
        db = DbConfig(
            url = TestDatabase.container.jdbcUrl,
            user = TestDatabase.container.username,
            password = TestDatabase.container.password,
            maxPoolSize = 4,
        ),
        jwt = JwtConfig(
            secret = "test-secret-that-is-definitely-long-enough",
            issuer = "insta",
            audience = "insta-app",
            accessTtl = 15.minutes,
            refreshTtl = 30.days,
        ),
        google = GoogleConfig(listOf("test-client-id")),
        rateLimit = RateLimitConfig(authRequestsPerMinute),
        mediaRoot = mediaRoot.toString(),
        otp = relaxedOtp,
    )

    /** Codes echoed and send throttling off, so tests can sign up and sign in back to back. */
    protected val relaxedOtp = OtpConfig(devEcho = true, resendCooldown = kotlin.time.Duration.ZERO, maxPerHour = 1_000)

    /** The production throttling (30 s cooldown, 5 per hour) with codes echoed, for tests of those limits. */
    protected val realOtp = OtpConfig(devEcho = true)

    /** Fresh media directory per test class so file assertions don't see other tests' uploads. */
    protected val mediaRoot: java.nio.file.Path by lazy { java.nio.file.Files.createTempDirectory("insta-media") }

    protected fun withApp(
        config: AppConfig = testConfig(),
        google: GoogleTokenVerifier = FakeGoogleVerifier(),
        extraModules: List<org.koin.core.module.Module> = emptyList(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application {
            module(config, listOf(koinModule { single<GoogleTokenVerifier> { google } }) + extraModules)
        }
        val client = createClient { install(ClientContentNegotiation) { json(AppJson) } }
        block(client)
    }
}
