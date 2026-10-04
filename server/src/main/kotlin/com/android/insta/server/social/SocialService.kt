package com.android.insta.server.social

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.Cursor
import com.android.insta.server.common.Page
import com.android.insta.server.common.PageRequest
import com.android.insta.server.db.Follows
import com.android.insta.server.db.Users
import com.android.insta.server.media.mediaUrl
import com.android.insta.server.notifications.NotificationService
import com.android.insta.server.notifications.NotificationType
import com.android.insta.server.notifications.recordNotification
import com.android.insta.server.notifications.removeFollowNotification
import com.android.insta.server.users.UserRepository
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

@Serializable
data class UserSummaryDto(val id: String, val username: String, val displayName: String, val avatarUrl: String?, val isFollowing: Boolean)

@Serializable
data class FollowStateDto(val isFollowing: Boolean, val followerCount: Long)

class SocialService(
    private val db: Database,
    private val users: UserRepository,
    private val clock: Clock,
    private val notifications: NotificationService,
) {

    /** Idempotent: following twice is still "following". */
    suspend fun follow(followerId: Uuid, username: String): FollowStateDto {
        val target = targetId(username)
        if (target == followerId) throw ApiException(HttpStatusCode.BadRequest, "CANNOT_FOLLOW_SELF", "You can't follow yourself")
        val notificationId = suspendTransaction(db) {
            val now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))
            val inserted = Follows.insertIgnore {
                it[Follows.followerId] = followerId
                it[followeeId] = target
                it[createdAt] = now
            }.insertedCount
            if (inserted > 0) recordNotification(target, followerId, NotificationType.FOLLOW, now) else null
        }
        notificationId?.let { notifications.deliver(it) }
        return FollowStateDto(true, followerCount(target))
    }

    /** Idempotent: unfollowing someone you don't follow is fine. */
    suspend fun unfollow(followerId: Uuid, username: String): FollowStateDto {
        val target = targetId(username)
        suspendTransaction(db) {
            Follows.deleteWhere { (Follows.followerId eq followerId) and (followeeId eq target) }
            removeFollowNotification(followerId, target)
        }
        return FollowStateDto(false, followerCount(target))
    }

    suspend fun followers(viewerId: Uuid, username: String, page: PageRequest): Page<UserSummaryDto> =
        relationPage(viewerId, page, listed = Follows.followerId) { Follows.followeeId eq targetId(username) }

    suspend fun following(viewerId: Uuid, username: String, page: PageRequest): Page<UserSummaryDto> =
        relationPage(viewerId, page, listed = Follows.followeeId) { Follows.followerId eq targetId(username) }

    /**
     * Username/display-name search. Postgres pg_trgm GIN indexes make the `LIKE '%q%'` scans cheap; results rank
     * exact username, then username prefix, then other matches.
     */
    suspend fun search(viewerId: Uuid, rawQuery: String, limit: Int = 20): List<UserSummaryDto> {
        val q = rawQuery.trim().lowercase().removePrefix("@")
        if (q.isEmpty()) return emptyList()
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val rows = suspendTransaction(db) {
            Users.selectAll()
                .where { (Users.username like pattern) or (Users.displayName.lowerCase() like pattern) }
                .limit(50)
                .map { it }
        }
        val ranked = rows.sortedWith(
            compareBy<ResultRow>(
                { if (it[Users.username] == q) 0 else if (it[Users.username].startsWith(q)) 1 else 2 },
                { it[Users.username].length },
                { it[Users.username] },
            ),
        ).take(limit)
        val following = followingAmong(viewerId, ranked.map { it[Users.id] })
        return ranked.map { it.toSummary(it[Users.id] in following) }
    }

    private suspend fun relationPage(
        viewerId: Uuid,
        page: PageRequest,
        listed: org.jetbrains.exposed.v1.core.Column<Uuid>,
        condition: suspend () -> Op<Boolean>,
    ): Page<UserSummaryDto> {
        val where = condition()
        val rows = suspendTransaction(db) {
            Follows.join(Users, JoinType.INNER, listed, Users.id)
                .selectAll()
                .where {
                    val cursor = page.cursor
                    if (cursor == null) where
                    else where and ((Follows.createdAt less cursor.createdAt) or ((Follows.createdAt eq cursor.createdAt) and (listed less cursor.id)))
                }
                .orderBy(Follows.createdAt to SortOrder.DESC, listed to SortOrder.DESC)
                .limit(page.limit + 1)
                .map { it }
        }
        val items = rows.take(page.limit)
        val following = followingAmong(viewerId, items.map { it[Users.id] })
        val next = if (rows.size > page.limit) items.last().let { Cursor(it[Follows.createdAt], it[listed]).encode() } else null
        return Page(items.map { it.toSummary(it[Users.id] in following) }, next)
    }

    private suspend fun followingAmong(viewerId: Uuid, ids: List<Uuid>): Set<Uuid> {
        if (ids.isEmpty()) return emptySet()
        return suspendTransaction(db) {
            Follows.select(Follows.followeeId)
                .where { (Follows.followerId eq viewerId) and (Follows.followeeId inList ids) }
                .map { it[Follows.followeeId] }
                .toSet()
        }
    }

    private suspend fun followerCount(userId: Uuid): Long =
        suspendTransaction(db) { Follows.selectAll().where { Follows.followeeId eq userId }.count() }

    private suspend fun targetId(username: String): Uuid =
        users.findByUsername(username.trim().lowercase())?.id
            ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "User not found")

    private fun ResultRow.toSummary(isFollowing: Boolean) = UserSummaryDto(
        id = this[Users.id].toString(),
        username = this[Users.username],
        displayName = this[Users.displayName],
        avatarUrl = this[Users.avatarMediaId]?.let { mediaUrl(it, "thumb") },
        isFollowing = isFollowing,
    )
}
