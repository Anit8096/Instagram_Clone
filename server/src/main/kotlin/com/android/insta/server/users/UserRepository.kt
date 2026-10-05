package com.android.insta.server.users

import com.android.insta.server.common.ApiException
import com.android.insta.server.db.Users
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

/** Every account has a Google identity and a verified phone (E.164); email is Google's, when it gave one. */
data class UserRecord(
    val id: Uuid,
    val username: String,
    val email: String?,
    val googleSub: String,
    val phone: String,
    val displayName: String,
    val bio: String,
    val avatarMediaId: Uuid?,
    val createdAt: OffsetDateTime,
)

data class NewUser(
    val username: String,
    val email: String?,
    val googleSub: String,
    val phone: String,
    val displayName: String,
)

class UserRepository(private val db: Database) {

    suspend fun findById(id: Uuid): UserRecord? = findOne { Users.id eq id }

    suspend fun findByUsername(username: String): UserRecord? = findOne { Users.username eq username }

    suspend fun findByGoogleSub(sub: String): UserRecord? = findOne { Users.googleSub eq sub }

    suspend fun findByPhone(phone: String): UserRecord? = findOne { Users.phone eq phone }

    suspend fun usernameExists(username: String): Boolean = findByUsername(username) != null

    /** Inserts a user; maps unique-constraint races to 409s with a field-specific code. */
    suspend fun create(user: NewUser): UserRecord {
        val id = Uuid.random()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        try {
            suspendTransaction(db) {
                Users.insert {
                    it[Users.id] = id
                    it[username] = user.username
                    it[email] = user.email
                    it[googleSub] = user.googleSub
                    it[phone] = user.phone
                    it[displayName] = user.displayName
                    it[bio] = ""
                    it[createdAt] = now
                }
            }
        } catch (e: ExposedSQLException) {
            throw uniqueViolationToApi(e) ?: e
        }
        return UserRecord(id, user.username, user.email, user.googleSub, user.phone, user.displayName, "", null, now)
    }

    suspend fun updatePhone(userId: Uuid, phone: String) {
        try {
            suspendTransaction(db) { Users.update({ Users.id eq userId }) { it[Users.phone] = phone } }
        } catch (e: ExposedSQLException) {
            throw uniqueViolationToApi(e) ?: e
        }
    }

    private suspend fun findOne(where: () -> org.jetbrains.exposed.v1.core.Op<Boolean>): UserRecord? =
        suspendTransaction(db) {
            Users.selectAll().where(where).limit(1).map { it.toUser() }.singleOrNull()
        }

    private fun ResultRow.toUser() = UserRecord(
        id = this[Users.id],
        username = this[Users.username],
        email = this[Users.email],
        googleSub = this[Users.googleSub],
        phone = this[Users.phone],
        displayName = this[Users.displayName],
        bio = this[Users.bio],
        avatarMediaId = this[Users.avatarMediaId],
        createdAt = this[Users.createdAt],
    )

    private fun uniqueViolationToApi(e: ExposedSQLException): ApiException? {
        if (e.sqlState != "23505") return null
        val text = e.cause?.message.orEmpty()
        return when {
            "users_username_key" in text -> usernameTaken()
            "users_phone_e164_key" in text -> phoneInUse()
            "users_google_sub_key" in text -> ApiException(HttpStatusCode.Conflict, "GOOGLE_ACCOUNT_LINKED", "Google account is already linked")
            "users_email_key" in text -> ApiException(HttpStatusCode.Conflict, "EMAIL_TAKEN", "This Google email is already linked to another account")
            else -> null
        }
    }

    companion object {
        fun usernameTaken() = ApiException(HttpStatusCode.Conflict, "USERNAME_TAKEN", "Username is already taken")
        fun phoneInUse() = ApiException(HttpStatusCode.Conflict, "PHONE_IN_USE", "This number is already linked to another account")
    }
}
