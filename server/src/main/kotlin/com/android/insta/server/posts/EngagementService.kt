package com.android.insta.server.posts

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.Cursor
import com.android.insta.server.common.Page
import com.android.insta.server.common.PageRequest
import com.android.insta.server.common.ValidationException
import com.android.insta.server.common.pageRequest
import com.android.insta.server.common.uuidParam
import com.android.insta.server.db.Comments
import com.android.insta.server.db.Likes
import com.android.insta.server.db.Posts
import com.android.insta.server.db.Users
import com.android.insta.server.media.mediaUrl
import com.android.insta.server.notifications.NotificationService
import com.android.insta.server.notifications.NotificationType
import com.android.insta.server.notifications.recordNotification
import com.android.insta.server.notifications.removeLikeNotification
import com.android.insta.server.plugins.currentUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.inject
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

@Serializable
data class LikeStateDto(val liked: Boolean, val likeCount: Int)

@Serializable
data class CreateCommentRequest(val body: String)

@Serializable
data class CommentDto(val id: String, val postId: String, val author: AuthorDto, val body: String, val createdAt: String)

/**
 * Likes and comments. Every write is idempotent (PUT/DELETE keyed by ids the client chooses) so the app's offline
 * action queue can replay them safely, and counters change in the same transaction as the row they count.
 */
class EngagementService(private val db: Database, private val clock: Clock, private val notifications: NotificationService) {

    suspend fun setLiked(userId: Uuid, postId: Uuid, liked: Boolean): LikeStateDto {
        val (state, notificationId) = suspendTransaction(db) {
            val author = requirePost(postId)
            val changed = if (liked) {
                Likes.insertIgnore {
                    it[Likes.userId] = userId
                    it[Likes.postId] = postId
                    it[createdAt] = now()
                }.insertedCount
            } else {
                Likes.deleteWhere { (Likes.userId eq userId) and (Likes.postId eq postId) }
            }
            var notificationId: Uuid? = null
            if (changed > 0) {
                Posts.update({ Posts.id eq postId }) {
                    if (liked) it[likeCount] = likeCount + 1 else it[likeCount] = likeCount - 1
                }
                if (liked) notificationId = recordNotification(author, userId, NotificationType.LIKE, now(), postId = postId)
                else removeLikeNotification(userId, postId)
            }
            LikeStateDto(liked, Posts.selectAll().where { Posts.id eq postId }.single()[Posts.likeCount]) to notificationId
        }
        notificationId?.let { notifications.deliver(it) }
        return state
    }

    /** `PUT /posts/{postId}/comments/{commentId}`: a retry with the same id returns the existing comment. */
    suspend fun addComment(userId: Uuid, postId: Uuid, commentId: Uuid, rawBody: String): Pair<CommentDto, Boolean> {
        val body = rawBody.trim()
        if (body.isEmpty() || body.length > BODY_MAX) throw ValidationException(mapOf("body" to "1-$BODY_MAX characters"))
        val (comment, created, notificationId) = suspendTransaction(db) {
            findComment(commentId)?.let { existing ->
                if (existing[Comments.authorId] != userId || existing[Comments.postId] != postId) {
                    throw ApiException(HttpStatusCode.Conflict, "COMMENT_ID_CONFLICT", "Comment id already used")
                }
                return@suspendTransaction Triple(existing.toComment(), false, null)
            }
            val author = requirePost(postId)
            Comments.insert {
                it[id] = commentId
                it[Comments.postId] = postId
                it[authorId] = userId
                it[Comments.body] = body
                it[createdAt] = now()
            }
            Posts.update({ Posts.id eq postId }) { it[commentCount] = commentCount + 1 }
            val notificationId = recordNotification(author, userId, NotificationType.COMMENT, now(), postId = postId, commentId = commentId)
            Triple(findComment(commentId)!!.toComment(), true, notificationId)
        }
        notificationId?.let { notifications.deliver(it) }
        return comment to created
    }

    /** Oldest first, like a conversation. */
    suspend fun comments(postId: Uuid, page: PageRequest): Page<CommentDto> = suspendTransaction(db) {
        requirePost(postId)
        val rows = commentsWithAuthors().selectAll()
            .where {
                val cursor = page.cursor
                val base = Comments.postId eq postId
                if (cursor == null) base
                else base and ((Comments.createdAt greater cursor.createdAt) or ((Comments.createdAt eq cursor.createdAt) and (Comments.id greater cursor.id)))
            }
            .orderBy(Comments.createdAt to SortOrder.ASC, Comments.id to SortOrder.ASC)
            .limit(page.limit + 1)
            .toList()
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { Cursor(it[Comments.createdAt], it[Comments.id]).encode() } else null
        Page(items.map { it.toComment() }, next)
    }

    /** The comment's author or the post's author may delete it. Deleting a missing comment is a no-op (idempotent). */
    suspend fun deleteComment(userId: Uuid, postId: Uuid, commentId: Uuid) {
        suspendTransaction(db) {
            val comment = Comments.selectAll().where { (Comments.id eq commentId) and (Comments.postId eq postId) }.singleOrNull()
                ?: return@suspendTransaction
            val postAuthor = Posts.selectAll().where { Posts.id eq postId }.single()[Posts.authorId]
            if (comment[Comments.authorId] != userId && postAuthor != userId) {
                throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "You can't delete this comment")
            }
            Comments.deleteWhere { Comments.id eq commentId }
            Posts.update({ Posts.id eq postId }) { it[commentCount] = commentCount - 1 }
        }
    }

    private fun commentsWithAuthors() = Comments.join(Users, JoinType.INNER, Comments.authorId, Users.id)

    private fun JdbcTransaction.findComment(id: Uuid): ResultRow? =
        commentsWithAuthors().selectAll().where { Comments.id eq id }.singleOrNull()

    /** Returns the post's author. */
    private fun JdbcTransaction.requirePost(postId: Uuid): Uuid =
        Posts.select(Posts.authorId).where { Posts.id eq postId }.singleOrNull()?.get(Posts.authorId)
            ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Post not found")

    private fun now() = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))

    private fun ResultRow.toComment() = CommentDto(
        id = this[Comments.id].toString(),
        postId = this[Comments.postId].toString(),
        author = AuthorDto(this[Users.id].toString(), this[Users.username], this[Users.displayName], this[Users.avatarMediaId]?.let { mediaUrl(it, "thumb") }),
        body = this[Comments.body],
        createdAt = this[Comments.createdAt].toInstant().toString(),
    )

    companion object {
        const val BODY_MAX = 1000
    }
}

/** Mount inside `authenticate`. */
fun Route.engagementRoutes() {
    val engagement by inject<EngagementService>()

    route("/posts/{id}") {
        put("/like") { call.respond(engagement.setLiked(call.currentUserId(), call.uuidParam("id"), liked = true)) }
        delete("/like") { call.respond(engagement.setLiked(call.currentUserId(), call.uuidParam("id"), liked = false)) }
        get("/comments") { call.respond(engagement.comments(call.uuidParam("id"), call.pageRequest(defaultLimit = 30))) }
        put("/comments/{commentId}") {
            val (comment, created) = engagement.addComment(
                call.currentUserId(), call.uuidParam("id"), call.uuidParam("commentId"), call.receive<CreateCommentRequest>().body,
            )
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, comment)
        }
        delete("/comments/{commentId}") {
            engagement.deleteComment(call.currentUserId(), call.uuidParam("id"), call.uuidParam("commentId"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
