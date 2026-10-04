package com.android.insta.core.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * JSON request body. The content type is set per request (not in defaultRequest) so multipart
 * uploads keep their own `multipart/form-data; boundary=…` header.
 */
inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** Turns server-relative media paths ("/api/v1/media/…") into absolute URLs for image loading. */
class UrlResolver(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    fun resolve(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> "$base/${path.trimStart('/')}"
    }
}
