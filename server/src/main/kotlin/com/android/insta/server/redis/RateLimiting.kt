package com.android.insta.server.redis

import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

sealed interface RateDecision {
    data object Allowed : RateDecision
    data class Limited(val retryAfter: Duration) : RateDecision
}

/**
 * Fixed-window counter per key (`INCR` + expiry in one script). Fails open: if Redis can't answer, the request is
 * allowed, since an outage shouldn't take the API down with it.
 */
class FixedWindowRateLimiter(private val redis: Redis, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(FixedWindowRateLimiter::class.java)

    suspend fun acquire(name: String, key: String, limit: Int, window: Duration): RateDecision {
        val now = clock.millis()
        val windowMs = window.inWholeMilliseconds
        val windowIndex = now / windowMs
        val count = try {
            redis.commands().eval<Long>(INCR_SCRIPT, ScriptOutputType.INTEGER, arrayOf(RedisKeys.rateLimit(name, key, windowIndex)), windowMs.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Rate limit {} not enforced, Redis unavailable: {}", name, e.message)
            return RateDecision.Allowed
        } ?: return RateDecision.Allowed
        return if (count <= limit) RateDecision.Allowed else RateDecision.Limited(((windowIndex + 1) * windowMs - now).milliseconds)
    }

    private companion object {
        val INCR_SCRIPT = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
        """.trimIndent()
    }
}

sealed interface OtpSendDecision {
    data object Allowed : OtpSendDecision
    data class Cooldown(val retryAfter: Duration) : OtpSendDecision
    data class HourlyCap(val retryAfter: Duration) : OtpSendDecision
}

/**
 * Per-number SMS budget as a sliding log: a sorted set of send times (from the injected [Clock], so tests can move
 * time). One script checks the cooldown since the last send and the cap within [window], then records the send.
 * Callers must fail **closed** on errors: without Redis there's no way to stop code flooding.
 */
class OtpSendThrottle(private val redis: Redis, private val clock: Clock) {

    suspend fun acquire(phone: String, cooldown: Duration, maxPerWindow: Int, window: Duration): OtpSendDecision {
        val now = clock.millis()
        val result = redis.commands().eval<Long>(
            SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(RedisKeys.otpSends(phone)),
            now.toString(),
            cooldown.inWholeMilliseconds.toString(),
            window.inWholeMilliseconds.toString(),
            maxPerWindow.toString(),
            "$now-${System.nanoTime()}",
        ) ?: throw RedisUnavailableException()
        return when {
            result > 0 -> OtpSendDecision.Cooldown(result.milliseconds)
            result < 0 -> OtpSendDecision.HourlyCap((-result).milliseconds)
            else -> OtpSendDecision.Allowed
        }
    }

    private companion object {
        // ARGV: now, cooldown, window, max, member. Returns 0 = recorded, >0 = cooldown wait (ms), <0 = cap wait (ms).
        val SCRIPT = """
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            local last = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            if last[2] then
              local wait = tonumber(last[2]) + tonumber(ARGV[2]) - now
              if wait > 0 then return wait end
            end
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
              local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              return -math.max(1, tonumber(first[2]) + window - now)
            end
            redis.call('ZADD', KEYS[1], now, ARGV[5])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
        """.trimIndent()
    }
}
