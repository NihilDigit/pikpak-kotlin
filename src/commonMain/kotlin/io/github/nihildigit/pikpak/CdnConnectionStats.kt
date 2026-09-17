package io.github.nihildigit.pikpak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * How often a CDN request had to build a connection instead of reusing one.
 *
 * The one thing a range request's latency cannot be decomposed without. Time to
 * the response headers covers taking a connection, sending the request, and the
 * CDN deciding to answer; on a long route a fresh TCP and TLS handshake is three
 * to four round trips and a reused connection is one, so the same measurement
 * can mean "the pool is churning" or "the server is slow" with nothing in it to
 * say which.
 *
 * Global rather than per client, because the counters are written from the HTTP
 * engine's event listener, which is installed when the client is built and has
 * no [PikPakClient] to belong to. A process talking to PikPak through several
 * clients sees their sum.
 *
 * Only the OkHttp-backed clients report — `defaultCdnHttpClient` on JVM and
 * Android. Everywhere else every field stays zero, which reads as "nothing was
 * measured", not as "nothing was rebuilt".
 */
data class CdnConnectionStats(
    /** Requests that took a connection, reused or newly built. */
    val acquired: Long = 0,
    /** Connections built from scratch: a TCP handshake, and TLS on top of it. */
    val opened: Long = 0,
    /**
     * Time spent building them, TLS included. Over [opened] it is what one
     * handshake costs on this route.
     */
    val handshake: Duration = Duration.ZERO,
    /**
     * The TLS part of [handshake]. Says whether a new connection is expensive
     * because of the route or because of the crypto on top of it; [tcp] is the
     * rest.
     */
    val tls: Duration = Duration.ZERO,
    /**
     * Name resolutions that reached the resolver. A lookup answered from the
     * system cache raises no callback, so this staying far below [opened] is
     * itself the answer to "is DNS in the path at all".
     */
    val dnsLookups: Long = 0,
    /** What those resolutions cost. Runs before [handshake], so it is not part of it. */
    val dns: Duration = Duration.ZERO,
    /** Connection attempts that failed outright. */
    val failed: Long = 0,
    /**
     * What those attempts waited before failing. Over [failed] it separates a
     * peer refusing at once from a handshake that ran into the connect timeout;
     * the two call for opposite responses.
     */
    val failedWait: Duration = Duration.ZERO,
    /**
     * Failures by cause, under a normalised key -- which kind of failure this
     * is, not how many there were of it in total. Capped at
     * [MAX_FAILURE_CAUSES] keys with the remainder under [OTHER_FAILURES]: a
     * generic IOException carries a different message every attempt, and an
     * uncapped map would keep growing for the life of the process.
     */
    val failureCauses: Map<String, Long> = emptyMap(),
    /**
     * Connections being built at this instant. A gauge, not a total: [minus]
     * keeps the later value rather than a difference.
     */
    val connecting: Int = 0,
    /**
     * The highest [connecting] reached since the process started. Also a gauge,
     * and specifically not the peak inside the window a [minus] covers -- a
     * difference of two peaks is not a peak of anything.
     */
    val peakConnecting: Int = 0,
    /**
     * [connecting] summed over the failures. Over [failed] it is how many
     * handshakes were in flight when one broke, which is what decides whether
     * the far end is rejecting concurrency or just dropping packets.
     */
    val failedConnectingSum: Long = 0,
) {
    /** Share of requests that had to build a connection, 0..1. Zero when nothing was recorded. */
    val missRate: Double get() = if (acquired == 0L) 0.0 else opened.toDouble() / acquired

    /**
     * The transport part of [handshake]. Derived, because OkHttp reports TLS as
     * a span inside the connect span rather than beside it.
     */
    val tcp: Duration get() = handshake - tls

    operator fun minus(earlier: CdnConnectionStats) = CdnConnectionStats(
        acquired = acquired - earlier.acquired,
        opened = opened - earlier.opened,
        handshake = handshake - earlier.handshake,
        tls = tls - earlier.tls,
        dnsLookups = dnsLookups - earlier.dnsLookups,
        dns = dns - earlier.dns,
        failed = failed - earlier.failed,
        failedWait = failedWait - earlier.failedWait,
        failureCauses = failureCauses.minusCounts(earlier.failureCauses),
        connecting = connecting,
        peakConnecting = peakConnecting,
        failedConnectingSum = failedConnectingSum - earlier.failedConnectingSum,
    )

    override fun toString(): String = buildString {
        append("acquired=").append(acquired)
        append(" opened=").append(opened).append(" (").append((missRate * 1000).toLong() / 10.0).append("%)")
        append(" handshake=").append(handshake.token())
        append("(dns=").append(dns.token()).append('/').append(dnsLookups)
        append(" tcp=").append(tcp.token()).append(" tls=").append(tls.token()).append(')')
        append(" failed=").append(failed)
        append(" failedWait=").append(failedWait.token())
        append(" depth=").append(connecting).append('/').append(peakConnecting)
        append(" failDepth=").append(if (failed == 0L) 0.0 else (failedConnectingSum * 10 / failed) / 10.0)
        if (failureCauses.isNotEmpty()) {
            append(" causes=")
            failureCauses.entries.sortedByDescending { it.value }.take(3)
                .joinTo(this, ",") { "${it.key}:${it.value}" }
        }
    }

    companion object {
        private val state = MutableStateFlow(CdnConnectionStats())

        /** The counters as they stand now. Callers keep their own baseline and subtract. */
        fun snapshot(): CdnConnectionStats = state.value

        internal fun recordAcquired() = state.update { it.copy(acquired = it.acquired + 1) }

        internal fun recordResolved(took: Duration) =
            state.update { it.copy(dnsLookups = it.dnsLookups + 1, dns = it.dns + took) }

        internal fun recordConnectStart() = state.update {
            val connecting = it.connecting + 1
            it.copy(connecting = connecting, peakConnecting = maxOf(it.peakConnecting, connecting))
        }

        internal fun recordOpened(took: Duration, secure: Duration) = state.update {
            it.copy(
                opened = it.opened + 1,
                handshake = it.handshake + took,
                tls = it.tls + secure,
                connecting = (it.connecting - 1).coerceAtLeast(0),
            )
        }

        internal fun recordFailed(type: String?, message: String?, waited: Duration) {
            // Outside update: the lambda reruns on contention and the key does not change.
            val key = failureKey(type, message)
            state.update {
                val causes = it.failureCauses
                val bucket =
                    if (key in causes || causes.size < MAX_FAILURE_CAUSES) key else OTHER_FAILURES
                it.copy(
                    failed = it.failed + 1,
                    failedWait = it.failedWait + waited,
                    failureCauses = causes + (bucket to ((causes[bucket] ?: 0L) + 1)),
                    failedConnectingSum = it.failedConnectingSum + it.connecting,
                    connecting = (it.connecting - 1).coerceAtLeast(0),
                )
            }
        }
    }
}

