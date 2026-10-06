package com.android.insta.server.jobs

import com.android.insta.server.db.Jobs
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import kotlin.uuid.Uuid

enum class JobStatus(val value: String) {
    QUEUED("queued"), RUNNING("running"), DONE("done"), DEAD("dead");

    companion object {
        fun parse(raw: String) = entries.first { it.value == raw }
    }
}

/** A job to enqueue. [runAt] null = now. With [dedupeKey], nothing is inserted while one with the key is active. */
data class NewJob(
    val type: String,
    val payload: String = "{}",
    val runAt: OffsetDateTime? = null,
    val dedupeKey: String? = null,
    val maxAttempts: Int = 3,
)

/** An inserted job, to hand to [JobRunner.dispatch] after the enclosing transaction commits. */
data class EnqueuedJob(val id: Uuid, val type: String, val runAt: OffsetDateTime)

data class JobRecord(
    val id: Uuid,
    val type: String,
    val payload: String,
    val status: JobStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val runAt: OffsetDateTime,
    val lastError: String?,
)

/** The `jobs` table: the durable half of the queue. All state changes are guarded by the expected current status. */
class JobRepository(private val db: Database) {

    /** Inserts inside the caller's transaction (outbox). Null when an active job with the same dedupe key exists. */
    fun JdbcTransaction.enqueueIn(job: NewJob, now: OffsetDateTime): EnqueuedJob? {
        val id = Uuid.generateV7()
        val runAt = job.runAt ?: now
        val inserted = Jobs.insertIgnore {
            it[Jobs.id] = id
            it[type] = job.type
            it[payload] = job.payload
            it[status] = JobStatus.QUEUED.value
            it[attempts] = 0
            it[maxAttempts] = job.maxAttempts
            it[Jobs.runAt] = runAt
            it[dedupeKey] = job.dedupeKey
            it[createdAt] = now
            it[updatedAt] = now
        }.insertedCount
        return if (inserted > 0) EnqueuedJob(id, job.type, runAt) else null
    }

    suspend fun find(id: Uuid): JobRecord? = suspendTransaction(db) {
        Jobs.selectAll().where { Jobs.id eq id }.singleOrNull()?.toJob()
    }

    /** queued → running, counting the attempt. Null if someone else has it, it's finished, or it isn't due. */
    suspend fun claim(id: Uuid, now: OffsetDateTime): JobRecord? = suspendTransaction(db) {
        val claimed = Jobs.update({ (Jobs.id eq id) and (Jobs.status eq JobStatus.QUEUED.value) and (Jobs.runAt lessEq now) }) {
            it[status] = JobStatus.RUNNING.value
            it[attempts] = attempts + 1
            it[updatedAt] = now
        }
        if (claimed == 0) null else Jobs.selectAll().where { Jobs.id eq id }.single().toJob()
    }

    suspend fun complete(id: Uuid, now: OffsetDateTime) = finish(id, JobStatus.DONE, null, now)

    suspend fun bury(id: Uuid, error: String, now: OffsetDateTime) = finish(id, JobStatus.DEAD, error, now)

    /** running → queued again at [runAt]; the reconciler publishes it once due. */
    suspend fun retryLater(id: Uuid, error: String, runAt: OffsetDateTime, now: OffsetDateTime) = suspendTransaction(db) {
        Jobs.update({ (Jobs.id eq id) and (Jobs.status eq JobStatus.RUNNING.value) }) {
            it[status] = JobStatus.QUEUED.value
            it[Jobs.runAt] = runAt
            it[lastError] = error.take(ERROR_MAX)
            it[dispatchedAt] = null
            it[updatedAt] = now
        }
    }

    /** Due queued jobs that were never published, or were published [redispatchBefore] ago and still not picked up. */
    suspend fun dueForDispatch(now: OffsetDateTime, redispatchBefore: OffsetDateTime, limit: Int): List<EnqueuedJob> =
        suspendTransaction(db) {
            Jobs.select(Jobs.id, Jobs.type, Jobs.runAt)
                .where {
                    (Jobs.status eq JobStatus.QUEUED.value) and (Jobs.runAt lessEq now) and
                        (Jobs.dispatchedAt.isNull() or (Jobs.dispatchedAt less redispatchBefore))
                }
                .orderBy(Jobs.runAt to SortOrder.ASC)
                .limit(limit)
                .map { EnqueuedJob(it[Jobs.id], it[Jobs.type], it[Jobs.runAt]) }
        }

    suspend fun markDispatched(ids: List<Uuid>, now: OffsetDateTime) {
        if (ids.isEmpty()) return
        suspendTransaction(db) {
            Jobs.update({ (Jobs.id inList ids) and (Jobs.status eq JobStatus.QUEUED.value) }) { it[dispatchedAt] = now }
        }
    }

    /** Running jobs whose worker died (no update for a while) go back to the queue. Returns how many. */
    suspend fun requeueStale(staleBefore: OffsetDateTime, now: OffsetDateTime): Int = suspendTransaction(db) {
        Jobs.update({ (Jobs.status eq JobStatus.RUNNING.value) and (Jobs.updatedAt less staleBefore) }) {
            it[status] = JobStatus.QUEUED.value
            it[runAt] = now
            it[dispatchedAt] = null
            it[lastError] = "Worker stopped responding"
            it[updatedAt] = now
        }
    }

    suspend fun deleteFinishedBefore(before: OffsetDateTime): Int = suspendTransaction(db) {
        Jobs.deleteWhere { (status inList listOf(JobStatus.DONE.value, JobStatus.DEAD.value)) and (updatedAt less before) }
    }

    private suspend fun finish(id: Uuid, status: JobStatus, error: String?, now: OffsetDateTime) = suspendTransaction(db) {
        Jobs.update({ (Jobs.id eq id) and (Jobs.status eq JobStatus.RUNNING.value) }) {
            it[Jobs.status] = status.value
            if (error != null) it[lastError] = error.take(ERROR_MAX)
            it[updatedAt] = now
        }
    }

    private fun ResultRow.toJob() = JobRecord(
        id = this[Jobs.id],
        type = this[Jobs.type],
        payload = this[Jobs.payload],
        status = JobStatus.parse(this[Jobs.status]),
        attempts = this[Jobs.attempts],
        maxAttempts = this[Jobs.maxAttempts],
        runAt = this[Jobs.runAt],
        lastError = this[Jobs.lastError],
    )

    private companion object {
        const val ERROR_MAX = 2_000
    }
}
