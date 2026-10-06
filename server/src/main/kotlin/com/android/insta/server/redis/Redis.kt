package com.android.insta.server.redis

import com.android.insta.server.config.RedisConfig
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class RedisUnavailableException(cause: Throwable? = null) : RuntimeException("Redis is unavailable", cause)

/**
 * One shared Lettuce connection for regular commands, plus [blockingConnection] for stream readers (a blocking
 * XREADGROUP would stall every other command on a shared connection).
 *
 * The server starts and keeps serving when Redis is down: the shared connection is opened lazily (a failed attempt
 * is retried after a short pause), and once open Lettuce reconnects by itself. While disconnected, commands are
 * rejected immediately instead of queueing, so callers see a [io.lettuce.core.RedisException] within the timeout.
 */
class Redis(private val config: RedisConfig) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Redis::class.java)

    private val client: RedisClient = RedisClient.create().apply {
        options = ClientOptions.builder()
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(SocketOptions.builder().connectTimeout(config.timeout.toJavaDuration()).build())
            // Timeout per connection, from its RedisURI: a fixed value here would also cut blocking stream reads short.
            .timeoutOptions(TimeoutOptions.enabled())
            .build()
    }

    private val mutex = Mutex()

    @Volatile
    private var shared: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var nextAttemptAt = 0L

    /** Commands on the shared connection. Throws [RedisUnavailableException] if it can't be opened right now. */
    suspend fun commands(): RedisCoroutinesCommands<String, String> = sharedConnection().coroutines()

    /**
     * A dedicated connection whose command timeout covers [blockFor] (for XREADGROUP BLOCK). The caller closes it.
     */
    suspend fun blockingConnection(blockFor: Duration): StatefulRedisConnection<String, String> =
        withContext(Dispatchers.IO) { client.connect(uri(config.timeout + blockFor + 5.seconds)) }

    suspend fun isUp(): Boolean = withTimeoutOrNull(config.timeout) {
        runCatching { commands().ping() == "PONG" }.getOrDefault(false)
    } ?: false

    private suspend fun sharedConnection(): StatefulRedisConnection<String, String> {
        shared?.let { return it }
        return mutex.withLock {
            shared ?: run {
                if (System.currentTimeMillis() < nextAttemptAt) throw RedisUnavailableException()
                try {
                    withContext(Dispatchers.IO) { client.connect(uri(config.timeout)) }.also { shared = it }
                } catch (e: Exception) {
                    nextAttemptAt = System.currentTimeMillis() + RETRY_CONNECT_MS
                    log.warn("Can't connect to Redis: {}", e.message)
                    throw RedisUnavailableException(e)
                }
            }
        }
    }

    private fun uri(timeout: Duration): RedisURI = RedisURI.create(config.url).apply { this.timeout = timeout.toJavaDuration() }

    override fun close() {
        shared?.close()
        client.shutdown()
    }

    private companion object {
        const val RETRY_CONNECT_MS = 2_000L
    }
}

/** Key names in one place: `insta:` prefix, then the area. Bump `v1` in cache keys when a cached shape changes. */
object RedisKeys {
    fun counts(userId: Any) = "insta:v1:counts:$userId"
    fun rateLimit(name: String, key: String, window: Long) = "insta:rl:$name:$key:$window"
    fun otpSends(phone: String) = "insta:rl:otp:$phone"
    fun jobStream(type: String) = "insta:jobs:$type"
    const val DEAD_LETTER_STREAM = "insta:jobs:dlq"
}
