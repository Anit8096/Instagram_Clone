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
            post("/google") {
                call.respond<GoogleAuthResult>(auth.loginWithGoogle(call.receive<GoogleLoginRequest>()))
            }
            post("/phone/otp") {
                call.respond(auth.requestPhoneLogin(call.receive<PhoneOtpRequest>()))
            }
            post("/phone/verify") {
                call.respond(auth.verifyPhoneLogin(call.receive<VerifyOtpRequest>()))
            }
            post("/onboarding/otp") {
                call.respond(auth.requestOnboardingOtp(call.receive<OnboardingOtpRequest>()))
            }
            post("/onboarding/complete") {
                call.respond(HttpStatusCode.Created, auth.completeOnboarding(call.receive<CompleteOnboardingRequest>()))
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
