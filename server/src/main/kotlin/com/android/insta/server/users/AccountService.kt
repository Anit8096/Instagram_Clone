package com.android.insta.server.users

import com.android.insta.server.auth.GoogleTokenVerifier
import com.android.insta.server.auth.PasswordHasher
import com.android.insta.server.common.ApiException
import com.android.insta.server.db.Comments
import com.android.insta.server.db.Likes
import com.android.insta.server.db.Media
import com.android.insta.server.db.Posts
import com.android.insta.server.db.Users
import com.android.insta.server.media.MediaStorage
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Re-authentication for destructive actions: the password, or a fresh Google ID token for Google-only accounts. */
@Serializable
data class DeleteAccountRequest(val password: String? = null, val googleIdToken: String? = null)

class AccountService(
    private val db: Database,
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val google: GoogleTokenVerifier,
    private val storage: MediaStorage,
) {
    /**
     * Hard delete. Foreign keys cascade the user's posts, media rows, comments, likes, follows, conversations,
     * notifications, device tokens and refresh tokens (so every session dies at its next refresh). Counters on other
     * people's posts are corrected in the same transaction; files are removed only after the commit.
     */
    suspend fun delete(userId: Uuid, request: DeleteAccountRequest) {
        val user = users.findById(userId) ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Account no longer exists")
        reauthenticate(user, request)

        val fileKeys = suspendTransaction(db) {
            val keys = Media.select(Media.fullPath, Media.thumbPath).where { Media.ownerId eq userId }
                .flatMap { listOf(it[Media.fullPath], it[Media.thumbPath]) }

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
            keys
        }
        storage.delete(fileKeys)
    }

    private suspend fun reauthenticate(user: UserRecord, request: DeleteAccountRequest) {
        val ok = when {
            user.passwordHash != null -> request.password?.let { password ->
                withContext(Dispatchers.Default) { hasher.verify(password, user.passwordHash) }
            } ?: false
            user.googleSub != null -> request.googleIdToken?.let { token ->
                runCatching { google.verify(token) }.getOrNull()?.subject == user.googleSub
            } ?: false
            else -> false
        }
        // 403, not 401: a 401 would make clients treat it as an expired access token and try a refresh.
        if (!ok) throw ApiException(HttpStatusCode.Forbidden, "REAUTH_FAILED", "Confirm it's you to delete your account")
    }
}
