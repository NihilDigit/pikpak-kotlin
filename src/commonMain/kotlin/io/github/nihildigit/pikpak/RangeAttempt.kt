package io.github.nihildigit.pikpak

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * One HTTP request inside a [RangeReader.read], from the moment it is sent to
 * the moment it stops delivering bytes.
 *
 * A read is not a request. Expiry, a 503 and a truncated body each end one
 * attempt and start another at the offset already reached, so a read that took
 * ten seconds may be one slow request or three quick ones and two refreshes.
 * Only at this granularity do the two look different.
 *
 * Aggregate throughput cannot answer the question this exists for. Eight
 * connections delivering 1.2 MB/s in total say nothing about whether they are
 * eight even connections or seven fast ones and one crawling, and an in-order
 * consumer waits for the slowest of the eight. [timeline] and [timeToFirstByte]
 * are here so one capture on a link that misbehaves is enough to tell which:
 *
 *  - a timeline that roughly doubles each round trip is slow-start bound, and
 *    the request ended before the connection ever reached its ceiling
 *  - a flat timeline well under the link's capacity is a window pinned by
 *    something that is not this request — a collapsed congestion window, or
 *    shaping at the other end
 *  - bytes that arrive quickly and then stop is a loss event mid-body
 *
 * [timeToFirstByte] carries the round trip separately, because at 200 ms a
 * request for a few hundred kilobytes spends a large part of its life in the
 * handshake and in slow start rather than moving bytes.
 *
 * One attempt is not always one HTTP round trip. `HttpEngine` retries 5xx and
 * transport errors before the body reaches the pump, so a request the CDN
 * answered 503 twice arrives here as a single attempt with an unusually long
 * [timeToFirstByte] and no [Outcome.Throttled] to show for it. A large time to
 * first byte therefore means "the bytes were late", not "the route is long" —
 * the [timeline] is what separates the two, an internal retry showing as empty
 * slots rather than slow ones.
 */
class RangeAttempt(
    /** Offset the request asked from. Later attempts of one read start further in. */
    val start: Long,
    /** Bytes asked for, or null for a read that runs to the end of the file. */
    val requested: Long?,
    /** Bytes this attempt handed over before it ended. */
    val delivered: Long,
    /**
     * Request sent to response headers, or null when none arrived.
     *
     * Everything before the CDN commits to answering: a connection taken from
     * the pool or built from scratch, the request, and whatever the server does
     * before its first header. Against a known round trip this separates a
     * reused connection (about one) from a fresh TCP and TLS handshake (three
     * to four) from a request that queued somewhere below this code.
     */
    val timeToHeaders: Duration?,
    /**
     * Request sent to first body byte, or null when no byte ever arrived.
     *
     * The gap between this and [timeToHeaders] is the server holding a response
     * open without sending, which is a different fault from either a slow
     * handshake or a slow body.
     */
    val timeToFirstByte: Duration?,
    /** Request sent to the last byte, or to the failure that ended it. */
    val duration: Duration,
    /** Reads holding a connection slot on this file when the attempt began. */
    val activeReads: Int,
    /** Reads waiting for a slot on this file when the attempt began. */
    val queuedReads: Int,
    /**
     * The priority this read asked for, on
     * [PikPakStreamReader.BLOCKING_PRIORITY]'s scale.
     *
     * Carried so latencies can be split by it. The gate hands out slots by
     * priority, so if it is doing its job a blocking read's distribution sits
     * below read-ahead's; if the two are the same, whatever the reads are
     * waiting for is below the gate and no amount of reordering there helps.
     */
    val priority: Int,
    val outcome: Outcome,
    /** Width of one [timeline] slot. */
    val bucketDuration: Duration,
    /**
     * Bytes delivered per [bucketDuration], the first slot starting when the
     * request was sent. An attempt outrunning the array folds its tail into the
     * last slot, so the sum always matches [delivered] and only the resolution
     * of a long attempt is lost.
     */
    val timeline: LongArray,
) {
    /** How the attempt ended. Everything but [Complete] means another attempt followed. */
    enum class Outcome {
        /** The body arrived in full, or ran to the end of the file. */
        Complete,

        /** The signature had expired; the URL was refreshed and the range reissued. */
        Expired,

        /** 503 — the URL is at its connection cap. Backpressure, not failure. */
        Throttled,

        /** A 4xx that is not an expiry, e.g. the file object moved. */
        Rejected,

        /** A transport failure or a body that stopped early. */
        Failed,
    }

    /** Bytes per second over [duration], counting the round trip. Zero when nothing arrived. */
    val bytesPerSecond: Long
        get() = if (delivered <= 0) 0 else delivered * 1000 / duration.inWholeMilliseconds.coerceAtLeast(1)

    /**
     * Bytes per second from the first byte onwards.
     *
     * The rate the connection itself ran at, with the round trip taken out. At
     * 200 ms RTT the two differ by more than a factor of two on a small block,
     * and mistaking one for the other is what makes a healthy connection on a
     * long route look like a broken one.
     */
    val bytesPerSecondAfterFirstByte: Long
        get() {
            val ttfb = timeToFirstByte ?: return 0
            val body = duration - ttfb
            if (delivered <= 0) return 0
            return delivered * 1000 / body.inWholeMilliseconds.coerceAtLeast(1)
        }

    /** The timeline as `kB/s` per slot, trailing empty slots dropped. Diagnostics only. */
    fun renderTimeline(): String {
        val last = timeline.indexOfLast { it > 0 }
        if (last < 0) return "(nothing)"
        val perSlot = bucketDuration.inWholeMilliseconds.coerceAtLeast(1)
        return (0..last).joinToString(" ") { (timeline[it] * 1000 / perSlot / 1024).toString() }
    }

    override fun toString(): String = buildString {
        append("$start+${requested ?: "eof"} -> $delivered B in $duration")
        append(" (headers ${timeToHeaders ?: "none"}, ttfb ${timeToFirstByte ?: "none"}, ${bytesPerSecond / 1024} kB/s")
        append(", ${bytesPerSecondAfterFirstByte / 1024} kB/s after first byte)")
        append(" $outcome, prio $priority, $activeReads active / $queuedReads queued")
        append(", kB/s per ${bucketDuration}: ${renderTimeline()}")
    }

    companion object {
        /**
         * Timeline resolution. One round trip on a long route is about this, so
         * a slow-start ramp lands roughly one doubling per slot.
         */
        val BUCKET: Duration = 200.milliseconds

        /** Slots kept, i.e. the first 20 s of an attempt at full resolution. */
        const val BUCKETS: Int = 100
    }
}

