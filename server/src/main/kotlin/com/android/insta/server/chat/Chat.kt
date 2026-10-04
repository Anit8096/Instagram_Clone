package com.android.insta.server.chat

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.AppJson
import com.android.insta.server.common.Cursor
import com.android.insta.server.common.Page
import com.android.insta.server.common.PageRequest
import com.android.insta.server.common.ValidationException
import com.android.insta.server.common.pageRequest
import com.android.insta.server.common.uuidParam
import com.android.insta.server.db.ConversationReads
import com.android.insta.server.db.Conversations
import com.android.insta.server.db.Messages
import com.android.insta.server.db.Users
import com.android.insta.server.media.mediaUrl
import com.android.insta.server.notifications.NotificationDto
import com.android.insta.server.notifications.NotificationService
import com.android.insta.server.plugins.currentUserId
import com.android.insta.server.users.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.ktor.ext.inject
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

@Serializable
data class PeerDto(val id: String, val username: String, val displayName: String, val avatarUrl: String?)

@Serializable
data class MessageDto(val id: String, val conversationId: String, val senderId: String, val body: String, val createdAt: String)

@Serializable
data class ConversationDto(
    val id: String,
    val peer: PeerDto,
    val lastMessage: MessageDto? = null,
    val unreadCount: Int = 0,
    /** When the other person last read this conversation: drives the "Seen" receipt. */
    val peerLastReadAt: String? = null,
)

@Serializable
data class OpenConversationRequest(val username: String)

@Serializable
data class SendMessageRequest(val body: String)

/** Pushed over `/ws` as JSON with a `type` discriminator. */
@Serializable
sealed interface RealtimeEvent {
    @Serializable @SerialName("message.new")
    data class MessageNew(val message: MessageDto) : RealtimeEvent

    @Serializable @SerialName("message.read")
    data class MessageRead(val conversationId: String, val userId: String, val readAt: String) : RealtimeEvent

    @Serializable @SerialName("notification.new")
    data class NotificationNew(val notification: NotificationDto, val unreadCount: Long) : RealtimeEvent

    /** Unread activity count changed elsewhere (e.g. marked read on another device). */
    @Serializable @SerialName("badge")
    data class Badge(val unreadNotifications: Long) : RealtimeEvent
}

/** Open WebSocket sessions per user (a user may be connected from several devices). */
class ConnectionRegistry {
    private val sessions = ConcurrentHashMap<Uuid, MutableSet<DefaultWebSocketServerSession>>()

    fun add(userId: Uuid, session: DefaultWebSocketServerSession) {
        sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun remove(userId: Uuid, session: DefaultWebSocketServerSession) {
        sessions[userId]?.remove(session)
    }

    fun isOnline(userId: Uuid): Boolean = !sessions[userId].isNullOrEmpty()

    suspend fun send(userIds: Collection<Uuid>, event: RealtimeEvent) {
        val text = AppJson.encodeToString(RealtimeEvent.serializer(), event)
        userIds.toSet().flatMap { sessions[it].orEmpty() }.forEach { session ->
            runCatching { session.send(Frame.Text(text)) } // a dead socket is cleaned up by its own handler
        }
    }
}

class ChatService(
    private val db: Database,
    private val users: UserRepository,
    private val registry: ConnectionRegistry,
    private val clock: Clock,
    private val notifications: NotificationService,
) {
    /** Get-or-create the 1:1 conversation; `user_a < user_b` keeps the pair unique whoever starts it. */
    suspend fun open(me: Uuid, username: String): ConversationDto {
        val peer = users.findByUsername(username.trim().lowercase())
            ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "User not found")
        if (peer.id == me) throw ApiException(HttpStatusCode.BadRequest, "CANNOT_MESSAGE_SELF", "You can't message yourself")
        val (a, b) = if (me.toString() < peer.id.toString()) me to peer.id else peer.id to me
        val id = suspendTransaction(db) {
            Conversations.insertIgnore {
                it[Conversations.id] = Uuid.random()
                it[userA] = a
                it[userB] = b
                it[createdAt] = now()
            }
            Conversations.selectAll().where { (Conversations.userA eq a) and (Conversations.userB eq b) }.single()[Conversations.id]
        }
        return inbox(me).first { it.id == id.toString() }
    }

    suspend fun inbox(me: Uuid): List<ConversationDto> = suspendTransaction(db) {
        Conversations.selectAll().where { (Conversations.userA eq me) or (Conversations.userB eq me) }.toList().map { conv ->
            val id = conv[Conversations.id]
            val peerId = if (conv[Conversations.userA] == me) conv[Conversations.userB] else conv[Conversations.userA]
            val peer = Users.selectAll().where { Users.id eq peerId }.single()
            val last = Messages.selectAll().where { Messages.conversationId eq id }
                .orderBy(Messages.createdAt to SortOrder.DESC, Messages.id to SortOrder.DESC).limit(1).singleOrNull()
            val myRead = lastRead(id, me)
            val unread = Messages.selectAll().where {
                val base = (Messages.conversationId eq id) and (Messages.senderId neq me)
                if (myRead == null) base else base and (Messages.createdAt greater myRead)
            }.count().toInt()
            Triple(conv, last, ConversationDto(
                id = id.toString(),
                peer = PeerDto(peerId.toString(), peer[Users.username], peer[Users.displayName], peer[Users.avatarMediaId]?.let { mediaUrl(it, "thumb") }),
                lastMessage = last?.toMessage(),
                unreadCount = unread,
                peerLastReadAt = lastRead(id, peerId)?.toInstant()?.toString(),
            ))
        }.sortedByDescending { (conv, last, _) -> (last?.get(Messages.createdAt) ?: conv[Conversations.createdAt]).toInstant() }
            .map { it.third }
    }

