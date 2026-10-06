package com.android.insta.server.jobs

import com.android.insta.server.db.Jobs
import com.android.insta.server.db.OtpChallenges
import com.android.insta.server.redis.Redis
import com.android.insta.server.redis.RedisKeys
import com.android.insta.server.support.IntegrationTest
import com.android.insta.server.support.TestRedis
import com.android.insta.server.support.eventually
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.get
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

class JobRunnerTest : IntegrationTest() {

    private fun jobs(vararg registrations: JobRegistration) = listOf(
        module { registrations.forEach { r -> single(named("test-${r.type}")) { r } } },
    )

    private fun now() = OffsetDateTime.now(ZoneOffset.UTC)

    private suspend fun job(id: Uuid) = assertNotNull(koin<JobRepository>().find(id))

    private suspend fun awaitStatus(id: Uuid, status: JobStatus) =
        eventually(message = "job $id to be $status") { koin<JobRepository>().find(id)?.status == status }

    @Test
    fun `an enqueued job runs once and is marked done`() {
        val calls = AtomicInteger()
        withApp(extraModules = jobs(JobRegistration("echo", { calls.incrementAndGet() }))) {
            startApplication()
            val enqueued = assertNotNull(koin<JobRunner>().enqueue(NewJob("echo", payload = """{"n":1}""")))
            awaitStatus(enqueued.id, JobStatus.DONE)
            delay(500.milliseconds) // a few reconciler passes must not run it again
            assertEquals(1, calls.get())
            assertEquals(1, job(enqueued.id).attempts)
        }
    }

    @Test
    fun `a worker's blocking read outlasts the regular command timeout`() = withApp {
        // Regression: a fixed client-wide timeout cut XREADGROUP BLOCK short, and an entry delivered during the
        // abandoned read sat unprocessed until the reconciler re-published it.
        startApplication()
        koin<Redis>().blockingConnection(fastJobs.blockTimeout).use { connection ->
            val read = connection.coroutines().xread(
                XReadArgs.Builder.block(1.seconds.toJavaDuration()), // > the 300 ms test timeout
                XReadArgs.StreamOffset.latest("insta:test:empty"),
            ).toList()
            assertTrue(read.isEmpty())
        }
    }

    @Test
    fun `jobs follow their transaction - rolled back never runs, committed runs without an explicit dispatch`() {
        val calls = AtomicInteger()
        withApp(extraModules = jobs(JobRegistration("outbox", { calls.incrementAndGet() }))) {
            startApplication()
            val db = koin<Database>()
            val repo = koin<JobRepository>()
            assertFailsWith<IllegalStateException> {
                suspendTransaction(db) {
                    with(repo) { enqueueIn(NewJob("outbox"), now()) }
                    error("business write failed")
                }
            }
            // Committed but never published: the reconciler finds and publishes it.
            val committed = assertNotNull(suspendTransaction(db) { with(repo) { enqueueIn(NewJob("outbox"), now()) } })
            awaitStatus(committed.id, JobStatus.DONE)
            assertEquals(1, calls.get())
            assertEquals(1, suspendTransaction(db) { Jobs.selectAll().where { Jobs.type eq "outbox" }.count() })
        }
    }

    @Test
    fun `a failing job is retried with backoff and dead-lettered after its last attempt`() {
        val calls = AtomicInteger()
        withApp(extraModules = jobs(JobRegistration("flaky", { calls.incrementAndGet(); error("boom") }))) {
            startApplication()
            val enqueued = assertNotNull(koin<JobRunner>().enqueue(NewJob("flaky", maxAttempts = 3)))
            awaitStatus(enqueued.id, JobStatus.DEAD)
            assertEquals(3, calls.get())
            assertEquals("boom", job(enqueued.id).lastError)
            assertEquals(1L, koin<Redis>().commands().xlen(RedisKeys.DEAD_LETTER_STREAM))
        }
    }

    @Test
    fun `a job that fails once succeeds on the retry`() {
        val calls = AtomicInteger()
        withApp(extraModules = jobs(JobRegistration("transient", { if (calls.incrementAndGet() == 1) error("blip") }))) {
            startApplication()
            val enqueued = assertNotNull(koin<JobRunner>().enqueue(NewJob("transient")))
            awaitStatus(enqueued.id, JobStatus.DONE)
            assertEquals(2, job(enqueued.id).attempts)
        }
    }

    @Test
    fun `a job over its timeout counts as failed`() {
        withApp(extraModules = jobs(JobRegistration("slow", { delay(5.seconds) }, timeout = 100.milliseconds))) {
            startApplication()
            val enqueued = assertNotNull(koin<JobRunner>().enqueue(NewJob("slow", maxAttempts = 1)))
            awaitStatus(enqueued.id, JobStatus.DEAD)
            assertTrue(job(enqueued.id).lastError!!.startsWith("Timed out"))
        }
    }