/**
 * Collects one [RangeAttempt] while the bytes are arriving.
 *
 * One recorder per attempt, touched only by the coroutine pumping that attempt,
 * so nothing here is synchronised.
 */
internal class RangeAttemptRecorder(
    private val start: Long,
    private val requested: Long?,
    private val activeReads: Int,
    private val queuedReads: Int,
    private val priority: Int,
) {
    private val mark = TimeSource.Monotonic.markNow()
    private val timeline = LongArray(RangeAttempt.BUCKETS)
    private var headersAt: Duration? = null
    private var firstByteAt: Duration? = null
    private var delivered = 0L

    /**
     * Called when the response headers arrive.
     *
     * The pump learns this for free: `streamRangeFromUrl` invokes its block
     * only once the status and headers are in hand, so entering that block is
     * the moment, and nothing in the endpoint has to be threaded through.
     */
    fun headersReceived() {
        if (headersAt == null) headersAt = mark.elapsedNow()
    }

    fun record(bytes: Int) {
        if (bytes <= 0) return
        val elapsed = mark.elapsedNow()
        if (firstByteAt == null) firstByteAt = elapsed
        delivered += bytes
        // The tail of an attempt longer than the array folds into the last slot
        // rather than being dropped, so the sum still reconciles with delivered.
        val slot = (elapsed / RangeAttempt.BUCKET).toInt().coerceIn(0, RangeAttempt.BUCKETS - 1)
        timeline[slot] += bytes.toLong()
    }

    fun finish(outcome: RangeAttempt.Outcome) = RangeAttempt(
        start = start,
        requested = requested,
        delivered = delivered,
        timeToHeaders = headersAt,
        timeToFirstByte = firstByteAt,
        duration = mark.elapsedNow(),
        activeReads = activeReads,
        queuedReads = queuedReads,
        priority = priority,
        outcome = outcome,
        bucketDuration = RangeAttempt.BUCKET,
        timeline = timeline,
    )
}
