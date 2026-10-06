package com.android.insta.server.users

import com.android.insta.server.auth.GoogleTokenVerifier
import com.android.insta.server.auth.OtpChallengeDto
import com.android.insta.server.auth.OtpOwner
import com.android.insta.server.auth.OtpPurpose
import com.android.insta.server.auth.OtpService
import com.android.insta.server.auth.PhoneNumbers
import com.android.insta.server.auth.PhoneOtpRequest
import com.android.insta.server.auth.VerifyOtpRequest
import com.android.insta.server.common.ApiException
import com.android.insta.server.common.ValidationException
import com.android.insta.server.db.Comments
import com.android.insta.server.db.Follows
import com.android.insta.server.db.Likes
import com.android.insta.server.db.Media
import com.android.insta.server.db.Posts
import com.android.insta.server.db.Users
import com.android.insta.server.media.MediaStorage
import com.android.insta.server.redis.Cache
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Re-authentication for deleting the account: a code from `POST /me/delete/otp` (sent to the account's phone), or a
 * fresh Google ID token for the account's Google identity.
 */
@Serializable
data class DeleteAccountRequest(val challengeId: String? = null, val code: String? = null, val googleIdToken: String? = null)

class AccountService(
    private val db: Database,
    private val users: UserRepository,
    private val otp: OtpService,
    private val google: GoogleTokenVerifier,
    private val storage: MediaStorage,
    private val cache: Cache,
) {
    suspend fun requestDeleteOtp(userId: Uuid): OtpChallengeDto {
        val user = users.findById(userId) ?: throw accountGone()
        return otp.request(user.phone, OtpPurpose.DELETE_ACCOUNT, OtpOwner.User(userId))
    }

    /** Change phone, step 1: the code goes to the **new** number, proving the user owns it. */
    suspend fun requestPhoneChange(userId: Uuid, request: PhoneOtpRequest): OtpChallengeDto {
        val user = users.findById(userId) ?: throw accountGone()
        val phone = PhoneNumbers.normalize(request.phone)
        if (phone == user.phone) throw ValidationException(mapOf("phone" to "This is already your number"))
        if (users.findByPhone(phone) != null) throw UserRepository.phoneInUse()
        return otp.request(phone, OtpPurpose.CHANGE_PHONE, OtpOwner.User(userId))
    }

    suspend fun confirmPhoneChange(userId: Uuid, request: VerifyOtpRequest): MeDto {
        val phone = otp.verify(request.challengeId, request.code, OtpPurpose.CHANGE_PHONE, OtpOwner.User(userId))
        users.updatePhone(userId, phone) // PHONE_IN_USE if someone else claimed it meanwhile
        return (users.findById(userId) ?: throw accountGone()).toMeDto()
    }

    /**
     * Hard delete. Foreign keys cascade the user's posts, media rows, comments, likes, follows, conversations,
     * notifications, device tokens and refresh tokens (so every session dies at its next refresh). Counters on other
     * people's posts are corrected in the same transaction; files are removed only after the commit.
     */
    suspend fun delete(userId: Uuid, request: DeleteAccountRequest) {
        val user = users.findById(userId) ?: throw accountGone()
        reauthenticate(user, request)

        val (fileKeys, peers) = suspendTransaction(db) {
            val keys = Media.select(Media.fullPath, Media.thumbPath).where { Media.ownerId eq userId }
                .flatMap { listOf(it[Media.fullPath], it[Media.thumbPath]) }
            // Everyone whose follower/following count drops when the follows cascade away.
            val peers = Follows.select(Follows.followerId, Follows.followeeId)
                .where { (Follows.followerId eq userId) or (Follows.followeeId eq userId) }
                .map { if (it[Follows.followerId] == userId) it[Follows.followeeId] else it[Follows.followerId] }
                .distinct()

            // Likes and comments on *other* people's posts: their posts lose the counts (own posts go away anyway).
            Likes.join(Posts, org.jetbrains.exposed.v1.core.JoinType.INNER, Likes.postId, Posts.id)
                .select(Likes.postId).where { (Likes.userId eq userId) and (Posts.authorId neq userId) }
                .map { it[Likes.postId] }
                .forEach { postId -> Posts.update({ Posts.id eq postId }) { it[likeCount] = likeCount - 1 } }
            Comments.join(Posts, org.jetbrains.exposed.v1.core.JoinType.INNER, Comments.postId, Posts.id)
                .select(Comments.postId).where { (Comments.authorId eq userId) and (Posts.authorId neq userId) }
                .map { it[Comments.postId] }
                .groupingBy { it }.eachCount()
                .forEach { (postId, n) -> Posts.update({ Posts.id eq postId }) { it[commentCount] = commentCount - n } }

            Users.deleteWhere { Users.id eq userId }
            keys to peers
        }
        storage.delete(fileKeys)
        cache.invalidateCounts(userId, *peers.toTypedArray())
    }

    private suspend fun reauthenticate(user: UserRecord, request: DeleteAccountRequest) {
        val ok = when {
            // Wrong, expired or reused codes surface as their specific 400 OTP_* errors.
            request.challengeId != null && request.code != null -> {
                otp.verify(request.challengeId, request.code, OtpPurpose.DELETE_ACCOUNT, OtpOwner.User(user.id))
                true
            }
            request.googleIdToken != null -> runCatching { google.verify(request.googleIdToken) }.getOrNull()?.subject == user.googleSub
            else -> false
        }
        // 403, not 401: a 401 would make clients treat it as an expired access token and try a refresh.
        if (!ok) throw ApiException(HttpStatusCode.Forbidden, "REAUTH_FAILED", "Confirm it's you to delete your account")
    }

    private fun accountGone() = ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Account no longer exists")
}
