package com.android.insta.server.media

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.uuidParam
import com.android.insta.server.config.AppConfig
import com.android.insta.server.plugins.currentUserId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.koin.ktor.ext.inject

/** `POST /media` (multipart field "file", query `kind=post|avatar`). Mount inside `authenticate`. */
fun Route.mediaUploadRoutes() {
    val media by inject<MediaService>()
    val config by inject<AppConfig>()

    post("/media") {
        val kind = MediaKind.parse(call.request.queryParameters["kind"] ?: "post")
            ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_KIND", "kind must be 'post' or 'avatar'")
        val limit = config.maxUploadBytes

        var bytes: ByteArray? = null
        call.receiveMultipart(formFieldLimit = limit * 2).forEachPart { part ->
            if (part is PartData.FileItem && part.name == "file" && bytes == null) {
                bytes = part.provider().readRemaining(limit + 1).readByteArray()
            }
            part.dispose()
        }
        val data = bytes ?: throw ApiException(HttpStatusCode.BadRequest, "MISSING_FILE", "Multipart field 'file' is required")
        if (data.size > limit) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "FILE_TOO_LARGE", "Images must be at most ${limit / 1_048_576} MB")
        }
        val record = media.upload(call.currentUserId(), kind, data)
        call.respond(HttpStatusCode.Created, record.toDto())
    }
}

/**
 * `GET /media/{id}/{full|thumb}`: public (all accounts are public). Files never change once
 * written, so they're cached forever and revalidated by ETag.
 */
fun Route.mediaServeRoutes() {
    val repository by inject<MediaRepository>()
    val storage by inject<MediaStorage>()

    get("/media/{id}/{variant}") {
        val id = call.uuidParam("id")
        val variant = call.parameters["variant"]
        val record = repository.find(id) ?: throw notFound()
        val key = when (variant) {
            "full" -> record.fullPath
            "thumb" -> record.thumbPath
            else -> throw notFound()
        }
        val etag = "\"$id-$variant\""
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        val path = storage.resolve(key) ?: throw notFound()
        call.respond(LocalPathContent(path, ContentType.Image.JPEG))
    }
}

private fun notFound() = ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Media not found")
