package com.android.insta.server.jobs

import com.android.insta.server.db.OtpChallenges
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

/** Hourly housekeeping: expired one-time codes (kept a day for the audit trail) and finished jobs (kept a week). */
fun maintenanceJob(db: Database, jobs: JobRepository, clock: Clock) = JobRegistration(
    type = "maintenance",
    every = 1.hours,
    handler = { _ ->
        val now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))
        val codes = suspendTransaction(db) { OtpChallenges.deleteWhere { expiresAt less now.minusDays(1) } }
        val finished = jobs.deleteFinishedBefore(now.minusDays(7))
        LoggerFactory.getLogger("jobs.maintenance").info("Maintenance: removed {} old code(s), {} finished job(s)", codes, finished)
    },
)
