package com.android.insta.server.users

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.ValidationException
import com.android.insta.server.db.Follows
import com.android.insta.server.db.Users
import com.android.insta.server.media.MediaKind
import com.android.insta.server.media.MediaRepository
import com.android.insta.server.media.MediaService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import com.android.insta.server.posts.PostRepository
import kotlin.uuid.Uuid

@Serializable
data class ProfileDto(
    val user: UserDto,
    val postCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val isMe: Boolean,
    val isFollowing: Boolean = false,
)

/** Omitted fields stay unchanged; [removeAvatar] clears the avatar (JSON can't tell null from missing here). */
@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarMediaId: String? = null,
    val removeAvatar: Boolean = false,
)

class ProfileService(
    private val db: Database,
    private val users: UserRepository,
    private val posts: PostRepository,
    private val mediaRepository: MediaRepository,
    private val mediaService: MediaService,
) {
    suspend fun profile(viewerId: Uuid, username: String): ProfileDto {
        val user = users.findByUsername(username.trim().lowercase()) ?: throw notFound()
        val (followers, following) = suspendTransaction(db) {
            Follows.selectAll().where { Follows.followeeId eq user.id }.count() to
                Follows.selectAll().where { Follows.followerId eq user.id }.count()
        }
        val isFollowing = viewerId != user.id && suspendTransaction(db) {
            Follows.selectAll().where { (Follows.followerId eq viewerId) and (Follows.followeeId eq user.id) }.limit(1).any()
        }
        return ProfileDto(user.toDto(), posts.countByAuthor(user.id), followers, following, viewerId == user.id, isFollowing)
    }

    suspend fun userIdFor(username: String): Uuid = users.findByUsername(username.trim().lowercase())?.id ?: throw notFound()

    suspend fun update(userId: Uuid, request: UpdateProfileRequest): MeDto {
        val displayName = request.displayName?.trim()
        val bio = request.bio?.trim()
        val errors = buildMap {
            if (displayName != null && displayName.length > DISPLAY_NAME_MAX) put("displayName", "At most $DISPLAY_NAME_MAX characters")
            if (bio != null && bio.length > BIO_MAX) put("bio", "At most $BIO_MAX characters")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val newAvatarId = request.avatarMediaId?.let {
            runCatching { Uuid.parse(it) }.getOrNull() ?: throw ValidationException(mapOf("avatarMediaId" to "Invalid media id"))
        }

        val oldFiles = suspendTransaction(db) {
            val current = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: throw notFound()
            val previousAvatar = current[Users.avatarMediaId]
            if (newAvatarId != null) {
                val media = with(mediaRepository) { findIn(newAvatarId) }
                if (media == null || media.ownerId != userId || media.kind != MediaKind.AVATAR) {
                    throw ApiException(HttpStatusCode.UnprocessableEntity, "INVALID_MEDIA", "Upload the image with kind=avatar first")
                }
            }
            val avatarChanges = newAvatarId != null || request.removeAvatar
            Users.update({ Users.id eq userId }) {
                if (displayName != null) it[Users.displayName] = displayName
                if (bio != null) it[Users.bio] = bio
                if (avatarChanges) it[avatarMediaId] = newAvatarId
            }
            if (avatarChanges && previousAvatar != null && previousAvatar != newAvatarId) {
                with(mediaRepository) { deleteIn(previousAvatar) }
            } else {
                emptyList()
            }
        }
        mediaService.deleteFiles(oldFiles)
        return users.findById(userId)!!.toMeDto()
    }

    private fun notFound() = ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "User not found")

    private companion object {
        const val DISPLAY_NAME_MAX = 60
        const val BIO_MAX = 150
    }
}
