package io.github.nihildigit.pikpak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.time.TimeSource

/** API queue priority. Higher numbers run first; ordinary requests use zero. */
class ApiRequestPriority(val priority: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApiRequestPriority>
}

/** Applies [priority] to API calls in [block], including their retries. */
suspend fun <T> withApiPriority(priority: Int, block: suspend () -> T): T =
    withContext(ApiRequestPriority(priority)) { block() }

/**
 * Coroutine-friendly token bucket shared by a client's API requests.
 * Queued requests reserve tokens immediately, and higher priorities receive the
 * next available token first (FIFO within a priority). Cancelling a queued
 * request refunds its reservation without leaving an empty time slot behind.
 * Already granted requests cannot be preempted; sustained high-priority traffic
 * can delay lower-priority work indefinitely.
 *
 * The constructor retains its conservative 5/s budget; [default] uses 20/s
 * with a burst of 24. Share an instance to share a budget across clients.
 */
class RateLimiter(
    private val capacity: Int = 5,
    private val refillPerSecond: Double = 5.0,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerSecond > 0 && refillPerSecond.isFinite()) { "refillPerSecond must be finite and positive" }
    }

    private class Waiter(val priority: Int)
    private val mutex = Mutex()
    // Net balance after deducting every queued reservation. The spendable
    // balance is tokens + waiters.size, never more than capacity.
    private var tokens = capacity.toDouble()
    private var lastRefill = TimeSource.Monotonic.markNow()
    private val waiters = mutableListOf<Waiter>()
    private var changed = CompletableDeferred<Unit>()

    /** Retains the original no-argument entry point. */
    suspend fun acquire() = acquire(0)

    suspend fun acquire(priority: Int) {
        val waiter = Waiter(priority)
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                refill()
                if (tokens >= 1.0 && waiters.isEmpty()) {
                    tokens -= 1.0
                    return
                }
                tokens -= 1.0
                val index = waiters.indexOfFirst { it.priority < priority }
                if (index < 0) waiters.add(waiter) else waiters.add(index, waiter)
                signalChange()
            }
            while (true) {
                val (signal, waitMillis) = mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    refill()
                    val available = tokens + waiters.size
                    if (waiters.first() === waiter && available >= 1.0) {
                        waiters.removeAt(0) // reservation was already deducted
                        signalChange()
                        return
                    }
                    // Only the head keeps a timer. A higher-priority arrival or
                    // cancellation wakes it to hand the timer to the new head.
                    changed to if (waiters.first() === waiter) {
                        ceil((1.0 - available) * 1000.0 / refillPerSecond).toLong().coerceAtLeast(1)
                    } else null
                }
                if (waitMillis == null) signal.await()
                else withTimeoutOrNull(waitMillis) { signal.await() }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (waiters.remove(waiter)) {
                        tokens += 1.0
                        signalChange()
                    }
                }
            }
        }
    }

    private fun signalChange() {
        changed.complete(Unit)
        changed = CompletableDeferred()
    }

    private fun refill() {
        val now = TimeSource.Monotonic.markNow()
        val elapsed = (now - lastRefill).inWholeNanoseconds / 1_000_000_000.0
        tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(capacity.toDouble() - waiters.size)
        lastRefill = now
    }

    companion object {
        /** Interactive budget with room for a short burst. */
        fun default(): RateLimiter = RateLimiter(capacity = 24, refillPerSecond = 20.0)

        /** Disable rate limiting (use only when you know the workload won't trip captcha). */
        fun unlimited(): RateLimiter = RateLimiter(capacity = Int.MAX_VALUE, refillPerSecond = 1e9)
    }
}
