package com.android.insta.server.plugins

import com.android.insta.server.auth.TokenService
import com.android.insta.server.common.ApiException
import com.android.insta.server.common.errorEnvelope
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

const val JWT_AUTH = "jwt"

data class UserPrincipal(val userId: Uuid)

fun Application.configureSecurity() {
    val tokens by inject<TokenService>()
    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = "insta"
            verifier(tokens.verifier)
            validate { credential ->
                credential.payload.subject
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?.let(::UserPrincipal)
            }
            challenge { _, _ ->
                // RFC 6750: tells clients (e.g. Ktor's bearer Auth plugin) to refresh and retry.
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"insta\"")
                call.respond(HttpStatusCode.Unauthorized, errorEnvelope("UNAUTHORIZED", "Missing or invalid access token"))
            }
        }
    }
}

fun ApplicationCall.currentUserId(): Uuid =
    principal<UserPrincipal>()?.userId
        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing or invalid access token")
