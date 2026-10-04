package com.android.insta.server.plugins

import com.android.insta.server.auth.authRoutes
import com.android.insta.server.common.ApiException
import com.android.insta.server.common.AppJson
import com.android.insta.server.common.errorEnvelope
import com.android.insta.server.config.RateLimitConfig
import com.android.insta.server.media.mediaServeRoutes
import com.android.insta.server.media.mediaUploadRoutes
import com.android.insta.server.posts.postRoutes
import com.android.insta.server.posts.engagementRoutes
import com.android.insta.server.chat.chatRoutes
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
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.path
import io.ktor.server.response.respond
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

val AUTH_RATE_LIMIT = RateLimitName("auth")

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

fun Application.configureRateLimiting(config: RateLimitConfig) {
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = config.authRequestsPerMinute, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
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
    routing {
        get("/health") {
            val dbUp = runCatching { suspendTransaction(db) { exec("SELECT 1") } }.isSuccess
            val status = if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, mapOf("status" to if (dbUp) "ok" else "degraded", "db" to if (dbUp) "up" else "down"))
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
            }
        }
    }
}
