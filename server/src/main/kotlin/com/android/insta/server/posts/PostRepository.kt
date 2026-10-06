package com.android.insta.server.posts

import com.android.insta.server.common.Cursor
import com.android.insta.server.common.PageRequest
import com.android.insta.server.db.Follows
import com.android.insta.server.db.Media
import com.android.insta.server.db.PostMedia
import com.android.insta.server.db.Posts
import com.android.insta.server.db.Users
import com.android.insta.server.media.MediaType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

/** One item of a post, in display order. */
data class PostMediaRecord(val id: Uuid, val type: MediaType, val width: Int, val height: Int)

data class PostRecord(
    val id: Uuid,
    val authorId: Uuid,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorAvatarMediaId: Uuid?,
    /** 1–10 items; the first is the cover and sets the post's shape. */
    val media: List<PostMediaRecord>,
    val caption: String,
    val likeCount: Int,
    val commentCount: Int,
    val createdAt: OffsetDateTime,
) {
    val cover: PostMediaRecord get() = media.first()
}

class PostRepository(private val db: Database) {

    private val joined = Posts.join(Users, JoinType.INNER, Posts.authorId, Users.id)

    suspend fun find(id: Uuid): PostRecord? = suspendTransaction(db) { findIn(id) }

    fun JdbcTransaction.findIn(id: Uuid): PostRecord? =
        joined.selectAll().where { Posts.id eq id }.singleOrNull()?.let { withMedia(listOf(it)).single() }

    fun JdbcTransaction.insertIn(id: Uuid, authorId: Uuid, mediaIds: List<Uuid>, caption: String, now: OffsetDateTime) {
        Posts.insert {
            it[Posts.id] = id
            it[Posts.authorId] = authorId
            it[Posts.caption] = caption
            it[likeCount] = 0
            it[commentCount] = 0
            it[createdAt] = now
        }
        PostMedia.batchInsert(mediaIds.withIndex()) { (position, mediaId) ->
            this[PostMedia.postId] = id
            this[PostMedia.position] = position
            this[PostMedia.mediaId] = mediaId
        }
    }

    /** Which of [mediaIds] already belong to a post. */
    fun JdbcTransaction.usedAmongIn(mediaIds: Collection<Uuid>): Set<Uuid> =
        PostMedia.select(PostMedia.mediaId).where { PostMedia.mediaId inList mediaIds }.map { it[PostMedia.mediaId] }.toSet()

    /** Deletes the post (its post_media rows cascade). The media rows are the caller's to delete. */
    fun JdbcTransaction.deleteIn(id: Uuid) {
        Posts.deleteWhere { Posts.id eq id }
    }

    /** Newest first; fetches one extra row to know whether another page exists. */
    suspend fun byAuthor(authorId: Uuid, page: PageRequest): List<PostRecord> = query(page) { Posts.authorId eq authorId }

    /** Home feed: the viewer's own posts plus posts from everyone they follow. */
    suspend fun feed(viewerId: Uuid, page: PageRequest): List<PostRecord> = query(page) {
        (Posts.authorId eq viewerId) or (Posts.authorId inSubQuery followeesOf(viewerId))
    }

    /** Discovery: recent posts from accounts the viewer doesn't follow (ranking by likes arrives with likes). */
    suspend fun explore(viewerId: Uuid, page: PageRequest): List<PostRecord> = query(page) {
        (Posts.authorId neq viewerId) and (Posts.authorId notInSubQuery followeesOf(viewerId))
    }

    private fun followeesOf(viewerId: Uuid) = Follows.select(Follows.followeeId).where { Follows.followerId eq viewerId }

    private suspend fun query(page: PageRequest, condition: () -> Op<Boolean>): List<PostRecord> = suspendTransaction(db) {
        val rows = joined.selectAll()
            .where { condition() and page.cursor.before() }
            .orderBy(Posts.createdAt to SortOrder.DESC, Posts.id to SortOrder.DESC)
            .limit(page.limit + 1)
            .toList()
        withMedia(rows)
    }

    suspend fun countByAuthor(authorId: Uuid): Long = suspendTransaction(db) {
        Posts.selectAll().where { Posts.authorId eq authorId }.count()
    }

    /** One query for the media of a whole page of posts, then attach in order. */
    private fun JdbcTransaction.withMedia(rows: List<ResultRow>): List<PostRecord> {
        if (rows.isEmpty()) return emptyList()
        val media = PostMedia.join(Media, JoinType.INNER, PostMedia.mediaId, Media.id)
            .select(PostMedia.postId, Media.id, Media.type, Media.width, Media.height)
            .where { PostMedia.postId inList rows.map { it[Posts.id] } }
            .orderBy(PostMedia.position to SortOrder.ASC)
            .groupBy({ it[PostMedia.postId] }) {
                PostMediaRecord(it[Media.id], MediaType.parse(it[Media.type]), it[Media.width], it[Media.height])
            }
        return rows.map { it.toPost(media[it[Posts.id]].orEmpty()) }
    }

    private fun Cursor?.before(): Op<Boolean> =
        if (this == null) {
            Op.TRUE
        } else {
            (Posts.createdAt less createdAt) or ((Posts.createdAt eq createdAt) and (Posts.id less id))
        }

    private fun ResultRow.toPost(media: List<PostMediaRecord>) = PostRecord(
        id = this[Posts.id],
        authorId = this[Posts.authorId],
        authorUsername = this[Users.username],
        authorDisplayName = this[Users.displayName],
        authorAvatarMediaId = this[Users.avatarMediaId],
        media = media,
        caption = this[Posts.caption],
        likeCount = this[Posts.likeCount],
        commentCount = this[Posts.commentCount],
        createdAt = this[Posts.createdAt],
    )
}
