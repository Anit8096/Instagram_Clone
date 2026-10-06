package com.android.insta.server.jobs

import com.android.insta.server.config.JobsConfig
import com.android.insta.server.redis.Redis
import com.android.insta.server.redis.RedisKeys
import io.lettuce.core.Consumer
import io.lettuce.core.RedisBusyException
import io.lettuce.core.StreamMessage
import io.lettuce.core.XAddArgs
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

fun interface JobHandler {
    /** Throw to fail the attempt. Handlers must be idempotent: a job can run again after a crash or a timeout. */
    suspend fun handle(job: JobRecord)
}

/**
 * A job type the runner works on. Register one as a Koin definition (any qualifier); the runner collects them all.
 * With [every], the runner keeps exactly one future run scheduled (recurring job, deduplicated by its type).
 */
data class JobRegistration(
    val type: String,
    val handler: JobHandler,
    val concurrency: Int = 1,
    val timeout: Duration = 1.minutes,
    val every: Duration? = null,
)

/**
 * Runs background jobs. Postgres (`jobs`) decides *what* runs and *when*; Redis Streams only carries job ids to the
 * workers (one consumer group per type).
 *
 * - **Enqueue:** insert the row in the business transaction ([JobRepository.enqueueIn]) and call [dispatch] after
 *   the commit, or use [enqueue]. A rolled-back transaction leaves nothing behind.
 * - **Workers:** XREADGROUP → claim the row (queued → running) → handler → done, or retry with exponential backoff,
 *   or after the last attempt dead + a copy on the dead-letter stream. Ids whose row can't be claimed (duplicate
 *   delivery, already finished) are just acknowledged.
 * - **Reconciler** (every [JobsConfig.reconcileInterval]): requeues running jobs whose worker went silent, publishes
 *   due queued rows (delayed jobs, retries, anything published while Redis was down), acknowledges stream entries
 *   left pending by dead consumers, and keeps recurring jobs scheduled.
 */
