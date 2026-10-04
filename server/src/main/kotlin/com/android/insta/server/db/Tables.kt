package com.android.insta.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mappings for tables created by Flyway (db/migration). Flyway owns the schema;
// these objects only describe it for queries, so constraints live in SQL.

object Users : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 30)
    val email = varchar("email", 254).nullable()
    val passwordHash = text("password_hash").nullable()
    val googleSub = varchar("google_sub", 255).nullable()
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
