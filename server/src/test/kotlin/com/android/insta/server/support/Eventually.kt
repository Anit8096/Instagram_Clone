package com.android.insta.server.support

import kotlinx.coroutines.delay
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Polls [condition] until it holds; for background work (jobs, reconciler) that completes asynchronously. */
suspend fun eventually(timeout: Duration = 10.seconds, message: String = "condition", condition: suspend () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!condition()) {
        if (deadline.hasPassedNow()) fail("Timed out after $timeout waiting for $message")
        delay(50.milliseconds)
    }
}
