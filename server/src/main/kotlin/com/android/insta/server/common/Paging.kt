package com.android.insta.server.common

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import kotlin.uuid.Uuid

@Serializable
data class Page<T>(val items: List<T>, val nextCursor: String? = null)

/**
 * Keyset position `(created_at, id)` for newest-first lists. Opaque to clients (base64url), stable
 * under inserts, and cheap with an index on `(…, created_at DESC, id DESC)`.
 */
data class Cursor(val createdAt: OffsetDateTime, val id: Uuid) {
    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${createdAt.toInstant()}|$id".toByteArray())

    companion object {
        fun decode(raw: String): Cursor = try {
            val (instant, id) = String(Base64.getUrlDecoder().decode(raw)).split('|', limit = 2)
            Cursor(Instant.parse(instant).atOffset(ZoneOffset.UTC), Uuid.parse(id))
        } catch (e: Exception) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Malformed cursor")
        }
    }
}

data class PageRequest(val cursor: Cursor?, val limit: Int)

fun ApplicationCall.pageRequest(defaultLimit: Int = 30, maxLimit: Int = 60): PageRequest {
    val limit = request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, maxLimit) ?: defaultLimit
    return PageRequest(request.queryParameters["cursor"]?.takeIf { it.isNotBlank() }?.let(Cursor::decode), limit)
}

fun ApplicationCall.uuidParam(name: String): Uuid =
    parameters[name]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Not found")
