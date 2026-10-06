package com.android.insta.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mappings for tables created by Flyway (db/migration). Flyway owns the schema;
// these objects only describe it for queries, so constraints live in SQL.

object Users : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 30)
    val email = varchar("email", 254).nullable()
    val googleSub = varchar("google_sub", 255)
    val phone = varchar("phone_e164", 16)
    val displayName = varchar("display_name", 60)
    val bio = varchar("bio", 150)
    val avatarMediaId = uuid("avatar_media_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Media : Table("media") {
    val id = uuid("id")
    val ownerId = uuid("owner_id")
    val kind = varchar("kind", 16)
    val fullPath = varchar("full_path", 512)
    val thumbPath = varchar("thumb_path", 512)
    val width = integer("width")
    val height = integer("height")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Posts : Table("posts") {
    val id = uuid("id")
    val authorId = uuid("author_id")
    val mediaId = uuid("media_id")
    val caption = varchar("caption", 2200)
    val likeCount = integer("like_count")
    val commentCount = integer("comment_count")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Follows : Table("follows") {
    val followerId = uuid("follower_id")
    val followeeId = uuid("followee_id")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(followerId, followeeId)
}

object RefreshTokens : Table("refresh_tokens") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val familyId = uuid("family_id")
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Likes : Table("likes") {
    val userId = uuid("user_id")
    val postId = uuid("post_id")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(userId, postId)
}

object Comments : Table("comments") {
    val id = uuid("id")
    val postId = uuid("post_id")
    val authorId = uuid("author_id")
    val body = varchar("body", 1000)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Conversations : Table("conversations") {
    val id = uuid("id")
    val userA = uuid("user_a")
    val userB = uuid("user_b")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Messages : Table("messages") {
    val id = uuid("id")
    val conversationId = uuid("conversation_id")
    val senderId = uuid("sender_id")
    val body = varchar("body", 2000)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object ConversationReads : Table("conversation_reads") {
    val conversationId = uuid("conversation_id")
    val userId = uuid("user_id")
    val lastReadAt = timestampWithTimeZone("last_read_at")

    override val primaryKey = PrimaryKey(conversationId, userId)
}

object Notifications : Table("notifications") {
    val id = uuid("id")
    val recipientId = uuid("recipient_id")
    val actorId = uuid("actor_id")
    val type = varchar("type", 16)
    val postId = uuid("post_id").nullable()
    val commentId = uuid("comment_id").nullable()
    val readAt = timestampWithTimeZone("read_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object OtpChallenges : Table("otp_challenges") {
    val id = uuid("id")
    val phone = varchar("phone_e164", 16)
    val purpose = varchar("purpose", 16)
    val userId = uuid("user_id").nullable()
    val onboardingSubject = varchar("onboarding_subject", 255).nullable()
    val codeHash = varchar("code_hash", 64)
    val attempts = integer("attempts")
    val expiresAt = timestampWithTimeZone("expires_at")
    val consumedAt = timestampWithTimeZone("consumed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object DeviceTokens : Table("device_tokens") {
    val fcmToken = varchar("fcm_token", 512)
    val userId = uuid("user_id")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(fcmToken)
}

object Jobs : Table("jobs") {
    val id = uuid("id")
    val type = varchar("type", 64)
    val payload = text("payload")
    val status = varchar("status", 16)
    val attempts = integer("attempts")
    val maxAttempts = integer("max_attempts")
    val runAt = timestampWithTimeZone("run_at")
    val dedupeKey = varchar("dedupe_key", 128).nullable()
    val dispatchedAt = timestampWithTimeZone("dispatched_at").nullable()
    val lastError = text("last_error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}
