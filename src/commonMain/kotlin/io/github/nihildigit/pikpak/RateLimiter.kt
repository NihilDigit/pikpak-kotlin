package io.github.nihildigit.pikpak

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
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
    companion object Key : CoroutineContext.Key<ApiRequestPriority> {
        const val INTERACTIVE = 10
        const val DEFAULT = 0
        const val BACKGROUND = -10
    }
}

/** Applies [priority] to API calls in [block], including their retries. */
suspend fun <T> withApiPriority(priority: Int, block: suspend () -> T): T =
    withContext(ApiRequestPriority(priority)) { block() }

/** Endpoint defaults never override an explicit caller policy. */
internal suspend fun <T> withDefaultApiPriority(priority: Int, block: suspend () -> T): T =
    if (currentCoroutineContext()[ApiRequestPriority] != null) block()
    else withApiPriority(priority, block)

internal suspend fun <T> PikPakClient.withInteractiveApiPriority(block: suspend () -> T): T =
    withDefaultApiPriority(ApiRequestPriority.INTERACTIVE) {
        // Explicit background maintenance must not repeatedly suppress data transfers.
        if (currentCoroutineContext()[ApiRequestPriority]!!.priority >= ApiRequestPriority.DEFAULT)
            notifyInteractiveActivity()
        block()
    }

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
 * Background requests preserve [backgroundReserve] tokens for subsequent ordinary
 * and interactive requests. Foreground traffic can spend that reserve; it is not
 * an unconditional latency guarantee. Buckets smaller than eight reserve nothing.
 */
class RateLimiter(
    private val capacity: Int = 5,
    private val refillPerSecond: Double = 5.0,
    private val backgroundReserve: Int = if (capacity >= 8) minOf(8, capacity / 3) else 0,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerSecond > 0 && refillPerSecond.isFinite()) { "refillPerSecond must be finite and positive" }
        require(backgroundReserve in 0 until capacity) { "backgroundReserve must leave room for a request" }
    }

    private class Waiter(var priority: Int, var order: Long = 0)
    private var nextOrder = 0L
    private val mutex = Mutex()
    // Net balance after deducting every queued reservation. The spendable
    // balance is tokens + waiters.size, never more than capacity.
    private var tokens = capacity.toDouble()
    private var lastRefill = timeSource.markNow()
    private val waiters = mutableListOf<Waiter>()
    private var changed = CompletableDeferred<Unit>()

    /** Retains the original no-argument entry point. */
    suspend fun acquire() = acquire(ApiRequestPriority.DEFAULT)

    suspend fun acquire(priority: Int) = acquire(priority, null)

    internal suspend fun acquire(priority: Int, demand: TransferDemand?): Unit = coroutineScope {
        fun effectivePriority() = demand?.let {
            if (it.priority >= PikPakStreamReader.STREAMING_PRIORITY) ApiRequestPriority.INTERACTIVE else ApiRequestPriority.BACKGROUND
        } ?: priority
        if (demand?.isClosed == true) throw CancellationException("demand closed")
        val waiter = Waiter(effectivePriority())
        fun required() = 1.0 + if (waiter.priority <= ApiRequestPriority.BACKGROUND) backgroundReserve else 0
        val waitingJob = currentCoroutineContext().job
        val updates = demand?.let { owner -> launch {
            owner.changes.collect {
                if (owner.isClosed) waitingJob.cancel(CancellationException("demand closed"))
                else mutex.withLock {
                    waiter.priority = effectivePriority()
                    waiters.sortWith(compareByDescending<Waiter> { it.priority }.thenBy { it.order })
                    signalChange()
                }
            }
        } }
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                refill()
                if (tokens >= required() && waiters.isEmpty()) {
                    tokens -= 1.0
                    return@coroutineScope
                }
                tokens -= 1.0
                waiter.order = nextOrder++
                val index = waiters.indexOfFirst { it.priority < waiter.priority }
                if (index < 0) waiters.add(waiter) else waiters.add(index, waiter)
                signalChange()
            }
            while (true) {
                val (signal, waitMillis) = mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    refill()
                    val available = tokens + waiters.size
                    if (waiters.first() === waiter && available >= required()) {
                        waiters.removeAt(0) // reservation was already deducted
                        signalChange()
                        return@coroutineScope
                    }
                    // Only the head keeps a timer. A higher-priority arrival or
                    // cancellation wakes it to hand the timer to the new head.
                    changed to if (waiters.first() === waiter) {
                        ceil((required() - available) * 1000.0 / refillPerSecond).toLong().coerceAtLeast(1)
                    } else null
                }
                if (waitMillis == null) signal.await()
                else withTimeoutOrNull(waitMillis) { signal.await() }
            }
        } finally {
            updates?.cancel()
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
        val elapsed = lastRefill.elapsedNow().inWholeNanoseconds / 1_000_000_000.0
        tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(capacity.toDouble() - waiters.size)
        lastRefill = timeSource.markNow()
    }

    companion object {
        /** Interactive budget with room for a short burst. */
        fun default(): RateLimiter = RateLimiter(capacity = 24, refillPerSecond = 20.0)

        /** Disable rate limiting (use only when you know the workload won't trip captcha). */
        fun unlimited(): RateLimiter = RateLimiter(capacity = Int.MAX_VALUE, refillPerSecond = 1e9)
    }
}
