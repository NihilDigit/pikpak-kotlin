package io.github.nihildigit.pikpak

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Coroutine-friendly token-bucket rate limiter. Calls to [acquire] suspend
 * until a token is available. Default permits a steady ~5 req/s with a small
 * burst — conservative enough to avoid the PikPak 9-code captcha wall under
 * normal interactive use, but easy to tune for batch workloads.
 *
 * Construct one limiter per logical "fleet" of requests you want to share a
 * budget across (typically one per [PikPakClient]). To share a global budget
 * across multiple clients, pass the same instance to all of them.
 */
class RateLimiter(
    private val capacity: Int = 5,
    private val refillPerSecond: Double = 5.0,
) {
    private val mutex = Mutex()
    private var tokens: Double = capacity.toDouble()
    private var lastRefill = TimeSource.Monotonic.markNow()

    suspend fun acquire() {
        while (true) {
            val waitFor: Duration = mutex.withLock {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    Duration.ZERO
                } else {
                    val needed = 1.0 - tokens
                    ((needed / refillPerSecond) * 1000).toLong().coerceAtLeast(1).milliseconds
                }
            }
            if (waitFor == Duration.ZERO) return
            delay(waitFor)
        }
    }

    private fun refill() {
        val now = TimeSource.Monotonic.markNow()
        val elapsed = (now - lastRefill).inWholeMilliseconds / 1000.0
        if (elapsed > 0) {
            tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(capacity.toDouble())
            lastRefill = now
        }
    }

    companion object {
        /** Permissive default suitable for interactive use. */
        val Default get() = RateLimiter(capacity = 5, refillPerSecond = 5.0)
        /** Disable rate limiting (use only when you know the workload won't trip captcha). */
        val Unlimited get() = RateLimiter(capacity = Int.MAX_VALUE, refillPerSecond = 1e9)
    }
}
