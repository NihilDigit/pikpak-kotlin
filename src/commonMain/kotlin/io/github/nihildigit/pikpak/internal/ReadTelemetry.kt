package io.github.nihildigit.pikpak.internal

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Measurements for one read, installed by its caller and updated by RangeReader.
 *
 * Use a fresh instance per read. The pump is its only writer after gate acquisition;
 * callers inspect it after the read has finished, including cancellation cleanup.
 */
internal class ReadTelemetry(
    timeSource: TimeSource = TimeSource.Monotonic,
    private val onBytes: ((Long) -> Unit)? = null,
) : AbstractCoroutineContextElement(ReadTelemetry) {
    private val origin = timeSource.markNow()
    val elapsed: Duration get() = origin.elapsedNow()

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
