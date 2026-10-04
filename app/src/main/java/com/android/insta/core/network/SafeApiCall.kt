package com.android.insta.core.network

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [request] and converts the response into an [ApiResult]: 2xx bodies are decoded as [T],
 * error envelopes become [AppError.Api], and I/O failures become [AppError.Network].
 */
suspend inline fun <reified T> safeApiCall(request: () -> HttpResponse): ApiResult<T> =
    try {
        val response = request()
        if (response.status.isSuccess()) {
            ApiResult.Success(if (T::class == Unit::class) Unit as T else response.body<T>())
        } else {
            ApiResult.Failure(response.toApiError())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Failure(AppError.Network)
    } catch (e: java.io.IOException) {
        ApiResult.Failure(AppError.Network)
    } catch (e: Exception) {
        Timber.w(e, "Unexpected API failure")
        ApiResult.Failure(AppError.Unexpected(e))
    }

suspend fun HttpResponse.toApiError(): AppError.Api {
    val envelope = runCatching { body<ErrorEnvelope>() }.getOrNull()
    return AppError.Api(
        status = status.value,
        code = envelope?.error?.code ?: "HTTP_${status.value}",
        message = envelope?.error?.message ?: status.description,
        fieldErrors = envelope?.error?.details.orEmpty(),
    )
}
