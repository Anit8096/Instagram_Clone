package com.android.insta.server.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Thrown anywhere in request handling; StatusPages turns it into an [ErrorEnvelope]. */
open class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message)

class ValidationException(details: Map<String, String>) :
    ApiException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Request validation failed", details)

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String, val details: Map<String, String>? = null)

fun errorEnvelope(code: String, message: String, details: Map<String, String>? = null) =
    ErrorEnvelope(ErrorBody(code, message, details))
