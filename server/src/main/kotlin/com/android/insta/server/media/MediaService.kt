package com.android.insta.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class MediaDto(val id: String, val url: String, val thumbUrl: String, val width: Int, val height: Int)

fun mediaUrl(id: Uuid, variant: String) = "/api/v1/media/$id/$variant"

fun MediaRecord.toDto() = MediaDto(id.toString(), mediaUrl(id, "full"), mediaUrl(id, "thumb"), width, height)

class MediaService(
    private val repository: MediaRepository,
    private val storage: MediaStorage,
    private val processor: ImageProcessor,
) {
    suspend fun upload(ownerId: Uuid, kind: MediaKind, bytes: ByteArray): MediaRecord {
        val processed = withContext(Dispatchers.Default) { processor.process(bytes) }
        val id = Uuid.random()
        val record = MediaRecord(
            id = id,
            ownerId = ownerId,
            kind = kind,
            fullPath = "$ownerId/${id}_full.jpg",
            thumbPath = "$ownerId/${id}_thumb.jpg",
            width = processed.width,
            height = processed.height,
        )
        withContext(Dispatchers.IO) {
            storage.write(record.fullPath, processed.full)
            storage.write(record.thumbPath, processed.thumb)
        }
        try {
            repository.insert(record)
        } catch (e: Exception) {
            storage.delete(listOf(record.fullPath, record.thumbPath))
            throw e
        }
        return record
    }

    fun deleteFiles(keys: Collection<String>) = storage.delete(keys)
}
