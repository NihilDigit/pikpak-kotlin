package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.FileReactivationCoordinator
import io.github.nihildigit.pikpak.internal.HostHealth
import io.github.nihildigit.pikpak.internal.PriorityGate
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.RecoveryPolicy
import io.github.nihildigit.pikpak.internal.RequestOrder
import io.github.nihildigit.pikpak.internal.SignedUrlGates
import io.github.nihildigit.pikpak.internal.isThrottlePressure
import io.github.nihildigit.pikpak.internal.retryAfter
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** Why [RangeReader] is asking for a URL. */
sealed interface UrlRequest {
    /** No URL yet. */
    data object Initial : UrlRequest

    /** [previous] came back 401/403 — its signature has expired or been revoked. */
    data class Expired(val previous: String) : UrlRequest

    /** [previous] failed with a client error that is not an expiry, e.g. 404 after the file moved. */
    data class Rejected(val previous: String, val status: Int) : UrlRequest

    /**
     * [previous]'s edge host did not answer in time, or broke the transfer. The link itself is
     * fine; what is wanted is a fresh one, which PikPak signs for a host picked anew each time.
     */
    data class Unresponsive(val previous: String) : UrlRequest

    /**
     * [previous] answered `206 Partial Content` with a `Content-Range` total length ([actual])
     * that disagrees with the caller's known byte length ([expected]) — e.g. PikPak serving a
     * transcoded representation on an `is_origin` link instead of the raw file.
     */
    data class SizeMismatch(val previous: String, val actual: Long, val expected: Long) : UrlRequest
}

