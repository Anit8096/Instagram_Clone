package com.android.insta.core.network

import kotlinx.serialization.Serializable

/** Outcome of a call to our backend. Repositories return this instead of throwing. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: AppError) : ApiResult<Nothing>
}

sealed interface AppError {
    /** No connectivity, DNS failure, timeout: the request never got a response. */
    data object Network : AppError

    /** The server answered with an error envelope. [code] is the server's machine-readable code. */
    data class Api(
        val status: Int,
        val code: String,
        val message: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : AppError

    data class Unexpected(val cause: Throwable? = null) : AppError
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

/** Mirrors the server's `{"error":{code,message,details}}` envelope. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String, val details: Map<String, String>? = null)
