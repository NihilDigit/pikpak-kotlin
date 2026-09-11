package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.PriorityGate
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Why [RangeReader] is asking for a URL. */
sealed interface UrlRequest {
    /** No URL yet. */
    data object Initial : UrlRequest

    /** [previous] came back 401/403 — its signature has expired or been revoked. */
    data class Expired(val previous: String) : UrlRequest

    /** [previous] failed with a client error that is not an expiry, e.g. 404 after the file moved. */
    data class Rejected(val previous: String, val status: Int) : UrlRequest
}

/** Live counters for one [RangeReader]. */
data class RangeReaderStats(
    /** Reads currently holding a connection slot. */
    val activeReads: Int = 0,
    /** Reads waiting for a slot. */
    val queuedReads: Int = 0,
    /** Bytes delivered to callers since construction. */
    val bytesRead: Long = 0,
    /** How many times the URL was refreshed. */
    val urlRefreshes: Int = 0,
    /** When the URL was last refreshed. */
    val lastUrlRefreshAt: Instant? = null,
    /** Requests retried after a transport failure or a truncated body. */
    val retries: Int = 0,
    /** Requests the CDN answered 503 — over its per-URL connection cap, not a failure. */
    val throttled: Int = 0,
)

/**
 * Concurrent random-access reader over one remote file.
 *
 * Built for playback: give it a way to obtain a signed URL and it will serve
 * arbitrary byte ranges, keeping the connection count inside what PikPak's CDN
 * accepts and refreshing the URL when the signature expires. Nothing is
 * buffered or cached here — piece scheduling, disk layout and read-ahead
 * belong to the caller.
 *
 * What it handles:
 *  - **Connection budget.** All reads on one instance share [connectionBudget]
 *    slots. A signed PikPak URL accepts 8 concurrent connections and answers
 *    the 9th with 503, so the budget is a hard property of the URL, not a
 *    tuning knob. Every read also takes a slot from
 *    [PikPakClient.accountConnectionBudget], which is what stops several
 *    readers from together exceeding what the account is allowed.
 *  - **Priority.** When slots are contended, a higher [read] priority is
 *    served first, both among this file's reads and against every other file
 *    the client is reading. Playback-head reads should outrank read-ahead,
 *    and both should outrank a background download.
 *  - **Expiry.** 401/403 calls [urlProvider] with [UrlRequest.Expired] and
 *    reissues the request. Concurrent reads share one refresh.
 *  - **Throttling.** 503 backs off and retries without spending a retry.
 *  - **Truncation.** A body that stops early, or an I/O failure mid-body,
 *    resumes from the offset already delivered instead of restarting.
 *
 * What it does not handle: caching, read-ahead, and the file's identity. The
 * reader never calls `getFile` itself — [urlProvider] owns that, which is what
 * lets a caller layer its own fallbacks behind it.
 *
 * Cancelling the coroutine that called [read] closes that read's connection
 * and frees its slot.
 */
