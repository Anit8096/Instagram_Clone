package com.android.insta.server.media

import com.android.insta.server.db.Media
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

enum class MediaKind(val value: String) {
    POST("post"), AVATAR("avatar");

    companion object {
        fun parse(raw: String?): MediaKind? = entries.firstOrNull { it.value == raw }
    }
}

data class MediaRecord(
    val id: Uuid,
    val ownerId: Uuid,
    val kind: MediaKind,
    val fullPath: String,
    val thumbPath: String,
    val width: Int,
    val height: Int,
)

class MediaRepository(private val db: Database) {

    suspend fun insert(record: MediaRecord) {
        suspendTransaction(db) {
            Media.insert {
                it[id] = record.id
                it[ownerId] = record.ownerId
                it[kind] = record.kind.value
                it[fullPath] = record.fullPath
                it[thumbPath] = record.thumbPath
                it[width] = record.width
                it[height] = record.height
                it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun find(id: Uuid): MediaRecord? = suspendTransaction(db) { findIn(id) }

    fun JdbcTransaction.findIn(id: Uuid): MediaRecord? =
        Media.selectAll().where { Media.id eq id }.singleOrNull()?.toMedia()

    /** Deletes the row inside the caller's transaction; returns its file keys for deletion after commit. */
    fun JdbcTransaction.deleteIn(id: Uuid): List<String> {
        val record = findIn(id) ?: return emptyList()
        Media.deleteWhere { Media.id eq id }
        return listOf(record.fullPath, record.thumbPath)
    }

    private fun ResultRow.toMedia() = MediaRecord(
        id = this[Media.id],
        ownerId = this[Media.ownerId],
        kind = MediaKind.parse(this[Media.kind]) ?: MediaKind.POST,
        fullPath = this[Media.fullPath],
        thumbPath = this[Media.thumbPath],
        width = this[Media.width],
        height = this[Media.height],
    )
}
