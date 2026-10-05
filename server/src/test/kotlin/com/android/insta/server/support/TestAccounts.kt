package com.android.insta.server.support

import com.android.insta.server.auth.AuthResponse
import com.android.insta.server.auth.CompleteOnboardingRequest
import com.android.insta.server.auth.GoogleAuthResult
import com.android.insta.server.auth.GoogleLoginRequest
import com.android.insta.server.auth.OnboardingOtpRequest
import com.android.insta.server.auth.OtpChallengeDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.concurrent.atomic.AtomicInteger

private val phoneCounter = AtomicInteger(0)

/** A fresh, valid Indian mobile number per call (+91 98765 0xxxx), so tests never share a phone. */
fun nextTestPhone(): String = "+91987650%04d".format(phoneCounter.incrementAndGet() % 10_000)

suspend fun HttpClient.json(path: String, body: Any) = post(path) { contentType(ContentType.Application.Json); setBody(body) }

/**
 * Creates an account the only way the API allows: Google sign-in (the fake verifier accepts `google:<name>`) →
 * onboarding code to [phone] (echoed because tests run with `OTP_DEV_ECHO`) → complete with the username.
 */
suspend fun HttpClient.signUp(name: String, displayName: String = "", phone: String = nextTestPhone()): AuthResponse {
    val onboarding = json("/api/v1/auth/google", GoogleLoginRequest("google:$name")).body<GoogleAuthResult>() as GoogleAuthResult.NeedsOnboarding
    val challenge = json("/api/v1/auth/onboarding/otp", OnboardingOtpRequest(onboarding.onboardingToken, phone)).body<OtpChallengeDto>()
    return json(
        "/api/v1/auth/onboarding/complete",
        CompleteOnboardingRequest(onboarding.onboardingToken, challenge.challengeId, challenge.devCode!!, name, displayName),
    ).body()
}