class JobRunner(
    private val db: Database,
    private val jobs: JobRepository,
    private val redis: Redis,
    private val config: JobsConfig,
    private val clock: Clock,
    registrations: List<JobRegistration>,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(JobRunner::class.java)
    private val registrations = registrations.associateBy { it.type }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val instance = Uuid.random().toString().take(8)

    fun start() {
        if (!config.workersEnabled) return
        for (registration in registrations.values) {
            repeat(registration.concurrency) { i ->
                scope.launch { workerLoop(registration, "$instance-${registration.type}-$i") }
            }
        }
        scope.launch { reconcileLoop() }
        log.info("Job runner started: {}", registrations.keys)
    }

    suspend fun enqueue(job: NewJob): EnqueuedJob? {
        val enqueued = suspendTransaction(db) { with(jobs) { enqueueIn(job, now()) } }
        enqueued?.let { dispatch(listOf(it)) }
        return enqueued
    }

    /** Publishes jobs that are already due. Failures are fine: the reconciler publishes them later. */
    suspend fun dispatch(enqueued: List<EnqueuedJob>) {
        val now = now()
        val due = enqueued.filter { !it.runAt.isAfter(now) }
        if (due.isEmpty()) return
        try {
            val commands = redis.commands()
            due.forEach { publish(commands, it) }
            jobs.markDispatched(due.map { it.id }, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Couldn't publish {} job(s), the reconciler will retry: {}", due.size, e.message)
        }
    }

    /** One reconciler pass; also called directly by tests. */
    suspend fun reconcile() {
        val now = now()
        val stale = jobs.requeueStale(now.minus(config.staleAfter.toJavaDuration()), now)
        if (stale > 0) log.warn("Requeued {} stale job(s)", stale)
        scheduleRecurring()
        val due = jobs.dueForDispatch(now, now.minus(config.redispatchAfter.toJavaDuration()), DISPATCH_BATCH)
        if (due.isNotEmpty()) {
            val commands = redis.commands()
            due.forEach { publish(commands, it) }
            jobs.markDispatched(due.map { it.id }, now)
        }
        ackAbandoned()
    }

    private suspend fun reconcileLoop() {
        while (scope.isActive) {
            try {
                reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scope.isActive) return // shutting down: Redis or the pool closed under an in-flight pass
                log.warn("Job reconcile failed: {}", e.message)
            }
            delay(config.reconcileInterval)
        }
    }

    private suspend fun scheduleRecurring() {
        val recurring = registrations.values.filter { it.every != null }
        if (recurring.isEmpty()) return
        suspendTransaction(db) {
            recurring.forEach { with(jobs) { enqueueIn(NewJob(it.type, dedupeKey = recurringKey(it.type), maxAttempts = 1), now()) } }
        }
    }

    private suspend fun workerLoop(registration: JobRegistration, consumer: String) {
        val stream = RedisKeys.jobStream(registration.type)
        while (scope.isActive) {
            try {
                redis.blockingConnection(config.blockTimeout).use { connection ->
                    val commands = connection.coroutines()
                    ensureGroup(commands, stream)
                    while (scope.isActive) {
                        val messages = commands.xreadgroup(
                            Consumer.from(GROUP, consumer),
                            XReadArgs.Builder.block(config.blockTimeout.toJavaDuration()).count(1),
                            XReadArgs.StreamOffset.lastConsumed(stream),
                        ).toList()
                        messages.forEach { process(registration, commands, stream, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scope.isActive) return
                log.warn("Job worker {} lost Redis, retrying: {}", consumer, e.message)
                delay(WORKER_RETRY)
            }
        }
    }

    private suspend fun process(
        registration: JobRegistration,
        commands: RedisCoroutinesCommands<String, String>,
        stream: String,
        message: StreamMessage<String, String>,
    ) {
        val id = message.body["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val job = id?.let { jobs.claim(it, now()) }
        if (job != null) run(registration, job)
        commands.xack(stream, GROUP, message.id)
    }

    private suspend fun run(registration: JobRegistration, job: JobRecord) {
        val error = try {
            withTimeout(registration.timeout) { registration.handler.handle(job) }
            null
        } catch (e: CancellationException) {
            if (!scope.isActive) throw e // shutting down: the reconciler requeues the job as stale later
            "Timed out after ${registration.timeout}"
        } catch (e: Exception) {
            e.message ?: e::class.simpleName ?: "error"
        }
        val now = now()
        when {
            error == null -> {
                jobs.complete(job.id, now)
                registration.every?.let { scheduleNext(registration.type, now.plus(it.toJavaDuration())) }
            }
            job.attempts >= job.maxAttempts -> {
                log.error("Job {} {} failed for good after {} attempt(s): {}", job.type, job.id, job.attempts, error)
                jobs.bury(job.id, error, now)
                runCatching {
                    redis.commands().xadd(
                        RedisKeys.DEAD_LETTER_STREAM,
                        XAddArgs.Builder.maxlen(STREAM_MAX_LENGTH).approximateTrimming(),
                        mapOf("id" to job.id.toString(), "type" to job.type, "error" to error.take(500)),
                    )
                }
                registration.every?.let { scheduleNext(registration.type, now.plus(it.toJavaDuration())) }
            }
            else -> {
                val backoff = config.retryBase * (1 shl (job.attempts - 1).coerceAtMost(10))
                log.warn("Job {} {} failed (attempt {}), retrying in {}: {}", job.type, job.id, job.attempts, backoff, error)
                jobs.retryLater(job.id, error, now.plus(backoff.toJavaDuration()), now)
            }
        }
    }

    private suspend fun scheduleNext(type: String, runAt: OffsetDateTime) {
        suspendTransaction(db) { with(jobs) { enqueueIn(NewJob(type, runAt = runAt, dedupeKey = recurringKey(type), maxAttempts = 1), now()) } }
    }

    /** Entries delivered to consumers that died. Their rows are requeued by [JobRepository.requeueStale]. */
    private suspend fun ackAbandoned() {
        val commands = redis.commands()
        for (type in registrations.keys) {
            val stream = RedisKeys.jobStream(type)
            ensureGroup(commands, stream)
            val claimed = commands.xautoclaim(
                stream,
                XAutoClaimArgs.Builder.xautoclaim(Consumer.from(GROUP, "$instance-reaper"), config.staleAfter.toJavaDuration(), "0-0").count(100),
            ) ?: continue
            val ids = claimed.messages.map { it.id }
            if (ids.isNotEmpty()) commands.xack(stream, GROUP, *ids.toTypedArray())
        }
    }

    private suspend fun publish(commands: RedisCoroutinesCommands<String, String>, job: EnqueuedJob) {
        commands.xadd(
            RedisKeys.jobStream(job.type),
            XAddArgs.Builder.maxlen(STREAM_MAX_LENGTH).approximateTrimming(),
            mapOf("id" to job.id.toString()),
        )
    }

    private suspend fun ensureGroup(commands: RedisCoroutinesCommands<String, String>, stream: String) {
        try {
            commands.xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), GROUP, XGroupCreateArgs.Builder.mkstream())
        } catch (_: RedisBusyException) {
            // BUSYGROUP: already there.
        }
    }

    private fun now() = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))

    override fun close() = scope.cancel()

    private companion object {
        const val GROUP = "workers"
        const val DISPATCH_BATCH = 500
        const val STREAM_MAX_LENGTH = 10_000L
        val WORKER_RETRY = 2.seconds

        fun recurringKey(type: String) = "recurring:$type"
    }
}
