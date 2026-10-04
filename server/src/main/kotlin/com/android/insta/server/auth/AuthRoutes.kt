package com.android.insta.server.auth

import com.android.insta.server.plugins.AUTH_RATE_LIMIT
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

fun Route.authRoutes() {
    val auth by inject<AuthService>()

    route("/auth") {
        rateLimit(AUTH_RATE_LIMIT) {
            post("/register") {
                call.respond(HttpStatusCode.Created, auth.register(call.receive<RegisterRequest>()))
            }
            post("/login") {
                call.respond(auth.login(call.receive<LoginRequest>()))
            }
            post("/google") {
                call.respond(auth.loginWithGoogle(call.receive<GoogleLoginRequest>()))
            }
            post("/refresh") {
                call.respond(auth.refresh(call.receive<RefreshRequest>()))
            }
            post("/logout") {
                auth.logout(call.receive<RefreshRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
