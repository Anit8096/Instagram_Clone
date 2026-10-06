package com.android.insta.server.plugins

import com.android.insta.server.auth.authRoutes
import com.android.insta.server.common.ApiException
import com.android.insta.server.common.AppJson
import com.android.insta.server.common.errorEnvelope
import com.android.insta.server.config.AppConfig
import com.android.insta.server.config.RateLimitConfig
import com.android.insta.server.redis.FixedWindowRateLimiter
import com.android.insta.server.redis.RateDecision
import com.android.insta.server.redis.Redis
import com.android.insta.server.media.mediaServeRoutes
import com.android.insta.server.media.mediaUploadRoutes
import com.android.insta.server.posts.postRoutes
import com.android.insta.server.posts.engagementRoutes
import com.android.insta.server.chat.chatRoutes
import com.android.insta.server.notifications.notificationRoutes
import com.android.insta.server.social.socialRoutes
import com.android.insta.server.users.meRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.inject
import org.slf4j.event.Level
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

fun Application.configureSerialization() {
    install(ContentNegotiation) { json(AppJson) }
}

fun Application.configureMonitoring() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { Uuid.random().toString() }
        verify { it.isNotBlank() && it.length <= 64 }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("call-id")
        filter { it.request.path() != "/health" }
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, errorEnvelope(e.code, e.message, e.details))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, errorEnvelope("BAD_REQUEST", "Malformed or missing request body"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, errorEnvelope("INTERNAL", "Something went wrong"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, errorEnvelope("NOT_FOUND", "No route for ${call.request.path()}"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, errorEnvelope("RATE_LIMITED", "Too many requests, try again later"))
        }
    }
}

/** A per-client-IP request budget shared by every server instance (counted in Redis, fixed windows). */
data class RateLimitPolicy(val name: String, val limit: Int, val window: Duration)

private class RouteRateLimitConfig {
    lateinit var policy: RateLimitPolicy
    lateinit var limiter: FixedWindowRateLimiter
}

private val RouteRateLimit = createRouteScopedPlugin("RouteRateLimit", ::RouteRateLimitConfig) {
    val policy = pluginConfig.policy
    val limiter = pluginConfig.limiter
    onCall { call ->
        val decision = limiter.acquire(policy.name, call.request.origin.remoteHost, policy.limit, policy.window)
        if (decision is RateDecision.Limited) {
            call.response.header(HttpHeaders.RetryAfter, ((decision.retryAfter.inWholeMilliseconds + 999) / 1000).toString())
            call.respond(HttpStatusCode.TooManyRequests, errorEnvelope("RATE_LIMITED", "Too many requests, try again later"))
        }
    }
}

/** Routes declared in [build] share [policy]'s budget. Fails open when Redis is unavailable. */
fun Route.rateLimited(policy: RateLimitPolicy, build: Route.() -> Unit): Route {
    val child = createChild(object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
        override fun toString() = "(rate limit ${policy.name})"
    })
    val limiter by application.inject<FixedWindowRateLimiter>()
    child.install(RouteRateLimit) {
        this.policy = policy
        this.limiter = limiter
    }
    child.build()
    return child
}

/** Sign-in and token endpoints: [RateLimitConfig.authRequestsPerMinute] per client IP. */
fun Route.authRateLimited(build: Route.() -> Unit): Route {
    val config by application.inject<AppConfig>()
    return rateLimited(RateLimitPolicy("auth", config.rateLimit.authRequestsPerMinute, 60.seconds), build)
}

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 64 * 1024L
    }
}

fun Application.configureDocs() {
    routing {
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")
    }
}

fun Application.configureRouting() {
    val db by inject<Database>()
    val redis by inject<Redis>()
    routing {
        // 503 only without the database; a Redis outage degrades the server (no OTP sends, no cache) but it serves.
        get("/health") {
            val dbUp = runCatching { suspendTransaction(db) { exec("SELECT 1") } }.isSuccess
            val redisUp = redis.isUp()
            val status = if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(
                status,
                mapOf(
                    "status" to if (dbUp && redisUp) "ok" else "degraded",
                    "db" to if (dbUp) "up" else "down",
                    "redis" to if (redisUp) "up" else "down",
                ),
            )
        }
        route("/api/v1") {
            authRoutes()
            mediaServeRoutes()
            authenticate(JWT_AUTH) {
                meRoutes()
                mediaUploadRoutes()
                postRoutes()
                socialRoutes()
                engagementRoutes()
                chatRoutes()
                notificationRoutes()
            }
        }
    }
}
