package io.github.nihildigit.pikpak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

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
    /** Time spent building them. Over [opened] it is what one handshake costs on this route. */
    val handshake: Duration = Duration.ZERO,
    /** Connection attempts that failed outright. */
    val failed: Long = 0,
) {
    /** Share of requests that had to build a connection, 0..1. Zero when nothing was recorded. */
    val missRate: Double get() = if (acquired == 0L) 0.0 else opened.toDouble() / acquired

    operator fun minus(earlier: CdnConnectionStats) = CdnConnectionStats(
        acquired = acquired - earlier.acquired,
        opened = opened - earlier.opened,
        handshake = handshake - earlier.handshake,
        failed = failed - earlier.failed,
    )

    override fun toString(): String =
        "acquired=$acquired opened=$opened (${(missRate * 1000).toLong() / 10.0}%) handshake=$handshake failed=$failed"

    companion object {
        private val state = MutableStateFlow(CdnConnectionStats())

        /** The counters as they stand now. Callers keep their own baseline and subtract. */
        fun snapshot(): CdnConnectionStats = state.value

        internal fun recordAcquired() = state.update { it.copy(acquired = it.acquired + 1) }

        internal fun recordOpened(took: Duration) =
            state.update { it.copy(opened = it.opened + 1, handshake = it.handshake + took) }

        internal fun recordFailed() = state.update { it.copy(failed = it.failed + 1) }
    }
}
