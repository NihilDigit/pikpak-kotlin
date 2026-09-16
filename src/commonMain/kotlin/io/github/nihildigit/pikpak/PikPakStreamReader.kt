package io.github.nihildigit.pikpak

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Serves one remote file to a media player as a seekable byte stream.
 *
 * This is a transport and nothing else. It has no download policy: it does not
 * decide what the file is worth keeping, it writes nothing to disk, and it
 * fetches only what the read position implies. PikPak is an online source, so
 * the player is the one that knows what it will read next, and every playback
 * fault this design replaced came from a policy layer second-guessing it.
 *
 * Bytes live in an in-memory cache of fixed [blockSize] slots keyed by their
 * offset, capped at [memoryCapBytes] and evicted least-recently-used. A cache
 * rather than a contiguous window because mpv opens a Matroska file by reading
 * the head, seeking to the tail for the Cues and seeking back: a window would
 * throw away one end each time, and a small backward seek would refetch.
 *
 * [memoryCapBytes] has to stay clear of [readAheadBytes] by more than the
 * bytes [concurrency] fetches hold at once, or read-ahead never reaches the
 * depth it was configured for. `init` requires it and `makeRoom` is where the
 * reason lives.
 *
 * [concurrency] fetches run at once, each over its own range request. One
 * fetch covers [blockSize] from a start or a seek and twice that once
 * [wideBlockThresholdBytes] ahead of the read position is in hand, so a cold
 * open spreads over all connections while a settled stream pays half the
 * per-request latency.
 *
 * The block the reader is blocked on is requested at a higher priority than
 * read-ahead, so it wins a contended connection slot both against this file's
 * own read-ahead and against every other file on the account.
 *
 * Not safe for concurrent readers — one position, one caller. [close] while a
 * read is parked is supported and is how a player aborts one; it is the one
 * call that may run concurrently with [read].
 */
