package io.github.nihildigit.pikpak

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Coroutine-friendly token-bucket rate limiter. Calls to [acquire] suspend
 * until a token is available. Default permits a steady ~5 req/s with a small
 * burst — conservative enough to avoid the PikPak 9-code captcha wall under
 * normal interactive use, but easy to tune for batch workloads.
 *
 * Concurrent waiters are handed reservations under the mutex so they wake up
 * staggered instead of all-at-once, avoiding a thundering herd when many
 * coroutines share the same limiter.
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
    // Earliest moment at which the next "waiter" reservation may fire.
    // Advances monotonically forward as waiters queue up.
    private var nextReleaseAt = TimeSource.Monotonic.markNow()

    suspend fun acquire() {
        val interval = (1000.0 / refillPerSecond).toLong().coerceAtLeast(1).milliseconds
        val waitFor = mutex.withLock {
            val now = TimeSource.Monotonic.markNow()
            refill(now)
            if (tokens >= 1.0) {
                tokens -= 1.0
                // If nextReleaseAt is already in the past, snap it forward to
                // "now" so the next waiter's reservation is measured from here.
                if (nextReleaseAt < now) nextReleaseAt = now
                Duration.ZERO
            } else {
                // Reserve a slot after whichever is later: "now" or the last
                // reserved slot. Each reservation advances nextReleaseAt by
                // one refill interval, serialising concurrent waiters.
                //
                // The reservation spends its token now, going negative if it
                // has to. Without this, refill() kept crediting a bucket that
                // nobody had debited, so every reserved slot was paid for twice
                // and the observed rate ran at up to double the configured one
                // — with a caller arriving later able to take a free token
                // ahead of an already-queued waiter.
                tokens -= 1.0
                val base = if (nextReleaseAt < now) now else nextReleaseAt
                val myReleaseAt = base + interval
                nextReleaseAt = myReleaseAt
                myReleaseAt - now
            }
        }
        if (waitFor <= Duration.ZERO) return
        try {
            delay(waitFor)
        } catch (e: CancellationException) {
            // A waiter that gives up returns its reservation. Kept, fifty queued calls from a
            // cancelled search left the bucket fifty tokens short and the next request waiting
            // ten seconds behind nobody. Pulling the next slot back may let a later caller fire
            // in the same interval as a waiter still queued; the tokens still bound the rate.
            withContext(NonCancellable) {
                mutex.withLock {
                    tokens += 1.0
                    val now = TimeSource.Monotonic.markNow()
                    nextReleaseAt = (nextReleaseAt - interval).let { if (it < now) now else it }
                }
            }
            throw e
        }
    }

    private fun refill(now: kotlin.time.TimeSource.Monotonic.ValueTimeMark) {
        val elapsed = (now - lastRefill).inWholeMilliseconds / 1000.0
        if (elapsed > 0) {
            tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(capacity.toDouble())
            lastRefill = now
        }
    }

    companion object {
        /** Permissive default suitable for interactive use. */
        fun default(): RateLimiter = RateLimiter(capacity = 5, refillPerSecond = 5.0)

        /** Disable rate limiting (use only when you know the workload won't trip captcha). */
        fun unlimited(): RateLimiter = RateLimiter(capacity = Int.MAX_VALUE, refillPerSecond = 1e9)
    }
}
