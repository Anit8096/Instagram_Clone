package com.android.insta.feature.post.data

import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.jsonBody
import com.android.insta.core.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

enum class MediaKind(val value: String) { POST("post"), AVATAR("avatar") }

class PostApi(private val client: HttpClient) {

    suspend fun uploadMedia(jpeg: ByteArray, kind: MediaKind): ApiResult<MediaDto> = safeApiCall {
        client.submitFormWithBinaryData(
            url = "api/v1/media?kind=${kind.value}",
            formData = formData {
                append("file", jpeg, Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"upload.jpg\"")
                })
            },
        )
    }

    /** Idempotent: repeating the call with the same [id] returns the existing post. */
    suspend fun createPost(id: String, mediaId: String, caption: String): ApiResult<PostDto> =
        safeApiCall { client.put("api/v1/posts/$id") { jsonBody(CreatePostRequest(mediaId, caption)) } }

    suspend fun getPost(id: String): ApiResult<PostDto> = safeApiCall { client.get("api/v1/posts/$id") }

    suspend fun deletePost(id: String): ApiResult<Unit> = safeApiCall { client.delete("api/v1/posts/$id") }
}