/** Live counters for one [RangeReader]. */
data class RangeReaderStats(
    /**
     * Reads holding a slot of the file's gate. A [PikPakFileHandle] shares one gate across the
     * readers it replaces on refresh, so this counts the file, not this reader alone.
     */
    val activeReads: Int = 0,
    /** Reads waiting for a slot of the file's gate; shared the same way. */
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
    /**
     * Called with every HTTP request this reader finishes, successful or not.
     *
     * Off by default: [stats] is enough to watch a transfer, and this is for
     * diagnosing a link where the aggregate looks healthy and one connection is
     * not. See [RangeAttempt] for what that distinction needs.
     *
     * Invoked from whichever coroutine was pumping the attempt, so several may
     * arrive at once and none of them may block.
     */
    private val onAttempt: ((RangeAttempt) -> Unit)? = null,
    private val timeSource: TimeSource = client.timeSource,
    private val coordinator: FileReactivationCoordinator = FileReactivationCoordinator(client),
    private val onBodyProgress: (suspend (Duration, Long) -> Unit)? = null,
    private val allowEmptyBodyReactivation: Boolean = false,
    private val expectedTotal: Long? = null,
) : RangeSource, AutoCloseable {

    constructor(
        client: PikPakClient,
        urlProvider: suspend (UrlRequest) -> String,
        connectionBudget: Int = PikPakClient.DEFAULT_CONNECTION_BUDGET,
        maxAttempts: Int = 5,
        onAttempt: ((RangeAttempt) -> Unit)? = null,
        timeSource: TimeSource = client.timeSource,
    ) : this(client, urlProvider, connectionBudget, maxAttempts, null, onAttempt, timeSource)

    init {
        require(connectionBudget >= 1) { "connectionBudget must be >= 1, got $connectionBudget" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
    }

    private val gate = gate ?: PriorityGate(minOf(8, connectionBudget))

    /** Identity of the gate, so a test can tell a shared one from a fresh one. */
    internal fun gateForTest(): Any = gate

    /** Whether [close] has been called, for a test asserting it has not. */
    internal fun isClosedForTest(): Boolean = closed
    private val urlMutex = Mutex()
    private val contentMutex = Mutex()
    private var knownTotal: Long? = expectedTotal?.takeIf { it > 0L }

    private class RepresentationSizeMismatch(val actual: Long, val expected: Long) :
        Exception("RangeReader: representation size $actual does not match expected $expected")

    private suspend fun validateTotal(total: Long?) = contentMutex.withLock {
        if (total != null) {
            val expected = expectedTotal?.takeIf { it > 0L }
            if (expected != null && total != expected) {
                throw RepresentationSizeMismatch(actual = total, expected = expected)
            }
            val previous = knownTotal
            if (previous != null && previous != total) {
                throw PikPakException(-1, "RangeReader: representation size changed from $previous to $total")
            }
            knownTotal = total
        }
    }
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
     * @param priority higher wins a contended slot. Equal priorities go to the
     *   older demand (a RequestOrder on the calling context, which the stream
     *   reader sets), then to arrival order.
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

        return coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val failure = PumpFailure()
                val pump = launchPump(this, channel, failure, start, length, priority)
                var completed = false
                var cancelCause: CancellationException? = null
                try {
                    val result = block(channel)
                    completed = true
                    if (length != null) {
                        withTimeoutOrNull(50.milliseconds) { pump.join() }
                    }
                    failure.cause?.let { throw it }
                    channel.closedCause?.let { throw it }
                    result
                } catch (t: Throwable) {
                    val cause = failure.cause
                    val thrown = if (cause != null && cause !== t) cause else t
                    if (thrown is CancellationException) cancelCause = thrown
                    else if (t is CancellationException) cancelCause = t
                    throw thrown
                } finally {
                    withContext(NonCancellable) {
                        failure.draining = true
                        channel.cancel(cancelCause ?: CancellationException("read block finished"))
                        if (completed) {
                            val remaining = failure.remainingBytes()
                            if (remaining in 1L..DRAIN_SOCKET_MAX_BYTES && client.hostHealth.tryAcquireDrain()) {
                                try {
                                    withTimeoutOrNull(DRAIN_SOCKET_GRACE) { pump.join() }
                                } finally {
                                    client.hostHealth.releaseDrain()
                                }
                            }
                            pump.cancel()
                            withTimeoutOrNull(50.milliseconds) { pump.join() }
                        } else {
                            // Zero-grace teardown on cancellation, preemption, or owner withdrawal.
                            pump.cancel(cancelCause ?: CancellationException("read cancelled"))
                            pump.join()
                        }
                        currentCoroutineContext()[FetchClaimControl]?.clearExchange()
                    }
                }
        }
    }

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T = read(start, length as Long?, priority, block)

    private class PumpFailure {
        @Volatile var cause: Throwable? = null
        @Volatile var announced: Long = -1L
        @Volatile var delivered: Long = 0L
        @Volatile var draining: Boolean = false
        @Volatile var readSeq: Long = 0L
        @Volatile var activeSilenceNanos: Long = 0L
        @Volatile var readStartedAt: TimeMark? = null
        fun remainingBytes(): Long = if (announced <= 0L) -1L else (announced - delivered).coerceAtLeast(0L)
    }

    /** The CDN took the request and sent nothing back within [limit]. Not a CancellationException, so it reaches the retry path. */
    private class UnresponsiveHost(val limit: Duration) : Exception("no response headers within $limit")

    /** Headers arrived, then the body delivered nothing for [limit] after [afterBytes]. */
    private class SinkFailure(val original: Throwable) : Exception("consumer channel failed", original)

    private class BodySilence(val limit: Duration, val afterBytes: Long) :
        Exception("stream went silent for $limit after $afterBytes bytes")

    /**
     * Reads a range into memory. Only for ranges small enough to hold; [read] is the general
     * form. Like [RangeSource.readBytes], a range past the end of the file comes back short.
     */
    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray {
        require(length <= Int.MAX_VALUE) { "readBytes cannot materialise $length bytes" }
        return read(start, length, priority) { channel -> channel.readFully(length.toInt()) }
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

    /** Supplies file identity for atomic admission; a queued read holds no partial permits. */
    private suspend fun <T> withSlot(priority: Int, body: suspend () -> T): T {
        return withContext(io.github.nihildigit.pikpak.internal.FileAdmission(gate, connectionBudget) { active, queued ->
            _stats.update { it.copy(activeReads = active, queuedReads = queued) }
        }) { body() }
    }

    private fun launchPump(
        scope: CoroutineScope,
        sink: ByteChannel,
        failure: PumpFailure,
        start: Long,
        length: Long?,
        priority: Int,
    ): Job = scope.launch {
        val telemetry = currentCoroutineContext()[ReadTelemetry]
        try {
            pump(sink, failure, start, length, priority, telemetry)
            sink.flushAndClose()
        } catch (t: Throwable) {
            // Record before cancelling: the reader wakes up the moment the
            // channel dies and must find the reason already there.
            if (t is SinkFailure) failure.cause = t.original
            else if (t !is CancellationException) failure.cause = t
            sink.cancel(failure.cause ?: t)
            if (t is CancellationException) throw t
        }
    }

    private suspend fun <T> runRetryStep(
        claimControl: FetchClaimControl?,
        offset: Long,
        step: suspend () -> T,
    ): T {
        if (claimControl == null) return step()
        if (claimControl.finishInFlightOnly) {
            throw PikPakException(-1, "RangeReader: FinishInFlight forbids retry at offset $offset")
        }
        return coroutineScope {
            val stepDeferred = async(start = CoroutineStart.UNDISPATCHED) { step() }
            val abortWatch = launch(start = CoroutineStart.UNDISPATCHED) {
                claimControl.finishInFlightOnlyFlow.first { it }
                stepDeferred.cancel(CancellationException("FinishInFlight aborted retry"))
            }
            try {
                stepDeferred.await()
            } catch (e: CancellationException) {
                if (claimControl.finishInFlightOnly && currentCoroutineContext().isActive) {
                    throw PikPakException(-1, "RangeReader: FinishInFlight aborted retry at offset $offset", cause = e)
                }
                throw e
            } finally {
                abortWatch.cancel()
            }
        }
    }

    private suspend fun pump(
        sink: ByteWriteChannel,
        state: PumpFailure,
        start: Long,
        length: Long?,
        priority: Int,
        telemetry: ReadTelemetry?,
    ) = client.signedUrlGates.withReplacementHandoff { handoff ->
        val outerClaimControl = currentCoroutineContext()[FetchClaimControl]
        var attemptsStarted = 0
        var offset = start
        var remaining = length
        var failures = 0
        var bodyStalls = 0
        var throttles = 0
        var emptyBodies = 0
        var rejectionRefreshed = false
        var sizeMismatchRefreshes = 0
        val failedReroutes = HashSet<String>()
        var chunkBuffer: ByteArray? = null
        var forcedAttemptUrl: String? = null

        while ((remaining == null || remaining > 0) && !state.draining) {
            if (outerClaimControl != null && offset >= outerClaimControl.allowedProductionEndOffset) {
                return@withReplacementHandoff
            }
            if (attemptsStarted > 0 && outerClaimControl?.finishInFlightOnly == true) {
                throw PikPakException(-1, "RangeReader: FinishInFlight forbids follow-up request at offset $offset")
            }
            attemptsStarted++
            val pendingReplacementUrl = forcedAttemptUrl.also { forcedAttemptUrl = null }
            // The link is what refreshes and expiry are about; the attempt may go to a sibling
            // host with the same path and signature, see HostHealth.route. Every refresh below
            // is keyed on linkUrl, so a reroute never looks like a changed link.
            val observedGeneration = coordinator.generation
            val linkUrl = currentUrl()
            val validReplacementUrl = if (pendingReplacementUrl != null) {
                if (SignedUrlGates.identityOf(pendingReplacementUrl) == SignedUrlGates.identityOf(linkUrl)) {
                    pendingReplacementUrl
                } else {
                    client.signedUrlGates.releaseHandoff(handoff)
                    null
                }
            } else {
                null
            }
            val started = telemetry?.let { timeSource.markNow() }
            // A reader parked on the block is not the place to try an unproven host, whether or not
            // its caller called it urgent: a player filling its buffer still stops if that block is late
            val mayExplore = priority < PikPakStreamReader.READ_AHEAD_PRIORITY
            val attemptUrl = validReplacementUrl ?: run {
                val routedUrl = client.hostHealth.route(
                    linkUrl,
                    mayExplore = mayExplore,
                    requestedBytes = remaining ?: HostHealth.MIN_SAMPLE_BYTES,
                    priority = priority,
                )
                val rh = HostHealth.hostOf(routedUrl)
                if (rh != null && rh in failedReroutes) linkUrl else routedUrl
            }
            val routedHost = HostHealth.hostOf(attemptUrl)
            val rerouted = attemptUrl != linkUrl
            var wireDelivered = 0L
            var forwardedBytes = 0L
            var stoppedAtBoundary = false
            var replacementTargetUrl: String? = null
            var announced: Long? = null
            var clippedAtEof = false
            var headersAnswered = false

            // Started even when nobody is listening: the branch would have to
            // be repeated at all six places an attempt can end, and a recorder
            // nothing reads costs one allocation per request.
            val attempt = RangeAttemptRecorder(offset, remaining, _stats.value.activeReads, _stats.value.queuedReads, priority, HostHealth.hostOf(attemptUrl), timeSource)
            attempt.admission.granted.invokeOnCompletion { failure -> if (failure == null) telemetry?.let { it.attempts++ } }

            val connectionTelemetry = telemetry ?: ReadTelemetry(timeSource)
            connectionTelemetry.resetConnection()
            try {
                // Only an extra context element, so the block below still runs
                // on this coroutine and this dispatcher; what it buys is that
                // the retries HttpEngine performs under this one request are
                // counted against this attempt and no other.
                withSlot(priority) {
                if (telemetry != null) {
                    if (rerouted) telemetry.rerouted = true
                }
                withContext(attempt.retries + attempt.admission + io.github.nihildigit.pikpak.internal.SignedRequestPriority(priority) + connectionTelemetry) {
                  coroutineScope {
                    // The request is past both gates here. What sits below them is the HTTP
                    // layer's own retries of a 5xx or 429, which are the host answering, only
                    // not yet with the body; so the clock restarts whenever one lands, and what
                    // is left is a host that said nothing at all.
                    // Throwing from the child fails the scope and cancels the request with it.
                    val answered = CompletableDeferred<Unit>()
                    val claimedWarm = client.hostHealth.onRequestStarted(attemptUrl)
                    val limit = client.hostHealth.firstResponseDeadline(attemptUrl, claimedWarm)
                    val claimControl = currentCoroutineContext()[FetchClaimControl]
                    val demand = currentCoroutineContext()[TransferDemand]
                    fun currentBodySilence(): Duration {
                        val effectivePriority = maxOf(priority, claimControl?.priorityFlow?.value ?: demand?.priority ?: priority)
                        return client.hostHealth.bodySilenceDeadline(effectivePriority)
                    }
                    val deadline = launch {
                        attempt.admission.granted.await()
                        var statusesSeen = attempt.retries.statuses
                        while (true) {
                            delay(limit)
                            if (answered.isCompleted) return@launch
                            val statuses = attempt.retries.statuses
                            if (statuses == statusesSeen) throw UnresponsiveHost(limit)
                            statusesSeen = statuses
                        }
                    }
                    val remainingSnap = remaining
                    client.streamRangeFromUrl(attemptUrl, offset, remaining) { stream ->
                        validateTotal(stream.totalSize)
                        headersAnswered = true
                        val exchangeId = claimControl?.beginExchange() ?: 0L
                        try {
                        answered.complete(Unit)
                        deadline.cancel()
                        val waited = attempt.headersReceived()
                        // A wait that spans a retry below measures the retry policy, not the line
                        val clean = attempt.retries.statuses == 0 && attempt.retries.transport == 0
                        client.hostHealth.answered(
                            attemptUrl,
                            waited.takeIf { clean },
                            connectionReused = connectionTelemetry.connectionReused,
                        )
                        announced = stream.contentLength
                        state.announced = announced ?: -1L
                        state.delivered = 0L
                        state.readSeq = 0L
                        state.readStartedAt = null
                        val initialSilence = currentBodySilence()
                        state.activeSilenceNanos = if (initialSilence.isFinite()) initialSilence.inWholeNanoseconds else Long.MAX_VALUE
                        clippedAtEof = stream.endsBeforeRequested(offset, remaining)
                        val buffer = chunkBuffer ?: ByteArray(READ_CHUNK).also { chunkBuffer = it }
                        var firstByteAt: TimeMark? = null
                        var shortWindowMark: TimeMark? = null
                        var shortWindowBytes = 0L
                        var shortWindowSinkWait = Duration.ZERO
                        var consecutiveQualifying = 0
                        var observedGoalRevision = Long.MIN_VALUE
                        coroutineScope {
                            val bodyWatchdog = launch {
                                var observedSeq = state.readSeq
                                var sleptOnObserved = Duration.ZERO
                                var nextDelay = currentBodySilence()
                                while (true) {
                                    delay(nextDelay)
                                    val seq1 = state.readSeq
                                    if (seq1 == observedSeq) {
                                        sleptOnObserved += nextDelay
                                    } else {
                                        observedSeq = seq1
                                        sleptOnObserved = Duration.ZERO
                                    }
                                    if ((seq1 and 1L) == 1L) {
                                        val limitNanos = state.activeSilenceNanos
                                        val startedAt = state.readStartedAt
                                        val deliveredSnap = state.delivered
                                        val seq2 = state.readSeq
                                        if (seq1 == seq2) {
                                            val limit = if (limitNanos == Long.MAX_VALUE) Duration.INFINITE else limitNanos.nanoseconds
                                            val elapsed = startedAt?.elapsedNow() ?: Duration.ZERO
                                            if (elapsed >= limit || sleptOnObserved >= limit) {
                                                throw BodySilence(limit, deliveredSnap)
                                            }
                                            nextDelay = (limit - maxOf(elapsed, sleptOnObserved)).coerceAtLeast(1.milliseconds)
                                            continue
                                        }
                                    }
                                    observedSeq = state.readSeq
                                    sleptOnObserved = Duration.ZERO
                                    nextDelay = currentBodySilence()
                                }
                            }
                            try {
                                while (true) {
                                    val ceilingBeforeRead = claimControl?.allowedProductionEndOffset ?: Long.MAX_VALUE
                                    val preReadForwardedOffset = offset + forwardedBytes
                                    if (!state.draining && preReadForwardedOffset >= ceilingBeforeRead) {
                                        stoppedAtBoundary = true
                                        state.announced = -1L
                                        break
                                    }
                                    val maxChunk = if (!state.draining && ceilingBeforeRead < Long.MAX_VALUE) {
                                        minOf(buffer.size.toLong(), (ceilingBeforeRead - preReadForwardedOffset).coerceAtLeast(1L)).toInt()
                                    } else {
                                        buffer.size
                                    }
                                    val bodySilence = currentBodySilence()
                                    state.activeSilenceNanos = if (bodySilence.isFinite()) bodySilence.inWholeNanoseconds else Long.MAX_VALUE
                                    state.readStartedAt = timeSource.markNow()
                                    state.readSeq += 1L
                                    val n = try {
                                        stream.channel.readAvailable(buffer, 0, maxChunk)
                                    } finally {
                                        state.readSeq += 1L
                                    }
                                    if (n == -1) {
                                        if (announced == null || wireDelivered >= announced!!) {
                                            client.hostHealth.onSocketReturned(attemptUrl)
                                        }
                                        break
                                    }
                                    if (n > 0) {
                                        if (announced != null && n.toLong() > announced!! - wireDelivered) {
                                            throw PikPakException(-1, "RangeReader: body exceeds Content-Range")
                                        }
                                        val wasFirstByte = firstByteAt == null
                                        if (wasFirstByte) {
                                            val nowMark = timeSource.markNow()
                                            firstByteAt = nowMark
                                            shortWindowMark = nowMark
                                            shortWindowBytes = 0L
                                            shortWindowSinkWait = Duration.ZERO
                                        }
                                        attempt.record(n)
                                        if (wasFirstByte) {
                                            val cleanFirstByte = attempt.retries.statuses == 0 && attempt.retries.transport == 0
                                            attempt.firstByteElapsed()?.takeIf { cleanFirstByte }?.let { ttfb ->
                                                client.hostHealth.recordFirstByteLatency(attemptUrl, ttfb)
                                            }
                                        }
                                        if (telemetry != null && telemetry.timeToFirstByte == null) {
                                            telemetry.timeToFirstByte = started?.elapsedNow()?.minus(attempt.gateWait)
                                        }
                                        telemetry?.recordBytes(n)
                                        addBytes(n.toLong())
                                        wireDelivered += n
                                        state.delivered = wireDelivered

                                        val ceilingAfterRead = claimControl?.allowedProductionEndOffset ?: Long.MAX_VALUE
                                        val preWriteOffset = offset + forwardedBytes
                                        val toWrite = if (!state.draining && ceilingAfterRead < Long.MAX_VALUE) {
                                            (ceilingAfterRead - preWriteOffset).coerceIn(0L, n.toLong()).toInt()
                                        } else {
                                            n
                                        }
                                        var chunkSinkWait = Duration.ZERO
                                        if (!state.draining && toWrite > 0) {
                                            val sinkStarted = timeSource.markNow()
                                            telemetry?.beginSinkWait()
                                            try {
                                                if (sink.isClosedForWrite) throw sink.closedCause
                                                    ?: IllegalStateException("consumer channel closed before draining")
                                                // ByteWriteChannel.writeFully copies [0, toWrite) into writeBuffer
                                                // synchronously before flushIfNeeded/flush can suspend on consumer
                                                // backpressure, so the channel owns these bytes as soon as writeFully
                                                // begins and a preemption during flush must resume after them.
                                                forwardedBytes += toWrite.toLong()
                                                claimControl?.markExchangeDelivered(exchangeId)
                                                sink.writeFully(buffer, 0, toWrite)
                                                sink.flush()
                                            } catch (e: CancellationException) {
                                                if (!state.draining && currentCoroutineContext().isActive) throw SinkFailure(e)
                                                throw e
                                            } catch (t: Throwable) {
                                                if (!state.draining) throw SinkFailure(t)
                                            } finally {
                                                telemetry?.endSinkWait()
                                                chunkSinkWait = sinkStarted.elapsedNow()
                                                attempt.recordSinkWait(chunkSinkWait)
                                                if (telemetry != null) telemetry.sinkWait += chunkSinkWait
                                            }
                                        }

                                        val forwardedOffset = offset + forwardedBytes
                                        val postFlushCeiling = claimControl?.allowedProductionEndOffset ?: Long.MAX_VALUE
                                        if (!state.draining && forwardedOffset >= postFlushCeiling) {
                                            if (announced != null && wireDelivered >= announced!!) {
                                                client.hostHealth.onSocketReturned(attemptUrl)
                                            } else {
                                                stoppedAtBoundary = true
                                                state.announced = -1L
                                            }
                                            break
                                        }

                                        val bodyElapsed = (firstByteAt!!.elapsedNow() - attempt.sinkWait).coerceAtLeast(Duration.ZERO)
                                        if (announced == null || wireDelivered < announced!!) {
                                            val bodyRate = attempt.bodyBytesPerSecond()
                                            onBodyProgress?.invoke(bodyElapsed, bodyRate)

                                            if (!wasFirstByte && !state.draining && claimControl != null) {
                                                shortWindowBytes += toWrite.toLong()
                                                shortWindowSinkWait += chunkSinkWait
                                                val rawWindowElapsed = shortWindowMark?.elapsedNow() ?: Duration.ZERO
                                                val netWindowElapsed = (rawWindowElapsed - shortWindowSinkWait).coerceAtLeast(Duration.ZERO)
                                                if (netWindowElapsed >= RecoveryPolicy.MIN_SHORT_WINDOW_MS.milliseconds) {
                                                    val shortWindowRate = if (shortWindowBytes > 0L) {
                                                        (shortWindowBytes * 1_000_000_000L) / netWindowElapsed.inWholeNanoseconds.coerceAtLeast(1L)
                                                    } else {
                                                        0L
                                                    }
                                                    val hasSinkBackpressure = shortWindowSinkWait >= 50.milliseconds ||
                                                        (rawWindowElapsed > Duration.ZERO && shortWindowSinkWait * 4 >= rawWindowElapsed)
                                                    val rev = claimControl.recoveryGoalRevision
                                                    val goalEnd = claimControl.recoveryGoalEndOffset
                                                    if (rev != observedGoalRevision) {
                                                        observedGoalRevision = rev
                                                        consecutiveQualifying = 0
                                                    }
                                                    val urgentActive = !claimControl.finishInFlightOnly &&
                                                        claimControl.priorityFlow.value >= PikPakStreamReader.STREAMING_PRIORITY &&
                                                        goalEnd > forwardedOffset &&
                                                        claimControl.consumedRecoveryRevision != rev
                                                    if (urgentActive) {
                                                        val candidate = client.hostHealth.bestReplacementCandidate(
                                                            currentUrl = attemptUrl,
                                                            excludedHosts = failedReroutes,
                                                            remainingBytes = goalEnd - forwardedOffset,
                                                        )
                                                        val decision = RecoveryPolicy.evaluateSameOffsetSwitchover(
                                                            forwardedOffset = forwardedOffset,
                                                            goalEndOffset = goalEnd,
                                                            goalRevision = rev,
                                                            consumedGoalRevision = claimControl.consumedRecoveryRevision,
                                                            currentUrl = attemptUrl,
                                                            currentShortWindowBytesPerSecond = shortWindowRate,
                                                            hasSinkBackpressure = hasSinkBackpressure,
                                                            candidate = candidate,
                                                            previousConsecutiveQualifying = consecutiveQualifying,
                                                            admissionQueueMillis = 0L,
                                                        )
                                                        consecutiveQualifying = decision.nextConsecutiveQualifying
                                                        if (decision.shouldSwitch &&
                                                            decision.candidate != null &&
                                                            claimControl.recoveryGoalRevision == rev &&
                                                            !claimControl.finishInFlightOnly
                                                        ) {
                                                            claimControl.consumedRecoveryRevision = rev
                                                            replacementTargetUrl = decision.candidate.url
                                                            handoff.armFor(decision.candidate.url)
                                                            break
                                                        }
                                                    } else {
                                                        consecutiveQualifying = 0
                                                    }
                                                    shortWindowMark = timeSource.markNow()
                                                    shortWindowBytes = 0L
                                                    shortWindowSinkWait = Duration.ZERO
                                                }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                bodyWatchdog.cancel()
                            }
                        }
                        } finally {
                            val fullRangeInSink = (remainingSnap != null && forwardedBytes >= remainingSnap) ||
                                (clippedAtEof && (announced == null || forwardedBytes >= announced!!)) ||
                                stoppedAtBoundary
                            if (!fullRangeInSink) {
                                claimControl?.endExchange(exchangeId)
                            }
                        }
                    }
                  }
                }
                }
            } catch (t: Throwable) {
                handoff.disarm()
                withContext(NonCancellable) {
                    client.signedUrlGates.releaseHandoff(handoff)
                }
                val claimControl = currentCoroutineContext()[FetchClaimControl]
                claimControl?.clearExchange()
                if (telemetry != null && started != null) {
                    telemetry.gateWait += attempt.gateWait
                    if (attempt.admission.granted.isCompleted) {
                        telemetry.networkDuration += (started.elapsedNow() - attempt.sinkWait - attempt.gateWait).coerceAtLeast(Duration.ZERO)
                    }
                }
                if (t is io.github.nihildigit.pikpak.internal.DemandWithdrawnException) {
                    val whole = announced != null && wireDelivered >= announced!!
                    report(
                        attempt,
                        attemptUrl,
                        if (whole) RangeAttempt.Outcome.Complete else RangeAttempt.Outcome.Cancelled,
                        if (whole) null else RangeAttempt.EndReason.Withdrawn,
                    )
                    throw t
                }
                if (t is io.github.nihildigit.pikpak.internal.TransferPreempted) {
                    telemetry?.let { it.preemptions++ }
                    report(attempt, attemptUrl, RangeAttempt.Outcome.Cancelled, RangeAttempt.EndReason.Preempted)
                    if (!currentCoroutineContext().isActive || claimControl?.finishInFlightOnly == true) throw t
                    offset += forwardedBytes
                    remaining = remaining?.minus(forwardedBytes)
                    continue
                }
                if (t is CancellationException) {
                    val whole = announced != null && wireDelivered >= announced!!
                    report(attempt, attemptUrl, if (whole) RangeAttempt.Outcome.Complete else RangeAttempt.Outcome.Cancelled)
                    throw t
                }
                if (t is SinkFailure) {
                    report(attempt, attemptUrl, RangeAttempt.Outcome.Cancelled)
                    throw t
                }
                if (claimControl?.finishInFlightOnly == true) {
                    val endReason = when {
                        t is UnresponsiveHost -> RangeAttempt.EndReason.HeaderTimeout
                        t is BodySilence -> RangeAttempt.EndReason.BodyStall
                        t is UrlExpiredException || (t is PikPakException && t.httpStatus != null) -> RangeAttempt.EndReason.HttpStatus
                        headersAnswered && announced != null && wireDelivered < announced!! -> RangeAttempt.EndReason.Truncated
                        else -> RangeAttempt.EndReason.TransportError
                    }
                    val outcome = when {
                        t is UrlExpiredException -> RangeAttempt.Outcome.Expired
                        t is PikPakException && t.isThrottlePressure -> RangeAttempt.Outcome.Throttled
                        t is PikPakException && t.httpStatus in 400..499 -> RangeAttempt.Outcome.Rejected
                        else -> RangeAttempt.Outcome.Failed
                    }
                    if (t is BodySilence) {
                        client.hostHealth.stalled(attemptUrl, attempt.delivered, attempt.bodyElapsed(t.limit))
                    } else if (t is UnresponsiveHost) {
                        client.hostHealth.markSilent(attemptUrl, t.limit)
                    }
                    report(attempt, attemptUrl, outcome, endReason)
                    throw t
                }
                offset += forwardedBytes
                remaining = remaining?.minus(forwardedBytes)
                if (forwardedBytes > 0L) emptyBodies = 0

                if (t is FileReactivatingException) {
                    report(attempt, attemptUrl, RangeAttempt.Outcome.Rejected, RangeAttempt.EndReason.HttpStatus)
                    runRetryStep(claimControl, offset) {
                        coordinator.awaitReady(observedGeneration, linkUrl) {
                            refreshUrl(UrlRequest.Expired(it), it)
                        }
                    }
                    continue
                }

                if (state.draining) return@withReplacementHandoff

                when {
                    t is RepresentationSizeMismatch -> {
                        report(attempt, attemptUrl, RangeAttempt.Outcome.Rejected, RangeAttempt.EndReason.HttpStatus)
                        runRetryStep(claimControl, offset) {
                            sizeMismatchRefreshes++
                            if (sizeMismatchRefreshes > 2) {
                                throw PikPakException(-1, t.message ?: "representation size mismatch", cause = t)
                            }
                            if (telemetry != null) telemetry.refreshed = true
                            val nextUrl = refreshUrl(UrlRequest.SizeMismatch(linkUrl, t.actual, t.expected), linkUrl)
                            if (nextUrl == linkUrl) {
                                throw PikPakException(-1, t.message ?: "representation size mismatch", cause = t)
                            }
                        }
                    }
                    t is PikPakException && t.isThrottlePressure -> {
                        report(attempt, attemptUrl, RangeAttempt.Outcome.Throttled, RangeAttempt.EndReason.HttpStatus)
                        runRetryStep(claimControl, offset) {
                            // Over the URL's connection cap. Somebody else's read
                            // will finish; this is a queue, not a failure.
                            throttles++
                            if (throttles > MAX_THROTTLE_WAITS) throw t
                            bumpThrottled()
                            delay(t.retryAfter() ?: throttleBackoff(throttles))
                        }
                    }
                    t is UrlExpiredException -> {
                        if (rerouted) {
                            routedHost?.let { failedReroutes += it }
                            client.hostHealth.refused(attemptUrl)
                            report(attempt, attemptUrl, RangeAttempt.Outcome.Rejected, RangeAttempt.EndReason.HttpStatus)
                            if (failedReroutes.size <= MAX_FREE_REROUTES) continue
                            runRetryStep(claimControl, offset) {
                                failures++
                                if (failures >= maxAttempts) throw t
                                bumpRetries()
                            }
                        } else {
                            report(attempt, attemptUrl, RangeAttempt.Outcome.Expired, RangeAttempt.EndReason.HttpStatus)
                            runRetryStep(claimControl, offset) {
                                if (telemetry != null) telemetry.refreshed = true
                                refreshUrl(UrlRequest.Expired(linkUrl), linkUrl)
                            }
                        }
                    }
                    t is PikPakException && t.httpStatus in 400..499 -> {
                        if (rerouted) {
                            routedHost?.let { failedReroutes += it }
                            client.hostHealth.refused(attemptUrl)
                            report(attempt, attemptUrl, RangeAttempt.Outcome.Rejected, RangeAttempt.EndReason.HttpStatus)
                            if (failedReroutes.size <= MAX_FREE_REROUTES) continue
                            runRetryStep(claimControl, offset) {
                                failures++
                                if (failures >= maxAttempts) throw t
                                bumpRetries()
                            }
                        } else {
                            report(attempt, attemptUrl, RangeAttempt.Outcome.Rejected, RangeAttempt.EndReason.HttpStatus)
                            runRetryStep(claimControl, offset) {
                                if (rejectionRefreshed) throw t
                                rejectionRefreshed = true
                                if (telemetry != null) telemetry.refreshed = true
                                refreshUrl(UrlRequest.Rejected(linkUrl, t.httpStatus!!), linkUrl)
                            }
                        }
                    }
                    t is UnresponsiveHost -> {
                        report(attempt, attemptUrl, RangeAttempt.Outcome.Failed, RangeAttempt.EndReason.HeaderTimeout)
                        client.hostHealth.markSilent(attemptUrl, t.limit)
                        if (rerouted) {
                            routedHost?.let { failedReroutes += it }
                            if (failedReroutes.size <= MAX_FREE_REROUTES) continue
                        }
                        runRetryStep(claimControl, offset) {
                            failures++
                            if (failures >= maxAttempts) throw PikPakException(-1, "RangeReader: no host answered at offset $offset", cause = t)
                            bumpRetries()
                            selectAlternateRoute(linkUrl, failedReroutes)
                        }
                    }
                    t is BodySilence -> {
                        report(attempt, attemptUrl, RangeAttempt.Outcome.Failed, RangeAttempt.EndReason.BodyStall)
                        client.hostHealth.stalled(attemptUrl, attempt.delivered, attempt.bodyElapsed(t.limit))
                        if (rerouted) {
                            routedHost?.let { failedReroutes += it }
                            if (failedReroutes.size <= MAX_FREE_REROUTES) continue
                        }
                        runRetryStep(claimControl, offset) {
                            bodyStalls++
                            if (forwardedBytes < MIN_VIABLE_BODY_BYTES_PER_SEC || bodyStalls >= maxAttempts) {
                                failures++
                                if (failures >= maxAttempts || bodyStalls >= maxAttempts) {
                                    throw PikPakException(-1, "RangeReader: body silent at offset $offset", cause = t)
                                }
                            }
                            bumpRetries()
                            selectAlternateRoute(linkUrl, failedReroutes)
                        }
                    }
                    else -> {
                        val truncatedAfterHeaders = headersAnswered && announced != null && wireDelivered < announced!!
                        report(
                            attempt,
                            attemptUrl,
                            RangeAttempt.Outcome.Failed,
                            if (truncatedAfterHeaders) RangeAttempt.EndReason.Truncated else RangeAttempt.EndReason.TransportError,
                        )
                        if (rerouted) {
                            routedHost?.let { failedReroutes += it }
                            if (!headersAnswered && wireDelivered == 0L) {
                                client.hostHealth.refused(attemptUrl)
                            }
                            if (failedReroutes.size <= MAX_FREE_REROUTES) continue
                        }
                        runRetryStep(claimControl, offset) {
                            if (!rerouted && headersAnswered && wireDelivered == 0L && (announced == null || announced!! > 0L)) {
                                emptyBodies++
                                if (allowEmptyBodyReactivation) {
                                    if (emptyBodies >= 2) {
                                        emptyBodies = 0
                                        coordinator.awaitReady(observedGeneration, linkUrl) {
                                            refreshUrl(UrlRequest.Expired(it), it)
                                        }
                                    } else {
                                        delay(100.milliseconds)
                                    }
                                    return@runRetryStep
                                } else if (emptyBodies >= 2) {
                                    throw PikPakException(-1, "RangeReader: empty body at offset $offset", cause = t)
                                }
                            }
                            failures++
                            if (failures >= maxAttempts) throw t
                            bumpRetries()
                            delay(client.retryPolicy.delayFor(failures - 1))
                            val canSwitch = rerouted ||
                                priority < PikPakStreamReader.READ_AHEAD_PRIORITY ||
                                !client.hostHealth.hasGoodHistory(attemptUrl) ||
                                failures >= 2
                            if (canSwitch) {
                                selectAlternateRoute(linkUrl, failedReroutes)
                            }
                        }
                    }
                }
                continue
            }

            if (telemetry != null && started != null) {
                telemetry.gateWait += attempt.gateWait
                telemetry.networkDuration += (started.elapsedNow() - attempt.sinkWait - attempt.gateWait).coerceAtLeast(Duration.ZERO)
            }

            if (replacementTargetUrl != null) {
                report(attempt, attemptUrl, RangeAttempt.Outcome.Cancelled, RangeAttempt.EndReason.OptimizedReplacement)
                offset += forwardedBytes
                remaining = remaining?.minus(forwardedBytes)
                if (forwardedBytes > 0L) emptyBodies = 0
                forcedAttemptUrl = replacementTargetUrl
                continue
            }

            client.signedUrlGates.releaseHandoff(handoff)

            if (stoppedAtBoundary) {
                val wholeWire = announced != null && wireDelivered >= announced!!
                report(
                    attempt,
                    attemptUrl,
                    if (wholeWire) RangeAttempt.Outcome.Complete else RangeAttempt.Outcome.Cancelled,
                    RangeAttempt.EndReason.BoundaryStop,
                )
                return@withReplacementHandoff
            }

            // A body that stopped short of its own Content-Length ends this
            // attempt the same way a transport error does; only the loop below
            // knows that yet, so the outcome is decided here and not in catch.
            report(attempt, attemptUrl, outcomeFor(announced, wireDelivered),
                if (announced != null && wireDelivered < announced!!) RangeAttempt.EndReason.Truncated else null)

            offset += forwardedBytes
            remaining = remaining?.minus(forwardedBytes)
            if (forwardedBytes > 0L) emptyBodies = 0

            val truncated = announced != null && wireDelivered < announced!!
            if (!truncated) {
                // An open-ended read is done when the server's own
                // Content-Length has been delivered in full. A closed range
                // that the server clipped at EOF is done too: asking for the
                // rest would be a request entirely past the end, which the
                // CDN answers with 416 and no amount of retrying changes.
                if (remaining == null || remaining <= 0L || clippedAtEof) return@withReplacementHandoff
            }
            outerClaimControl?.clearExchange()
            if (outerClaimControl?.finishInFlightOnly == true) {
                throw PikPakException(-1, "RangeReader: FinishInFlight stopped short at offset $offset")
            }
            runRetryStep(outerClaimControl, offset) {
                if (wireDelivered == 0L && rerouted) {
                    routedHost?.let { failedReroutes += it }
                    client.hostHealth.refused(attemptUrl)
                } else if (wireDelivered == 0L) {
                    if (headersAnswered && (announced == null || announced!! > 0L)) {
                        emptyBodies++
                        if (allowEmptyBodyReactivation) {
                            if (emptyBodies >= 2) {
                                emptyBodies = 0
                                coordinator.awaitReady(observedGeneration, linkUrl) {
                                    refreshUrl(UrlRequest.Expired(it), it)
                                }
                            } else {
                                delay(100.milliseconds)
                            }
                            return@runRetryStep
                        } else if (emptyBodies >= 2) {
                            throw PikPakException(-1, "RangeReader: empty body at offset $offset")
                        }
                    }
                    failures++
                    if (failures >= maxAttempts) {
                        throw PikPakException(
                            -1,
                            "RangeReader: no progress at offset $offset after $failures attempts",
                        )
                    }
                    bumpRetries()
                    delay(client.retryPolicy.delayFor(failures - 1))
                    selectAlternateRoute(linkUrl, failedReroutes)
                } else if (truncated) {
                    bumpRetries()
                    failures++
                    if (failures >= maxAttempts) throw PikPakException(-1, "truncated range at offset $offset")
                    selectAlternateRoute(linkUrl, failedReroutes)
                } else {
                    bumpRetries()
                }
            }
        }
    }

    private var initialFailure: Pair<Throwable, TimeMark>? = null

    private suspend fun currentUrl(): String = urlMutex.withLock {
        url?.let { return it }
        initialFailure?.let { (cause, at) ->
            if (at.elapsedNow() < 2.seconds) throw cause
        }
        try {
            urlProvider(UrlRequest.Initial).also { url = it; initialFailure = null }
        } catch (t: Throwable) {
            if (t !is CancellationException) initialFailure = t to timeSource.markNow()
            throw t
        }
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
        // Fatal here, where the link is what was refused. switchHost takes the same link back:
        // a host that did not answer may answer the next time.
        if (fresh == stale) {
            throw PikPakException(
                -1,
                "RangeReader: urlProvider returned the same rejected URL; it cannot make progress",
                errorDescription = stale,
            )
        }
        adopt(fresh)
    }

    /** Under [urlMutex]. */
    private fun adopt(fresh: String): String {
        url = fresh
        val now = Clock.System.now()
        _stats.update { it.copy(urlRefreshes = it.urlRefreshes + 1, lastUrlRefreshAt = now) }
        return fresh
    }

    /** When a host change last failed to get a link; guarded by [urlMutex]. */
    private var hostChangeFailedAt: TimeMark? = null

    /**
     * Selects an alternate route after a transport failure on [stale] without mutating [HostHealth].
     *
     * Health facts (`markSilent`, `stalled`, `refused`) are recorded by the caller solely on the
     * executed `attemptUrl`. If [HostHealth] already offers a usable sibling not in [excludedHosts],
     * the next iteration routes there directly without minting a fresh link.
     */
    private suspend fun selectAlternateRoute(stale: String, excludedHosts: Set<String> = emptySet()) {
        val routed = client.hostHealth.route(stale, mayExplore = false)
        val routedHost = HostHealth.hostOf(routed)
        if (routed != stale && (routedHost == null || routedHost !in excludedHosts)) {
            return
        }
        val telemetry = currentCoroutineContext()[ReadTelemetry]
        try {
            urlMutex.withLock {
                if (url != stale) return
                if (hostChangeFailedAt?.let { it.elapsedNow() < HOST_CHANGE_COOLDOWN } == true) return
                if (telemetry != null) telemetry.refreshed = true
                val fresh = try {
                    urlProvider(UrlRequest.Unresponsive(stale))
                } catch (e: Exception) {
                    if (e !is CancellationException) hostChangeFailedAt = timeSource.markNow()
                    throw e
                }
                adopt(fresh)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // Counters are bumped from every pump at once; a read-copy-write on
    // StateFlow.value would lose increments, update() retries on contention.

    /** A body that stopped short of its own Content-Length by itself ended the way a transport error does. */
    private fun outcomeFor(announced: Long?, delivered: Long): RangeAttempt.Outcome =
        if (announced != null && delivered < announced) RangeAttempt.Outcome.Failed
        else RangeAttempt.Outcome.Complete

    /**
     * Hands a finished attempt to [onAttempt], never letting it end the read.
     *
     * An observer exists to explain a transfer, so a broken one must not be
     * able to stop it: this is the one place the "don't swallow errors" rule
     * does not apply, because the caller supplied the code that threw and it is
     * not on the path the bytes take.
     */
    private suspend fun report(recorder: RangeAttemptRecorder, url: String, outcome: RangeAttempt.Outcome,
        endReason: RangeAttempt.EndReason? = null) {
        currentCoroutineContext()[ReadTelemetry]?.let { telemetry ->
            telemetry.retries += recorder.retries.serverErrors + recorder.retries.rateLimited + recorder.retries.transport
            telemetry.throttled += recorder.retries.serverErrors + recorder.retries.rateLimited
            if (outcome == RangeAttempt.Outcome.Throttled) telemetry.throttled++
            if (outcome == RangeAttempt.Outcome.Cancelled) telemetry.rightCensored = true
        }
        when {
            outcome == RangeAttempt.Outcome.Complete -> {
                client.hostHealth.delivered(recorder.host, recorder.delivered, recorder.bodyBytesPerSecond(), url)
            }
            endReason == RangeAttempt.EndReason.BoundaryStop || endReason == RangeAttempt.EndReason.OptimizedReplacement -> {
                client.hostHealth.recordBodyRate(
                    url = url,
                    bytes = recorder.delivered,
                    bodyDuration = recorder.activeBodyDuration(),
                    bytesPerSecond = recorder.bodyBytesPerSecond(),
                )
            }
        }
        client.accountState.flushDownstreamBytes()
        val observer = onAttempt ?: return
        try {
            observer(recorder.finish(outcome, endReason))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun addBytes(count: Long) {
        if (count <= 0) return
        _stats.update { it.copy(bytesRead = it.bytesRead + count) }
        client.accountState.onDownstreamBytesRead(count)
    }

    private suspend fun bumpRetries() {
        currentCoroutineContext()[ReadTelemetry]?.let { it.retries++ }
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

    internal companion object {
        internal fun tailSilenceDeadline(base: Duration, delivered: Long, announced: Long?): Duration = base

        const val READ_CHUNK = 64 * 1024

        /**
         * How long the body may go silent before the attempt is retried.
         *
         * The value callers used to put on the socket timeout, moved to where
         * it applies. It stays short on purpose: once bytes are flowing this
         * link sustains megabytes a second, so seconds of none means the
         * connection is gone and re-issuing the range costs one round trip.
         *
         * The floor, not the value: a line slow enough to lift the first-response deadline
         * lifts this with it. There a connection carries 20–40 KB/s with losses, a retransmit
         * can stall it for seconds, and re-issuing the range costs a new TLS handshake, which
         * took 1–5 s on that line.
         */
        val BODY_SILENCE = 3.seconds
        const val MIN_VIABLE_BODY_BYTES_PER_SEC = 24L * 1024
        const val EXPLORE_MIN_OFFSET_BYTES = 2L * 1024 * 1024
        const val DRAIN_SOCKET_MAX_BYTES = 128L * 1024
        val DRAIN_SOCKET_GRACE = 80.milliseconds

        /**
         * How long a failed host change holds off the next on this reader. Long enough that the
         * reads failing together on a dead network share one lookup, short enough that a host
         * change after the network returns waits seconds at most.
         */
        val HOST_CHANGE_COOLDOWN = 5.seconds

        /**
         * A 503 means the URL is at its connection cap; waiting is the whole
         * remedy. The ceiling only exists so a permanently saturated URL fails
         * instead of hanging.
         */
        const val MAX_THROTTLE_WAITS = 30
        const val MAX_FREE_REROUTES = 2
        const val THROTTLE_BASE_DELAY_MS = 200L
        const val THROTTLE_MAX_DELAY_MS = 2_000L
    }
}
