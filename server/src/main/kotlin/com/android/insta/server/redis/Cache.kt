package com.android.insta.server.redis

import com.android.insta.server.common.AppJson
import io.lettuce.core.SetArgs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Read-through cache. Postgres stays the source of truth: entries expire after their TTL and writers invalidate the
 * keys they affect after committing. Fails open — any Redis error just means a database read.
 */
interface Cache {
    suspend fun <T> getOrLoad(key: String, ttl: Duration, serializer: KSerializer<T>, load: suspend () -> T): T

    /** Best effort: a failed delete leaves the entry until its TTL ends. */
    suspend fun invalidate(vararg keys: String)
}

class RedisCache(private val redis: Redis) : Cache {
    private val log = LoggerFactory.getLogger(RedisCache::class.java)

    override suspend fun <T> getOrLoad(key: String, ttl: Duration, serializer: KSerializer<T>, load: suspend () -> T): T {
        val cached = attempt("get $key") { redis.commands().get(key) }
        if (cached != null) {
            runCatching { return AppJson.decodeFromString(serializer, cached) }
                .onFailure { log.warn("Dropping unreadable cache entry {}", key) }
        }
        val value = load()
        attempt("set $key") { redis.commands().set(key, AppJson.encodeToString(serializer, value), SetArgs.Builder.px(ttl.inWholeMilliseconds)) }
        return value
    }

    override suspend fun invalidate(vararg keys: String) {
        if (keys.isEmpty()) return
        attempt("del ${keys.joinToString()}") { redis.commands().del(*keys) }
    }

    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Cache {} failed, using the database: {}", what, e.message)
        null
    }
}
