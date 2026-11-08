package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Measurements for one read, installed by its caller and updated by RangeReader.
 *
 * Use a fresh instance per read. The pump writes service measurements after gate acquisition;
 * engine callbacks publish connection evidence through an atomic flow. Callers inspect the
 * measurements after the read has finished, including cancellation cleanup.
 */
internal class ReadTelemetry(
    timeSource: TimeSource = TimeSource.Monotonic,
    private val onBytes: ((Long) -> Unit)? = null,
) : AbstractCoroutineContextElement(ReadTelemetry) {
    private val connection = MutableStateFlow<Boolean?>(null)

    /** Null means the engine has supplied no evidence, never an inferred cold connection. */
    val connectionReused: Boolean? get() = connection.value

    fun recordConnectionAcquired(reused: Boolean) {
        connection.value = reused
    }

    /** Reset before each HTTP attempt so a retry cannot inherit the previous connection. */
    fun resetConnection() {
        connection.value = null
    }

    private val origin = timeSource.markNow()
    val elapsed: Duration get() = origin.elapsedNow()

    private data class WaitState(
        val mask: Int = 0,
        val at: Duration = Duration.ZERO,
        val limiter: Duration = Duration.ZERO,
        val overlap: Duration = Duration.ZERO,
    )
    private val waits = MutableStateFlow(WaitState())

    private fun transitionWait(bit: Int, active: Boolean) {
        while (true) {
            val before = waits.value
            val now = elapsed
            val interval = (now - before.at).coerceAtLeast(Duration.ZERO)
            val next = before.copy(
                mask = if (active) before.mask or bit else before.mask and bit.inv(), at = now,
                limiter = before.limiter + if (before.mask and 2 != 0) interval else Duration.ZERO,
                overlap = before.overlap + if (before.mask == 3) interval else Duration.ZERO,
            )
            if (waits.compareAndSet(before, next)) return
        }
    }

    fun beginSinkWait() = transitionWait(1, true)
    fun endSinkWait() = transitionWait(1, false)
    fun beginLimiterWait() = transitionWait(2, true)
    fun endLimiterWait() = transitionWait(2, false)

    /** Exact interval difference, not a subtraction of unrelated cumulative waits. */
    val uncoveredLimiterWait: Duration
        get() = waits.value.let { (it.limiter - it.overlap).coerceAtLeast(Duration.ZERO) }

    /** Consumer blocking is not evidence of slow network service. */
    var sinkWait: Duration = Duration.ZERO
    /** A stopped request only establishes a lower bound on completion time. */
    var rightCensored: Boolean = false

    fun recordBytes(bytes: Int) {
        if (bytes <= 0) return
        deliveredBytes += bytes
        onBytes?.invoke(bytes.toLong())
    }
    companion object Key : CoroutineContext.Key<ReadTelemetry>

    /** Time acquiring the file and account slots, including a cancelled wait. */
    var gateWait: Duration = Duration.ZERO
    /** Attempt lifetime excluding consumer backpressure; retries remain separately labelled. */
    var networkDuration: Duration = Duration.ZERO
    /** Pump start to the first positive body read, before writing to the consumer. */
    var timeToFirstByte: Duration? = null
    /** Body bytes read from the CDN, before writing to the consumer. */
    var deliveredBytes: Long = 0
    /** Pump-level HTTP attempts; retries within HttpEngine are counted separately. */
    var attempts: Int = 0
    /** Transport and truncation retries, plus all retries performed inside HttpEngine. */
    var retries: Int = 0
    /** Outer 503 responses plus server-error and rate-limit retries inside HttpEngine. */
    var throttled: Int = 0
    var refreshed: Boolean = false
    var rerouted: Boolean = false

    val isClean: Boolean
        get() = attempts == 1 && retries == 0 && throttled == 0 &&
            !refreshed && !rightCensored && deliveredBytes > 0
}