class RangeReader internal constructor(
    private val client: PikPakClient,
    private val urlProvider: suspend (UrlRequest) -> String,
    val connectionBudget: Int = PikPakClient.DEFAULT_CONNECTION_BUDGET,
    /**
     * Transport failures tolerated per [read] before it gives up. Counts
     * truncated bodies and I/O errors; 503 and URL expiry do not count.
     */
    private val maxAttempts: Int = 5,
    /**
     * The per-file gate to take slots from, or null to own one.
     *
     * [PikPakFileHandle] passes its own, because it replaces this reader when
     * a signature is about to expire and the reads already running on the old
     * instance do not stop when it is closed. With a gate each, the two
     * overlap and the file can briefly hold twice [connectionBudget]
     * connections on one signed URL — which is exactly what the CDN answers
     * with 503.
     */
    gate: PriorityGate?,
) : AutoCloseable {

    constructor(
        client: PikPakClient,
        urlProvider: suspend (UrlRequest) -> String,
        connectionBudget: Int = PikPakClient.DEFAULT_CONNECTION_BUDGET,
        maxAttempts: Int = 5,
    ) : this(client, urlProvider, connectionBudget, maxAttempts, null)

    init {
        require(connectionBudget >= 1) { "connectionBudget must be >= 1, got $connectionBudget" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
    }

    private val gate = gate ?: PriorityGate(connectionBudget)

    /** Identity of the gate, so a test can tell a shared one from a fresh one. */
    internal fun gateForTest(): Any = gate

    /** Whether [close] has been called, for a test asserting it has not. */
    internal fun isClosedForTest(): Boolean = closed
    private val urlMutex = Mutex()
    private var url: String? = null
    private var closed = false

    private val _stats = MutableStateFlow(RangeReaderStats())
    val stats: StateFlow<RangeReaderStats> = _stats.asStateFlow()

    /**
     * Reads [length] bytes starting at [start] (to EOF when [length] is null)
     * and hands them to [block] as a stream.
     *
     * The channel [block] receives is stitched across however many HTTP
     * requests the read actually took, so a mid-body failure and the resume
     * that follows are invisible to it. Bytes arrive in order and exactly
     * once. The channel dies when [block] returns; do not let it escape.
     *
     * A range that runs past the end of the file ends at EOF, like a file
     * read: the channel closes after the bytes that exist, and [block] sees
     * fewer than [length] bytes rather than an error.
     *
     * @param priority higher wins a contended slot. Equal priorities are FIFO.
     */
    suspend fun <T> read(
        start: Long,
        length: Long? = null,
        priority: Int = 0,
        block: suspend (ByteReadChannel) -> T,
    ): T {
        require(start >= 0) { "start must be >= 0, got $start" }
        require(length == null || length >= 1) { "length must be >= 1 when non-null, got $length" }
        check(!closed) { "RangeReader is closed" }

        return withSlot(priority) {
            coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val failure = PumpFailure()
                val pump = launchPump(this, channel, failure, start, length)
                try {
                    val result = block(channel)
                    // Cancelling a channel only tells the reader that bytes
                    // stopped; the reason has to be carried out separately or
                    // the caller sees "short read" instead of "403".
                    failure.cause?.let { throw it }
                    result
                } catch (t: Throwable) {
                    val cause = failure.cause
                    if (cause != null && cause !== t) throw cause
                    throw t
                } finally {
                    // The caller may stop reading early — an aborted seek, a
                    // full buffer. Killing the pump is what releases the
                    // connection; without it the slot stays held until the
                    // server finishes sending a range nobody wants.
                    pump.cancel()
                    channel.cancel(CancellationException("read block finished"))
                }
            }
        }
    }

    private class PumpFailure {
        var cause: Throwable? = null
    }

    /** Reads a range into memory. Only for ranges small enough to hold; [read] is the general form. */
    suspend fun readBytes(start: Long, length: Long, priority: Int = 0): ByteArray {
        require(length <= Int.MAX_VALUE) { "readBytes cannot materialise $length bytes" }
        val out = ByteArray(length.toInt())
        read(start, length, priority) { channel ->
            var filled = 0
            while (filled < out.size) {
                val n = channel.readAvailable(out, filled, out.size - filled)
                if (n == -1) break
                filled += n
            }
            if (filled != out.size) {
                throw PikPakException(-1, "readBytes: got $filled of $length bytes at offset $start")
            }
        }
        return out
    }

    /**
     * Fetches a URL now so the first [read] does not pay for it. Useful right
     * after resolving a file, while the user is still looking at a spinner.
     */
    suspend fun prewarm(): String = currentUrl()

    /**
     * Rejects further [read] calls. Reads already in progress are owned by the
     * coroutines that started them and finish or cancel with those; nothing
     * here holds a connection between reads, so there is nothing to tear down.
     */
    override fun close() {
        closed = true
    }

    /**
     * Takes a slot from this file's budget, then one from the account's.
     *
     * The order is not arbitrary. This way a reader holds at most
     * [connectionBudget] account slots, so one busy file can never occupy the
     * whole account budget and starve another. The reverse order would let a
     * reader take every account slot for reads still queued behind its own
     * per-file gate, which cannot proceed and would hold the account gate shut
     * while doing nothing.
     */
    private suspend fun <T> withSlot(priority: Int, body: suspend () -> T): T {
        gate.acquire(priority)
        updateStats()
        try {
            client.accountGate.acquire(priority)
            try {
                return body()
            } finally {
                client.accountGate.release()
            }
        } finally {
            gate.release()
            updateStats()
        }
    }

    private fun launchPump(
        scope: CoroutineScope,
        sink: ByteChannel,
        failure: PumpFailure,
        start: Long,
        length: Long?,
    ): Job = scope.launch {
        try {
            pump(sink, start, length)
            sink.flushAndClose()
        } catch (t: Throwable) {
            // Record before cancelling: the reader wakes up the moment the
            // channel dies and must find the reason already there.
            if (t !is CancellationException) failure.cause = t
            sink.cancel(t)
            if (t is CancellationException) throw t
        }
    }

    private suspend fun pump(sink: ByteWriteChannel, start: Long, length: Long?) {
        var offset = start
        var remaining = length
        var failures = 0
        var throttles = 0
        var rejectionRefreshed = false

        while (remaining == null || remaining > 0) {
            val attemptUrl = currentUrl()
            var delivered = 0L
            var announced: Long? = null
            var clippedAtEof = false

            try {
                client.streamRangeFromUrl(attemptUrl, offset, remaining) { stream ->
                    announced = stream.contentLength
                    clippedAtEof = stream.endsBeforeRequested(offset, remaining)
                    val buffer = ByteArray(READ_CHUNK)
                    while (true) {
                        val n = stream.channel.readAvailable(buffer, 0, buffer.size)
                        if (n == -1) break
                        if (n > 0) {
                            sink.writeFully(buffer, 0, n)
                            sink.flush()
                            delivered += n
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                offset += delivered
                remaining = remaining?.minus(delivered)
                addBytes(delivered)

                when {
                    t is UrlExpiredException -> {
                        refreshUrl(UrlRequest.Expired(attemptUrl), attemptUrl)
                    }
                    t is PikPakException && t.httpStatus == 503 -> {
                        // Over the URL's connection cap. Somebody else's read
                        // will finish; this is a queue, not a failure.
                        throttles++
                        if (throttles > MAX_THROTTLE_WAITS) throw t
                        bumpThrottled()
                        delay(throttleBackoff(throttles))
                    }
                    t is PikPakException && t.httpStatus in 400..499 -> {
                        if (rejectionRefreshed) throw t
                        rejectionRefreshed = true
                        refreshUrl(UrlRequest.Rejected(attemptUrl, t.httpStatus!!), attemptUrl)
                    }
                    else -> {
                        failures++
                        if (failures >= maxAttempts) throw t
                        bumpRetries()
                        delay(client.retryPolicy.delayFor(failures - 1))
                    }
                }
                continue
            }

            offset += delivered
            remaining = remaining?.minus(delivered)
            addBytes(delivered)

            val truncated = announced != null && delivered < announced!!
            if (!truncated) {
                // An open-ended read is done when the server's own
                // Content-Length has been delivered in full. A closed range
                // that the server clipped at EOF is done too: asking for the
                // rest would be a request entirely past the end, which the
                // CDN answers with 416 and no amount of retrying changes.
                if (remaining == null || remaining <= 0L || clippedAtEof) return
            }
            if (delivered == 0L) {
                failures++
                if (failures >= maxAttempts) {
                    throw PikPakException(
                        -1,
                        "RangeReader: no progress at offset $offset after $failures attempts",
                    )
                }
                bumpRetries()
                delay(client.retryPolicy.delayFor(failures - 1))
            } else {
                bumpRetries()
            }
        }
    }

    private suspend fun currentUrl(): String = urlMutex.withLock {
        url ?: urlProvider(UrlRequest.Initial).also { url = it }
    }

    /**
     * Replaces [stale] with a fresh URL. Eight reads hitting the same expired
     * signature at once must not become eight getFile calls, so a refresh that
     * already happened is reused.
     */
    private suspend fun refreshUrl(reason: UrlRequest, stale: String): String = urlMutex.withLock {
        val existing = url
        if (existing != null && existing != stale) return existing
        val fresh = urlProvider(reason)
        if (fresh == stale) {
            throw PikPakException(
                -1,
                "RangeReader: urlProvider returned the same rejected URL; it cannot make progress",
                errorDescription = stale,
            )
        }
        url = fresh
        val now = Clock.System.now()
        _stats.update { it.copy(urlRefreshes = it.urlRefreshes + 1, lastUrlRefreshAt = now) }
        fresh
    }

    // Counters are bumped from every pump at once; a read-copy-write on
    // StateFlow.value would lose increments, update() retries on contention.

    private fun updateStats() {
        _stats.update { it.copy(activeReads = gate.inUse, queuedReads = gate.queued) }
    }

    private fun addBytes(count: Long) {
        if (count <= 0) return
        _stats.update { it.copy(bytesRead = it.bytesRead + count) }
    }

    private fun bumpRetries() {
        _stats.update { it.copy(retries = it.retries + 1) }
    }

    private fun bumpThrottled() {
        _stats.update { it.copy(throttled = it.throttled + 1) }
    }

    /**
     * True when the server's Content-Range stops short of the requested end
     * because the file does. Distinguished from a plain short response by the
     * total: the sent range must run up to it, or the total must be unknown.
     */
    private fun RangeStream.endsBeforeRequested(offset: Long, remaining: Long?): Boolean {
        val requestedEnd = remaining?.let { offset + it - 1 } ?: return false
        val sentEnd = rangeEndInclusive ?: return false
        if (sentEnd >= requestedEnd) return false
        val total = totalSize ?: return true
        return sentEnd + 1 >= total
    }

    private fun throttleBackoff(attempt: Int): Duration =
        (THROTTLE_BASE_DELAY_MS * attempt).coerceAtMost(THROTTLE_MAX_DELAY_MS).milliseconds

    private companion object {
        const val READ_CHUNK = 64 * 1024

        /**
         * A 503 means the URL is at its connection cap; waiting is the whole
         * remedy. The ceiling only exists so a permanently saturated URL fails
         * instead of hanging.
         */
        const val MAX_THROTTLE_WAITS = 30
        const val THROTTLE_BASE_DELAY_MS = 200L
        const val THROTTLE_MAX_DELAY_MS = 2_000L
    }
}

/**
 * A [RangeReader] over [fileId] that refreshes its own URL via `getFile`.
 * The common case: the caller has a file id and wants bytes.
 */
fun PikPakClient.rangeReader(
    fileId: String,
    connectionBudget: Int = this.connectionBudget,
): RangeReader = RangeReader(
    client = this,
    urlProvider = {
        getFile(fileId).downloadUrl
            ?: throw PikPakException(-1, "rangeReader: file $fileId has no octet-stream link")
    },
    connectionBudget = connectionBudget,
)