    @Test
    fun `a delayed job waits for its run time`() {
        val ranAt = mutableListOf<OffsetDateTime>()
        withApp(extraModules = jobs(JobRegistration("later", { synchronized(ranAt) { ranAt += now() } }))) {
            startApplication()
            val runAt = now().plusSeconds(2)
            val enqueued = assertNotNull(koin<JobRunner>().enqueue(NewJob("later", runAt = runAt)))
            delay(1.seconds)
            assertEquals(JobStatus.QUEUED, job(enqueued.id).status)
            awaitStatus(enqueued.id, JobStatus.DONE)
            assertTrue(!ranAt.single().isBefore(runAt))
        }
    }

    @Test
    fun `a running job whose worker died is requeued and finished`() {
        val calls = AtomicInteger()
        val config = testConfig().copy(jobs = fastJobs.copy(staleAfter = 1.seconds))
        withApp(config = config, extraModules = jobs(JobRegistration("orphan", { calls.incrementAndGet() }))) {
            startApplication()
            val id = Uuid.random()
            val longAgo = now().minusMinutes(10)
            suspendTransaction(koin<Database>()) {
                Jobs.insert {
                    it[Jobs.id] = id
                    it[type] = "orphan"
                    it[payload] = "{}"
                    it[status] = JobStatus.RUNNING.value
                    it[attempts] = 1
                    it[maxAttempts] = 3
                    it[runAt] = longAgo
                    it[createdAt] = longAgo
                    it[updatedAt] = longAgo
                }
            }
            awaitStatus(id, JobStatus.DONE)
            assertEquals(1, calls.get())
            assertEquals(2, job(id).attempts)
        }
    }

    @Test
    fun `jobs enqueued while Redis is down run once it is back, and health reports the outage`() {
        val calls = AtomicInteger()
        withApp(extraModules = jobs(JobRegistration("resilient", { calls.incrementAndGet() }))) { client ->
            startApplication()
            val enqueued = TestRedis.paused {
                val health = client.get("/health").body<Map<String, String>>()
                assertEquals(mapOf("status" to "degraded", "db" to "up", "redis" to "down"), health)
                assertNotNull(koin<JobRunner>().enqueue(NewJob("resilient"))).also {
                    delay(500.milliseconds)
                    assertEquals(0, calls.get())
                }
            }
            awaitStatus(enqueued.id, JobStatus.DONE)
            assertEquals(1, calls.get())
            assertEquals("up", client.get("/health").body<Map<String, String>>()["redis"])
        }
    }

    @Test
    fun `maintenance runs at startup and schedules its next run an hour later`() = withApp {
        startApplication()
        eventually(message = "a finished maintenance run and a future one") {
            suspendTransaction(koin<Database>()) {
                val rows = Jobs.selectAll().where { Jobs.type eq "maintenance" }.toList()
                rows.any { it[Jobs.status] == JobStatus.DONE.value } &&
                    rows.any { it[Jobs.status] == JobStatus.QUEUED.value && it[Jobs.runAt].isAfter(now().plusMinutes(50)) }
            }
        }
    }

    @Test
    fun `maintenance removes day-old codes and week-old finished jobs only`() = withApp {
        startApplication()
        val db = koin<Database>()
        val oldCode = Uuid.random()
        val freshCode = Uuid.random()
        val oldJob = Uuid.random()
        suspendTransaction(db) {
            listOf(oldCode to now().minusDays(2), freshCode to now().minusHours(2)).forEach { (codeId, at) ->
                OtpChallenges.insert {
                    it[id] = codeId
                    it[phone] = "+919876500000"
                    it[purpose] = "login"
                    it[codeHash] = "x".repeat(64)
                    it[attempts] = 0
                    it[expiresAt] = at.plusMinutes(5)
                    it[createdAt] = at
                }
            }
            Jobs.insert {
                it[id] = oldJob
                it[type] = "echo"
                it[payload] = "{}"
                it[status] = JobStatus.DONE.value
                it[attempts] = 1
                it[maxAttempts] = 3
                it[runAt] = now().minusDays(8)
                it[createdAt] = now().minusDays(8)
                it[updatedAt] = now().minusDays(8)
            }
        }

        val maintenance = app.get<JobRegistration>(named("maintenance"))
        maintenance.handler.handle(JobRecord(Uuid.random(), "maintenance", "{}", JobStatus.RUNNING, 1, 1, now(), null))

        suspendTransaction(db) {
            assertEquals(listOf(freshCode), OtpChallenges.selectAll().map { it[OtpChallenges.id] })
            assertEquals(0, Jobs.selectAll().where { Jobs.id eq oldJob }.count())
        }
    }
}