/**
 * Seconds, never "2m 45.2s": the default rendering of a large [Duration] holds a
 * space, and the line it goes into is read as space-separated key=value pairs.
 */
private fun Duration.token(): String = toString(DurationUnit.SECONDS, 2)

/** Room for the handful of causes that repeat, plus [OTHER_FAILURES]. */
private const val MAX_FAILURE_CAUSES = 8
private const val OTHER_FAILURES = "other"

/** How much of a failure message is read. Bounds the cost of [failureKey]. */
private const val MESSAGE_HEAD = 64

/**
 * Types whose name alone does not say what went wrong, so the message has to be
 * folded into the key.
 */
private val GENERIC_FAILURE_TYPES = setOf("IOException", "SocketException", "Exception")

private fun failureKey(type: String?, message: String?): String {
    val name = type?.takeIf { it.isNotEmpty() } ?: "Unknown"
    if (name !in GENERIC_FAILURE_TYPES || message.isNullOrEmpty()) return name
    // The message is the only thing telling one generic failure from another,
    // but it also carries addresses, ports and byte counts, and one key per
    // attempt would blow past the cap immediately. Words containing a digit are
    // dropped, and only the head of the message is read.
    val summary = message.take(MESSAGE_HEAD)
        .split(' ', ':', ',')
        .filter { it.isNotEmpty() && it.none(Char::isDigit) }
        .take(4)
        .joinToString("_")
    return if (summary.isEmpty()) name else "$name/$summary"
}

private fun Map<String, Long>.minusCounts(earlier: Map<String, Long>): Map<String, Long> {
    if (earlier.isEmpty()) return this
    val result = LinkedHashMap<String, Long>(size)
    for ((key, count) in this) {
        val delta = count - (earlier[key] ?: 0L)
        if (delta > 0) result[key] = delta
    }
    return result
}
