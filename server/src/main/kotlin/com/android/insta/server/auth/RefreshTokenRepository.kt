package com.android.insta.server.auth

import com.android.insta.server.db.RefreshTokens
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

sealed interface RotationResult {
    data class Rotated(val userId: Uuid) : RotationResult

    /** A token that was already rotated or logged out was presented again: its family is now revoked. */
    data object Reused : RotationResult

    data object Invalid : RotationResult
}

class RefreshTokenRepository(private val db: Database) {

    suspend fun insert(userId: Uuid, familyId: Uuid, tokenHash: String, expiresAt: Instant) {
        suspendTransaction(db) { insertToken(userId, familyId, tokenHash, expiresAt) }
    }

    /**
     * Atomically swaps [presentedHash] for [newHash] in the same family. Runs in one transaction,
     * and the conditional revoke (`revoked_at IS NULL`) makes concurrent use of the same token
     * count as reuse rather than producing two valid successors.
     */
    suspend fun rotate(presentedHash: String, newHash: String, newExpiry: Instant, now: Instant): RotationResult =
        suspendTransaction(db) {
            val row = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq presentedHash }.forUpdate().singleOrNull()
                ?: return@suspendTransaction RotationResult.Invalid
            val familyId = row[RefreshTokens.familyId]
            val userId = row[RefreshTokens.userId]

            if (row[RefreshTokens.revokedAt] != null) {
                revokeFamilyIn(familyId, now)
                return@suspendTransaction RotationResult.Reused
            }
            if (row[RefreshTokens.expiresAt].toInstant() <= now) return@suspendTransaction RotationResult.Invalid

            val revoked = RefreshTokens.update({
                (RefreshTokens.id eq row[RefreshTokens.id]) and RefreshTokens.revokedAt.isNull()
            }) { it[revokedAt] = now.utc() }
            if (revoked == 0) {
                revokeFamilyIn(familyId, now)
                return@suspendTransaction RotationResult.Reused
            }
            insertToken(userId, familyId, newHash, newExpiry)
            RotationResult.Rotated(userId)
        }

    /** Revokes the family of [tokenHash], if any (logout). */
    suspend fun revokeFamilyOf(tokenHash: String, now: Instant) {
        suspendTransaction(db) {
            val familyId = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq tokenHash }
                .singleOrNull()?.get(RefreshTokens.familyId) ?: return@suspendTransaction
            revokeFamilyIn(familyId, now)
        }
    }

    private fun JdbcTransaction.insertToken(userId: Uuid, familyId: Uuid, tokenHash: String, expiresAt: Instant) {
        RefreshTokens.insert {
            it[id] = Uuid.random()
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.familyId] = familyId
            it[RefreshTokens.tokenHash] = tokenHash
            it[RefreshTokens.expiresAt] = expiresAt.utc()
            it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    private fun JdbcTransaction.revokeFamilyIn(familyId: Uuid, now: Instant) {
        RefreshTokens.update({ (RefreshTokens.familyId eq familyId) and RefreshTokens.revokedAt.isNull() }) {
            it[revokedAt] = now.utc()
        }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
