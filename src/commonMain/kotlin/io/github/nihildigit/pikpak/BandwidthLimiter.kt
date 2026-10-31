package io.github.nihildigit.pikpak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * A ceiling on the combined rate of every download given this limiter, changeable while they run.
 *
 * [PikPakClient.downloadLimiter] applies to durable cache downloads on that client. The same
 * instance can also be passed to [RangeSource.downloadTo] or other clients so their downloads
 * and connections share one budget. Playback, ordinary prefetch and size probes do not acquire
 * download tokens. A cache cancels a waiting download reservation when playback needs it.
 *
 * Bytes are paid for at block boundaries. The first block is admitted before its request;
 * later blocks of a long range can apply backpressure while that connection remains open.
 * These consumer waits are excluded from network service-rate observations. A block arrives
 * at the line's speed and the next one waits, so the ceiling holds over stretches longer
 * than a few blocks rather than for each individual read.
 *
 * A token bucket that may go into debt. A request is granted once the balance is not negative
 * and then takes its whole size, so a block larger than a second's worth is still granted, and
 * pays for itself by delaying whoever comes next. Waiters are served in the order they asked,
 * which is the order [RangeSource.downloadTo] issues its blocks in, so the block the file is
 * waiting to append is never overtaken by one behind it.
 *
 * [bytesPerSecond] may be changed at any time and applies at once, to the waiter already
 * sleeping too: it recomputes its wait against the new rate. Null lifts the limit and releases
 * every waiter.
 */
class BandwidthLimiter(
    bytesPerSecond: Long? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val rate = MutableStateFlow(bytesPerSecond.validated())
    private val mutex = Mutex()
    private val origin = timeSource.markNow()

    /** Guarded by [mutex]. Bytes available now; negative is debt. */
    private var balance = 0.0
    private var refilledAt = Duration.ZERO

    /** The current ceiling in bytes per second, or null for none. */
    var bytesPerSecond: Long?
        get() = rate.value
        set(value) {
            rate.value = value.validated()
        }

    /** Returns once [bytes] may be fetched under the current ceiling. */
    suspend fun acquire(bytes: Long) {
        if (bytes <= 0 || rate.value == null) return
        mutex.withLock {
            while (true) {
                val current = rate.value ?: return
                refill(current)
                if (balance >= 0) {
                    balance -= bytes
                    return
                }
                val wait = (-balance / current * 1e9).nanoseconds
                // A changed rate ends the wait early, so it is re-planned against the new one
                withTimeoutOrNull(wait) { rate.first { it != current } }
            }
        }
    }

    private fun refill(current: Long) {
        val now = origin.elapsedNow()
        val elapsed = (now - refilledAt).coerceAtLeast(Duration.ZERO)
        refilledAt = now
        // Idle time earns at most BURST of credit, or a limiter left unused for an hour would
        // let the next hour's worth through at full speed
        balance = minOf(balance + elapsed.toDouble(DurationUnit.SECONDS) * current, current * BURST_SECONDS)
    }

    private companion object {
        const val BURST_SECONDS = 1.0

        fun Long?.validated(): Long? {
            require(this == null || this > 0) { "bytesPerSecond must be > 0 or null, got $this" }
            return this
        }
    }
}
