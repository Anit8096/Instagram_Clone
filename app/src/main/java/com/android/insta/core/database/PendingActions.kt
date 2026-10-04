package com.android.insta.core.database

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * A user action waiting to reach the server (offline queue). [id] doubles as the server-side id where one is
 * needed (comments), so replays are idempotent. Sent strictly in [createdAt] order.
 */
@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val postId: String,
    val body: String? = null,
    val createdAt: Long,
    val state: String = ActionState.PENDING,
    val error: String? = null,
)

object ActionType {
    const val LIKE = "like"
    const val UNLIKE = "unlike"
    const val COMMENT = "comment"
    const val MESSAGE = "message"
}

object ActionState {
    const val PENDING = "pending"
    const val FAILED = "failed"
}

@Dao
interface PendingActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(action: PendingActionEntity)

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun get(id: String): PendingActionEntity?

    @Query("SELECT * FROM pending_actions WHERE state = 'pending' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextPending(): PendingActionEntity?

    @Query("SELECT * FROM pending_actions WHERE type IN ('like', 'unlike') AND state = 'pending'")
    suspend fun pendingLikes(): List<PendingActionEntity>

    @Query("SELECT * FROM pending_actions WHERE type = 'comment' AND postId = :postId ORDER BY createdAt")
    fun observeComments(postId: String): Flow<List<PendingActionEntity>>

    /** Queued items of [type] for one target (post id for comments, conversation id for messages). */
    @Query("SELECT * FROM pending_actions WHERE type = :type AND postId = :targetId ORDER BY createdAt")
    fun observeByType(type: String, targetId: String): Flow<List<PendingActionEntity>>

    @Query("DELETE FROM pending_actions WHERE postId = :postId AND type IN ('like', 'unlike') AND state = 'pending'")
    suspend fun deletePendingLikes(postId: String)

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_actions")
    suspend fun deleteAll()
}
