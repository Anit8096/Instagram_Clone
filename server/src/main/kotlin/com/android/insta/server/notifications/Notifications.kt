package com.android.insta.server.notifications

import com.android.insta.server.chat.ConnectionRegistry
import com.android.insta.server.chat.RealtimeEvent
import com.android.insta.server.common.ApiException
import com.android.insta.server.common.Cursor
import com.android.insta.server.common.Page
import com.android.insta.server.common.PageRequest
import com.android.insta.server.common.pageRequest
import com.android.insta.server.db.Comments
import com.android.insta.server.db.DeviceTokens
import com.android.insta.server.db.Notifications
import com.android.insta.server.db.Posts
import com.android.insta.server.db.Users
import com.android.insta.server.media.mediaUrl
import com.android.insta.server.plugins.currentUserId
import com.android.insta.server.posts.AuthorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

@Serializable
data class NotificationDto(
    val id: String,
    /** `like`, `comment` or `follow`. */
    val type: String,
    val actor: AuthorDto,
    val postId: String? = null,
    val postThumbUrl: String? = null,
    val commentId: String? = null,
    val commentBody: String? = null,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class UnreadCountDto(val count: Long)

object NotificationType {
    const val LIKE = "like"
    const val COMMENT = "comment"
    const val FOLLOW = "follow"
    const val MESSAGE = "message"
}

// Writers: call inside the transaction that creates or removes the thing being notified about, so a like and its
// notification commit (or roll back) together. They return the new row's id, or null when nothing was inserted.

/** No self-notifications; a repeated like/follow by the same actor is absorbed by the V3 partial unique indexes. */
fun recordNotification(recipient: Uuid, actor: Uuid, type: String, at: OffsetDateTime, postId: Uuid? = null, commentId: Uuid? = null): Uuid? {
    if (recipient == actor) return null
    val id = Uuid.random()
    val inserted = Notifications.insertIgnore {
        it[Notifications.id] = id
        it[recipientId] = recipient
        it[actorId] = actor
        it[Notifications.type] = type
        it[Notifications.postId] = postId
        it[Notifications.commentId] = commentId
        it[createdAt] = at
    }.insertedCount
    return id.takeIf { inserted > 0 }
}

/** Unliking takes the "liked your post" back out of the recipient's activity. */
fun removeLikeNotification(actor: Uuid, postId: Uuid) {
    Notifications.deleteWhere { (actorId eq actor) and (Notifications.postId eq postId) and (type eq NotificationType.LIKE) }
}

fun removeFollowNotification(actor: Uuid, recipient: Uuid) {
    Notifications.deleteWhere { (actorId eq actor) and (recipientId eq recipient) and (type eq NotificationType.FOLLOW) }
}

/**
 * Activity feed, unread badge and delivery. After a notification commits, [deliver] pushes it to the recipient's open
 * sockets (in-app badge) and, only when they have none, to their devices through [PushSender].
 */
class NotificationService(
    private val db: Database,
    private val registry: ConnectionRegistry,
    private val push: PushSender,
    private val clock: Clock,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    // Pushes are fire-and-forget so a slow push service never delays the like/comment/follow response.
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun list(me: Uuid, page: PageRequest): Page<NotificationDto> = suspendTransaction(db) {
        val rows = joined().selectAll().where {
            val cursor = page.cursor
            val base = Notifications.recipientId eq me
            if (cursor == null) base
            else base and ((Notifications.createdAt less cursor.createdAt) or ((Notifications.createdAt eq cursor.createdAt) and (Notifications.id less cursor.id)))
        }.orderBy(Notifications.createdAt to SortOrder.DESC, Notifications.id to SortOrder.DESC).limit(page.limit + 1).toList()
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { Cursor(it[Notifications.createdAt], it[Notifications.id]).encode() } else null
        Page(items.map { it.toDto() }, next)
    }

    suspend fun unreadCount(me: Uuid): Long = suspendTransaction(db) { countUnread(me) }

    /** Marks everything read and tells the user's other open sessions to clear their badge. */
    suspend fun markAllRead(me: Uuid) {
        suspendTransaction(db) {
            Notifications.update({ (Notifications.recipientId eq me) and Notifications.readAt.isNull() }) { it[readAt] = now() }
        }
        registry.send(listOf(me), RealtimeEvent.Badge(0))
    }

    /** A token belongs to one install; registering it again (e.g. another account on the device) moves it. */
    suspend fun registerDevice(me: Uuid, token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty() || trimmed.length > TOKEN_MAX) throw ApiException(HttpStatusCode.BadRequest, "INVALID_TOKEN", "Invalid device token")
        suspendTransaction(db) {
            DeviceTokens.upsert {
                it[fcmToken] = trimmed
                it[userId] = me
                it[updatedAt] = now()
            }
        }
    }

    suspend fun unregisterDevice(me: Uuid, token: String) {
        suspendTransaction(db) { DeviceTokens.deleteWhere { (fcmToken eq token.trim()) and (userId eq me) } }
    }

    /** Call after the transaction that created notification [id] has committed. */
    suspend fun deliver(id: Uuid) {
        val (recipient, dto, unread) = suspendTransaction(db) {
            val row = joined().selectAll().where { Notifications.id eq id }.singleOrNull() ?: return@suspendTransaction null
            val recipient = row[Notifications.recipientId]
            Triple(recipient, row.toDto(), countUnread(recipient))
        } ?: return
        registry.send(listOf(recipient), RealtimeEvent.NotificationNew(dto, unread))
        if (!registry.isOnline(recipient)) pushLater(recipient, dto.toPush())
    }

    /** DMs: an open socket already delivered `message.new`; otherwise push. Messages don't add activity rows. */
    fun deliverMessage(recipient: Uuid, senderUsername: String, body: String) {
        if (registry.isOnline(recipient)) return
        pushLater(recipient, PushMessage(senderUsername, body.take(PREVIEW_MAX), "insta://chat/$senderUsername", "chat:$senderUsername", NotificationType.MESSAGE))
    }

    private fun pushLater(recipient: Uuid, message: PushMessage) {
        pushScope.launch {
            runCatching {
                val tokens = suspendTransaction(db) {
                    DeviceTokens.select(DeviceTokens.fcmToken).where { DeviceTokens.userId eq recipient }.map { it[DeviceTokens.fcmToken] }
                }
                if (tokens.isEmpty()) return@launch
                val dead = push.send(tokens, message)
                if (dead.isNotEmpty()) suspendTransaction(db) { DeviceTokens.deleteWhere { fcmToken inList dead } }
            }.onFailure { log.warn("Push delivery failed", it) }
        }
    }

    override fun close() = pushScope.cancel()

    private fun countUnread(me: Uuid): Long =
        Notifications.selectAll().where { (Notifications.recipientId eq me) and Notifications.readAt.isNull() }.count()

    private fun joined() = Notifications
        .join(Users, JoinType.INNER, Notifications.actorId, Users.id)
        .join(Posts, JoinType.LEFT, Notifications.postId, Posts.id)
        .join(Comments, JoinType.LEFT, Notifications.commentId, Comments.id)

    private fun now() = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))

    private fun ResultRow.toDto() = NotificationDto(
        id = this[Notifications.id].toString(),
        type = this[Notifications.type],
        actor = AuthorDto(this[Users.id].toString(), this[Users.username], this[Users.displayName], this[Users.avatarMediaId]?.let { mediaUrl(it, "thumb") }),
        postId = this[Notifications.postId]?.toString(),
        postThumbUrl = this.getOrNull(Posts.mediaId)?.let { mediaUrl(it, "thumb") },
        commentId = this[Notifications.commentId]?.toString(),
        commentBody = this.getOrNull(Comments.body),
        read = this[Notifications.readAt] != null,
        createdAt = this[Notifications.createdAt].toInstant().toString(),
    )

    private fun NotificationDto.toPush(): PushMessage {
        val who = actor.username
        return when (type) {
            NotificationType.LIKE -> PushMessage(who, "$who liked your post.", "insta://post/$postId", "post:$postId", type)
            NotificationType.COMMENT -> PushMessage(who, "$who commented: ${commentBody.orEmpty().take(PREVIEW_MAX)}", "insta://post/$postId", "post:$postId", type)
            else -> PushMessage(who, "$who started following you.", "insta://user/$who", "follow:$who", type)
        }
    }

    companion object {
        const val TOKEN_MAX = 512
        const val PREVIEW_MAX = 120
    }
}

/** Mount inside `authenticate`. */
fun Route.notificationRoutes() {
    val notifications by inject<NotificationService>()

    get("/notifications") { call.respond(notifications.list(call.currentUserId(), call.pageRequest(defaultLimit = 30))) }
    get("/notifications/unread-count") { call.respond(UnreadCountDto(notifications.unreadCount(call.currentUserId()))) }
    post("/notifications/read") {
        notifications.markAllRead(call.currentUserId())
        call.respond(HttpStatusCode.NoContent)
    }
    put("/me/devices/{token}") {
        notifications.registerDevice(call.currentUserId(), call.parameters["token"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/me/devices/{token}") {
        notifications.unregisterDevice(call.currentUserId(), call.parameters["token"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
}