class PikPakStreamReader internal constructor(
    private val source: RangeSource,
    /** Length of the remote file. `remoteSize` or `PikPakFile.size` is where this comes from. */
    val size: Long,
    private val concurrency: Int,
    parentCoroutineContext: CoroutineContext,
    private val blockSize: Long = DEFAULT_BLOCK_SIZE,
    private val readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
    private val memoryCapBytes: Long = DEFAULT_MEMORY_CAP_BYTES,
    private val wideBlockThresholdBytes: Long = DEFAULT_WIDE_BLOCK_THRESHOLD_BYTES,
) : AutoCloseable {

    /**
     * @param source where the bytes come from. Pass a [PikPakFileHandle] when
     *   the file id may move under the reader; a plain [RangeReader] adapted
     *   with [asRangeSource] is enough for a file that will not.
     * @param concurrency range requests in flight at once. Defaults to a whole
     *   per-URL connection budget, which is what a cold open needs; lower it
     *   only to leave slots for another file on the same account.
     */
    constructor(
        source: RangeSource,
        size: Long,
        concurrency: Int = PikPakClient.DEFAULT_CONNECTION_BUDGET,
        parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    ) : this(source, size, concurrency, parentCoroutineContext, DEFAULT_BLOCK_SIZE)

    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

    /**
     * The one lock over [cache], [cachedBytes], [inFlight] and the seek marks.
     *
     * A coroutine [Mutex] rather than a monitor because kotlin-stdlib has no
     * common `synchronized` and atomicfu is not a dependency here. Every
     * critical section below is non-suspending, so the blocking surface can
     * take it through [locked] without the event loop ever having to run
     * anything but the lock handover.
     */
    private val mutex = Mutex()

    /**
     * Cached slots, least recently used first. Insertion order is the LRU
     * order: a hit removes and reinserts.
     */
    private val cache = LinkedHashMap<Int, ByteArray>()
    private var cachedBytes = 0L

    /** Slot -> the fetch that will fill it. One fetch may own several slots. */
    private val inFlight = HashMap<Int, Fetch>()

    /**
     * Bumped whenever a worker or a reader could have new work to see: a
     * completed fetch, a seek, a close. Waiters park on it so they cannot miss
     * a wake-up landing between their check and their suspend.
     */
    private val revision = MutableStateFlow(0L)

    @Volatile
    private var readPosition = 0L

    @Volatile
    private var closed = false

    /** The failure that made this reader unusable, if any. Reported to every read. */
    @Volatile
    private var failure: Throwable? = null

    /**
     * Bytes the CDN has handed over, monotonic. The basis of a speed readout.
     *
     * Written only under [mutex] so the increments cannot be lost; volatile so
     * a progress display can sample it without contending for the lock.
     */
    @Volatile
    var deliveredBytes: Long = 0
        private set

    // Set by seekTo, consumed by the read that first serves bytes at the new
    // position. What a viewer feels is the interval up to the byte, not up to
    // the fetch, so the measurement ends in read and not in the worker.
    // seekTarget is volatile as well as guarded so the common case, no seek
    // outstanding, costs a read rather than a lock.
    @Volatile
    private var seekTarget = -1L
    private var seekMark: TimeMark? = null

    /** How long the last [seekTo] took to produce its first byte. Null until one has. */
    @Volatile
    var lastSeekLatency: Duration? = null
        private set

    /** Current read position, in bytes from the start of the file. */
    val position: Long get() = readPosition

    val bytesRemaining: Long get() = (size - readPosition).coerceAtLeast(0)

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, got $concurrency" }
        require(size >= 0) { "size must be >= 0, got $size" }
        // A wide fetch claims two slots, so concurrency fetches can hold twice
        // that many, and makeRoom wants one more block free on top before it
        // will let a claim through. Below this the cap alone stops read-ahead
        // short of the window and nothing in the scheduler says so.
        val headroom = (2 * concurrency + 1) * blockSize
        require(memoryCapBytes >= readAheadBytes + headroom) {
            "memoryCapBytes ($memoryCapBytes) must clear readAheadBytes ($readAheadBytes) by $headroom"
        }
        // The workers can also stop without anyone calling close: cancelling
        // the job passed in through parentCoroutineContext takes the whole
        // scope down. A reader in another coroutine would then wait on a
        // revision nobody will bump again. Marking the reader closed from here
        // covers both routes — close cancels the scope, and this is what the
        // cancellation means either way.
        scope.coroutineContext.job.invokeOnCompletion {
            closed = true
            bump()
        }
        repeat(concurrency) { scope.launch { runWorker() } }
    }

    /**
     * Moves the read position. Cheap: nothing is fetched here, the workers
     * pick the new window up on the next claim.
     */
    suspend fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        checkOpen()
        if (position == readPosition) return
        readPosition = position
        val windowEnd = (position + readAheadBytes).coerceAtMost(size)
        // Fetches the new position does not want are cancelled rather than left
        // to finish. Their partial bytes are lost, which is the point: what the
        // reader needs back is the connection, and after a seek every one of
        // them is moving bytes nobody will look at.
        val victims = locked {
            seekTarget = position
            seekMark = TimeSource.Monotonic.markNow()
            inFlight.values.distinct()
                .filter { it.endOffset <= position || it.startOffset >= windowEnd }
                .onEach { it.cancelled = true }
        }
        for (fetch in victims) fetch.job?.cancel()
        bump()
    }

    /**
     * Fills [buffer] from the current position and advances it.
     *
     * Returns the number of bytes written, which is at most one block's worth
     * however large [length] is, or -1 at end of file. Suspends until the block
     * under the read position is in hand.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        val pos = readPosition
        if (pos >= size) return -1
        if (length == 0) return 0

        val slot = slotOf(pos)
        val data = awaitSlot(slot)
        val within = (pos - slot.toLong() * blockSize).toInt()
        val available = minOf(minOf(data.size - within, length).toLong(), size - pos).toInt()
        data.copyInto(buffer, offset, within, within + available)
        readPosition = pos + available
        // Crossing into the next block is what moves the read-ahead window and
        // makes the block behind it evictable, so it is also the only moment a
        // worker parked against the memory cap could have work again. Without
        // this the stream stops the first time the cache fills.
        if (slotOf(readPosition) != slot) bump()
        if (seekTarget == pos) reportSeekLatency(pos)
        return available
    }

    /**
     * Drops the cache, cancels every fetch and fails every parked read.
     *
     * Deliberately not suspending, unlike the rest of the surface. A player
     * releases its input from a lifecycle callback that has no coroutine —
     * `onDestroy`, a `release()` — and aborting a parked read from another
     * thread is a supported way to stop playback. Both need a close that can
     * be called from anywhere.
     *
     * All three steps are non-blocking. Cancelling the scope completes every
     * in-flight [Fetch.done] through the job parent, which frees readers parked
     * in `join()`; [bump] frees the ones parked on [revision]; both then see
     * [closed] on the next turn of the loop. The cache is not cleared: doing so
     * needs the mutex the workers are still draining under, and a closed reader
     * is about to be dropped anyway.
     */
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        bump()
    }

    ///////////////////////////////////////////////////////////////////////////
    // reading
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Suspends until the slot is cached, then returns it.
     *
     * The loop re-checks rather than trusting one wake-up, because a fetch this
     * reader was waiting on can be cancelled by a seek and has to be reclaimed.
     */
    private suspend fun awaitSlot(slot: Int): ByteArray {
        while (true) {
            failure?.let { throw PikPakException(-1, "stream failed at offset $readPosition", cause = it) }
            checkOpen()
            var waiter: CompletableDeferred<Unit>? = null
            var seen = 0L
            val cached = locked {
                seen = revision.value
                waiter = inFlight[slot]?.done
                hit(slot)
            }
            if (cached != null) return cached
            val pending = waiter
            // join rather than await: a fetch cancelled by a seek completes its
            // deferred exceptionally, and that is a reason to look again, not
            // to fail the read.
            if (pending != null) pending.join() else revision.first { it != seen }
        }
    }

    private suspend fun reportSeekLatency(servedAt: Long) {
        val mark = locked<TimeMark?> {
            if (seekTarget != servedAt) return@locked null
            seekMark.also {
                seekTarget = -1
                seekMark = null
            }
        } ?: return
        lastSeekLatency = mark.elapsedNow()
    }

    ///////////////////////////////////////////////////////////////////////////
    // fetching
    ///////////////////////////////////////////////////////////////////////////

    internal inner class Fetch(val firstSlot: Int, val slotCount: Int) {
        /**
         * Parented to the reader's job so that cancelling the scope completes
         * it. Without that parent, [close] would have to walk [inFlight] and
         * complete each one by hand — under the mutex, since the workers are
         * still draining — which is what forced the whole closing surface to
         * suspend. A reader waiting here joins rather than awaits, so arriving
         * cancelled reads as "look again", and the next loop sees [closed].
         */
        val done = CompletableDeferred<Unit>(scope.coroutineContext[Job])

        @Volatile
        var job: Job? = null

        @Volatile
        var cancelled = false

        val startOffset: Long get() = firstSlot.toLong() * blockSize
        val endOffset: Long get() = (startOffset + slotCount * blockSize).coerceAtMost(size)
    }

    private suspend fun runWorker() {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value
            val fetch = mutex.withLock { claimNext() }
            if (fetch == null) {
                revision.first { it != seen }
                continue
            }
            // Its own child job so a seek can cancel this block without taking
            // the worker down with it. Started lazily and registered first: a
            // seek landing in between would otherwise find no job to cancel.
            val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(fetch) }
            val startIt = mutex.withLock {
                if (fetch.cancelled) false else { fetch.job = job; true }
            }
            if (!startIt) {
                // A seek got here first. The job was never started, but it is
                // already a child of the scope, and a lazy child that is
                // neither started nor cancelled stays attached for the life of
                // the reader — one leak per seek that lands in this window.
                job.cancel()
                abandon(fetch)
                continue
            }
            try {
                job.start()
                job.join()
            } finally {
                // Not inside runFetch: a seek landing between the registration
                // above and start() cancels the lazy job before its body ever
                // runs, so the body is not a place the cleanup can live. After
                // a fetch that did complete this is a no-op.
                abandon(fetch)
            }
        }
    }

    /**
     * Takes the first slot inside the read-ahead window that is neither cached
     * nor already being fetched, and claims it plus, when the stream has
     * settled, the slot after it.
     *
     * Must be called under [mutex].
     */
    private fun claimNext(): Fetch? {
        if (closed || failure != null) return null
        val pos = readPosition
        if (pos >= size) return null
        val windowEnd = (pos + readAheadBytes).coerceAtMost(size)
        val firstSlot = slotOf(pos)
        val lastSlot = slotOf(windowEnd - 1)

        var target = -1
        for (slot in firstSlot..lastSlot) {
            if (slot in cache || slot in inFlight) continue
            target = slot
            break
        }
        if (target == -1) return null
        if (!makeRoom()) return null

        val slotCount =
            if (wide(pos) && target < lastSlot && (target + 1) !in cache && (target + 1) !in inFlight) 2 else 1
        val fetch = Fetch(target, slotCount)
        for (slot in target until target + slotCount) inFlight[slot] = fetch
        return fetch
    }

    /**
     * Whether the stream has enough ahead of the read position to be worth
     * asking for double-sized blocks. Everything between the read position and
     * [wideBlockThresholdBytes] past it must already be cached or on its way,
     * which a start and a seek both undo, so both fall back to single slots.
     *
     * Must be called under [mutex].
     */
    private fun wide(pos: Long): Boolean {
        val end = (pos + wideBlockThresholdBytes).coerceAtMost(size)
        for (slot in slotOf(pos)..slotOf(end - 1)) {
            if (slot !in cache && slot !in inFlight) return false
        }
        return true
    }

    /**
     * Frees enough of the cache to stay under [memoryCapBytes], dropping the
     * least recently used slot that lies outside the current read-ahead window.
     *
     * Slots inside the window are exempt because evicting them is what the
     * fetch about to be claimed would have to undo: the next claimNext sees
     * the hole the eviction just made and refetches it. Narrowing the exemption
     * to a short stretch ahead of the read position would free candidates at
     * the far end of the window, but every one of them is a byte the same
     * scheduler asked for and would ask for again. Hence the invariant in
     * `init` instead: the cap has to be large enough that the window fits with
     * room to spare, and what falls outside it is the already-read history the
     * LRU order is there to age out.
     *
     * The `false` return therefore means the cap is the binding constraint and
     * nothing can be claimed until the reader advances.
     *
     * Must be called under [mutex].
     */
    private fun makeRoom(): Boolean {
        val pos = readPosition
        val windowEnd = (pos + readAheadBytes).coerceAtMost(size)
        val firstSlot = slotOf(pos)
        val lastSlot = slotOf((windowEnd - 1).coerceAtLeast(pos))
        while (cachedBytes + inFlightBytes() + blockSize > memoryCapBytes) {
            val victim = cache.keys.firstOrNull { it < firstSlot || it > lastSlot } ?: return false
            cachedBytes -= (cache.remove(victim)?.size ?: 0).toLong()
        }
        return true
    }

    private fun inFlightBytes(): Long = inFlight.size.toLong() * blockSize

    private suspend fun runFetch(fetch: Fetch) {
        val offset = fetch.startOffset
        val length = fetch.endOffset - offset
        var attempt = 0
        while (true) {
            try {
                complete(fetch, readRange(offset, length, priorityFor(offset)))
                return
            } catch (e: CancellationException) {
                // Releasing the slots and waking the waiters is the worker's
                // job, in a finally around join(); see abandon.
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt < MAX_ATTEMPTS) continue
                // The link refresh a 403 needs happens inside RangeReader,
                // below this loop; what is left here is the case where even
                // that did not help.
                mutex.withLock { releaseSlotsLocked(fetch) }
                failure = e
                fetch.done.completeExceptionally(e)
                bump()
                return
            }
        }
    }

    private suspend fun readRange(offset: Long, length: Long, priority: Int): ByteArray {
        val buffer = ByteArray(length.toInt())
        var filled = 0
        source.read(offset, length, priority) { channel ->
            while (filled < buffer.size) {
                // readAvailable hands over already-buffered bytes without
                // suspending, so without this a cancelled fetch could run to
                // the end of its block before reaching a cancellation point.
                currentCoroutineContext().ensureActive()
                val n = channel.readAvailable(buffer, filled, buffer.size - filled)
                if (n == -1) break
                filled += n
            }
        }
        if (filled != buffer.size) {
            // Not a transport hiccup: RangeReader resumes those itself. It
            // means the remote resource is shorter than the recorded size,
            // i.e. the wrong bytes.
            throw PikPakException(-1, "stream: got $filled of ${buffer.size} bytes at offset $offset")
        }
        return buffer
    }

    /** Higher for the block the reader is blocked on, so it wins a contended connection slot. */
    private fun priorityFor(offset: Long): Int {
        val pos = readPosition
        return if (offset <= pos && offset + blockSize > pos) BLOCKING_PRIORITY else READ_AHEAD_PRIORITY
    }

    private suspend fun complete(fetch: Fetch, bytes: ByteArray) {
        mutex.withLock {
            for (i in 0 until fetch.slotCount) {
                val slot = fetch.firstSlot + i
                if (inFlight[slot] === fetch) inFlight.remove(slot)
                val from = i * blockSize.toInt()
                if (from >= bytes.size) continue
                val to = minOf(bytes.size, from + blockSize.toInt())
                put(slot, bytes.copyOfRange(from, to))
            }
            deliveredBytes += bytes.size.toLong()
        }
        fetch.done.complete(Unit)
        bump()
    }

    /**
     * Gives up a claimed fetch that will never deliver bytes.
     *
     * Idempotent, and safe to run after [complete] or after the failure path:
     * the slot removal only touches slots this fetch still owns, and
     * `completeExceptionally` returns false once the deferred is settled, so a
     * second pass changes nothing. That is what lets it run unconditionally on
     * every exit path of a claimed fetch, which is the only way to cover the
     * case where the fetch body never ran at all.
     */
    private suspend fun abandon(fetch: Fetch) {
        mutex.withLock { releaseSlotsLocked(fetch) }
        val abandoned = fetch.done.completeExceptionally(
            CancellationException("fetch at ${fetch.startOffset} was abandoned"),
        )
        // Both halves matter: the slots are free for claimNext again, and a
        // reader parked on this fetch's done has to be told to look again.
        if (abandoned) bump()
    }

    /** Must be called under [mutex]. */
    private fun releaseSlotsLocked(fetch: Fetch) {
        for (i in 0 until fetch.slotCount) {
            val slot = fetch.firstSlot + i
            if (inFlight[slot] === fetch) inFlight.remove(slot)
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // cache
    ///////////////////////////////////////////////////////////////////////////

    private fun slotOf(offset: Long): Int = (offset / blockSize).toInt()

    /** Must be called under [mutex]. Reinserting is what makes the map's order an LRU order. */
    private fun hit(slot: Int): ByteArray? {
        val data = cache.remove(slot) ?: return null
        cache[slot] = data
        return data
    }

    /** Must be called under [mutex]. */
    private fun put(slot: Int, data: ByteArray) {
        val previous = cache.put(slot, data)
        cachedBytes += data.size - (previous?.size ?: 0)
    }

    /**
     * Takes [mutex] around a section that must not suspend.
     *
     * The non-suspending lambda is the point: `withLock` would accept a
     * suspending one, and a critical section that suspends here holds the lock
     * across a fetch and stalls every worker behind it.
     */
    private suspend fun <T> locked(body: () -> T): T = mutex.withLock { body() }

    private fun bump() {
        revision.update { it + 1 }
    }

    private fun checkOpen() {
        if (closed) throw PikPakException(-1, "PikPakStreamReader is closed")
    }

    /** Cached bytes held right now. For tests asserting the memory cap. */
    internal suspend fun cachedBytesForTest(): Long = locked { cachedBytes }

    /** Slots currently claimed by a fetch. A leaked claim shows up here as a count that never falls. */
    internal suspend fun inFlightSlotsForTest(): Int = locked { inFlight.size }

    /**
     * Bytes from the read position that are cached or already on their way,
     * counted contiguously and stopping at the read-ahead window.
     *
     * The measure the memory cap can silently destroy: a cache that stays
     * under its cap says nothing about whether read-ahead ever got ahead.
     */
    internal suspend fun readAheadDepthForTest(): Long = locked {
        val pos = readPosition
        val windowEnd = (pos + readAheadBytes).coerceAtMost(size)
        var covered = pos
        while (covered < windowEnd) {
            val slot = slotOf(covered)
            if (slot !in cache && slot !in inFlight) break
            covered = (slot + 1).toLong() * blockSize
        }
        covered.coerceAtMost(windowEnd) - pos
    }

    // The window a seek has to hit to cancel a fetch before its body runs is a
    // few instructions wide, so the abandonment path is driven directly rather
    // than raced for.
    internal suspend fun claimForTest(): Fetch? = locked { claimNext() }

    internal suspend fun abandonForTest(fetch: Fetch): Unit = abandon(fetch)

    // Public: every member here is a documented default or a point on the priority scale, and a
    // caller placing its own reads among these needs to name them. An internal companion made the
    // `public` on each of them mean nothing.
    companion object {
        /**
         * Cache and fetch granularity. A range request costs roughly 200 ms
         * before its first byte, which at the ~0.2 MB/s one PikPak connection
         * sustains is the time it takes to move 40 KiB, so a quarter of a
         * megabyte keeps the latency a rounding error while still splitting a
         * cold open across all eight connections.
         */
        const val DEFAULT_BLOCK_SIZE: Long = 256L * 1024

        /**
         * How far past the read position the stream is kept filled. In memory
         * only.
         *
         * Half the cap, not all of it. Equal to the cap, the window is the
         * whole cache and makeRoom has nothing outside it to evict, so
         * read-ahead stops a few blocks short and from then on only advances
         * one block per block the reader consumes — the configured depth
         * stops being the depth that runs. The other half holds what has
         * already been read, which is what a backward seek reads from.
         */
        const val DEFAULT_READ_AHEAD_BYTES: Long = 32L * 1024 * 1024

        const val DEFAULT_MEMORY_CAP_BYTES: Long = 64L * 1024 * 1024

        /**
         * Bytes ahead that must be in hand before blocks double in size.
         *
         * A quarter of the window rather than half of it: at the window's own
         * size the doubling could only start once read-ahead was complete,
         * i.e. never while it matters.
         */
        const val DEFAULT_WIDE_BLOCK_THRESHOLD_BYTES: Long = 8L * 1024 * 1024

        /** Attempts per block. RangeReader already retries transport failures and refreshes expired links. */
        const val MAX_ATTEMPTS = 3

        /**
         * What this reader asks for the block a caller is waiting on.
         *
         * Public so a caller can place its own reads on the same scale. The one
         * thing that reasonably outranks this is a fetch that has to land
         * before playback can begin at all — a container index the demuxer
         * needs before it will report a seek table, say — since until that
         * arrives there is no playback position for anything to block at.
         */
        const val BLOCKING_PRIORITY = 100

        /**
         * What this reader asks for blocks ahead of the read position.
         *
         * Anything a caller wants served ahead of its own read-ahead but behind
         * the block playback is stopped on belongs between this and
         * [BLOCKING_PRIORITY].
         */
        const val READ_AHEAD_PRIORITY = 10
    }
}