    /** Newest first; the client reverses for display. */
    suspend fun history(me: Uuid, conversationId: Uuid, page: PageRequest): Page<MessageDto> = suspendTransaction(db) {
        requireMember(me, conversationId)
        val rows = Messages.selectAll().where {
            val cursor = page.cursor
            val base = Messages.conversationId eq conversationId
            if (cursor == null) base
            else base and ((Messages.createdAt less cursor.createdAt) or ((Messages.createdAt eq cursor.createdAt) and (Messages.id less cursor.id)))
        }.orderBy(Messages.createdAt to SortOrder.DESC, Messages.id to SortOrder.DESC).limit(page.limit + 1).toList()
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { Cursor(it[Messages.createdAt], it[Messages.id]).encode() } else null
        Page(items.map { it.toMessage() }, next)
    }

    /** Idempotent send; new messages are pushed to both participants' open sockets. */
    suspend fun send(me: Uuid, conversationId: Uuid, messageId: Uuid, rawBody: String): Pair<MessageDto, Boolean> {
        val body = rawBody.trim()
        if (body.isEmpty() || body.length > BODY_MAX) throw ValidationException(mapOf("body" to "1-$BODY_MAX characters"))
        val (message, created, members) = suspendTransaction(db) {
            val members = requireMember(me, conversationId)
            Messages.selectAll().where { Messages.id eq messageId }.singleOrNull()?.let { existing ->
                if (existing[Messages.senderId] != me || existing[Messages.conversationId] != conversationId) {
                    throw ApiException(HttpStatusCode.Conflict, "MESSAGE_ID_CONFLICT", "Message id already used")
                }
                return@suspendTransaction Triple(existing.toMessage(), false, members)
            }
            Messages.insert {
                it[id] = messageId
                it[Messages.conversationId] = conversationId
                it[senderId] = me
                it[Messages.body] = body
                it[createdAt] = now()
            }
            Triple(Messages.selectAll().where { Messages.id eq messageId }.single().toMessage(), true, members)
        }
        if (created) {
            registry.send(members, RealtimeEvent.MessageNew(message))
            val recipient = members.first { it != me }
            users.findById(me)?.let { sender -> notifications.deliverMessage(recipient, sender.username, message.body) }
        }
        return message to created
    }

    suspend fun markRead(me: Uuid, conversationId: Uuid) {
        val readAt = now()
        val members = suspendTransaction(db) {
            val members = requireMember(me, conversationId)
            ConversationReads.upsert {
                it[ConversationReads.conversationId] = conversationId
                it[userId] = me
                it[lastReadAt] = readAt
            }
            members
        }
        registry.send(members, RealtimeEvent.MessageRead(conversationId.toString(), me.toString(), readAt.toInstant().toString()))
    }

    private fun JdbcTransaction.requireMember(me: Uuid, conversationId: Uuid): List<Uuid> {
        val conv = Conversations.selectAll().where { Conversations.id eq conversationId }.singleOrNull()
        val members = conv?.let { listOf(it[Conversations.userA], it[Conversations.userB]) }
        if (members == null || me !in members) throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Conversation not found")
        return members
    }

    private fun JdbcTransaction.lastRead(conversationId: Uuid, userId: Uuid): OffsetDateTime? =
        ConversationReads.selectAll().where { (ConversationReads.conversationId eq conversationId) and (ConversationReads.userId eq userId) }
            .singleOrNull()?.get(ConversationReads.lastReadAt)

    private fun now() = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))

    private fun ResultRow.toMessage() = MessageDto(
        this[Messages.id].toString(), this[Messages.conversationId].toString(), this[Messages.senderId].toString(),
        this[Messages.body], this[Messages.createdAt].toInstant().toString(),
    )

    companion object {
        const val BODY_MAX = 2000
    }
}

/** Mount inside `authenticate`: the WebSocket handshake carries the bearer token like any request. */
fun Route.chatRoutes() {
    val chat by inject<ChatService>()
    val registry by inject<ConnectionRegistry>()

    get("/conversations") { call.respond(chat.inbox(call.currentUserId())) }
    post("/conversations") { call.respond(chat.open(call.currentUserId(), call.receive<OpenConversationRequest>().username)) }
    get("/conversations/{id}/messages") { call.respond(chat.history(call.currentUserId(), call.uuidParam("id"), call.pageRequest(defaultLimit = 50))) }
    put("/conversations/{id}/messages/{messageId}") {
        val (message, created) = chat.send(call.currentUserId(), call.uuidParam("id"), call.uuidParam("messageId"), call.receive<SendMessageRequest>().body)
        call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, message)
    }
    post("/conversations/{id}/read") {
        chat.markRead(call.currentUserId(), call.uuidParam("id"))
        call.respond(HttpStatusCode.NoContent)
    }
    webSocket("/ws") {
        val userId = call.currentUserId()
        registry.add(userId, this)
        try {
            for (frame in incoming) Unit // server → client only; pings are handled by the plugin
        } finally {
            registry.remove(userId, this)
        }
    }
}
