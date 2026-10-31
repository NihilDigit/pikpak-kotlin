package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import io.github.nihildigit.pikpak.internal.RequestOrder
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.github.nihildigit.pikpak.internal.TransportMemoryBudget
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The bytes of one remote file, fetched on demand and shared by every reader of it.
 *
 * Demand comes in two shapes. A [Cursor] is a read position with a read-ahead window in front
 * of it; a [PikPakStreamReader] is one. A warm is a set of ranges somebody wants cached without
 * reading them yet. Any number of each can be open at once, and the workers serve their union.
 * An earlier design gave every reader its own cache, which left one position per cache: a
 * player that opened a second connection had to cancel the first, and bytes fetched for one
 * reader were invisible to the next, so a container index fetched while probing was fetched
 * again once playback began.
 *
 * Bytes live in fixed [blockSize] slots keyed by their offset, capped at [memoryCapBytes] and
 * evicted least-recently-used from outside every cursor's window. A cache rather than a
 * contiguous window because mpv opens a Matroska file by reading the head, seeking to the tail
 * for the Cues and seeking back: a window would throw away one end each time.
 *
 * Each fetch goes out at the priority of the demand that claimed it, and ties are broken by
 * when that demand was made (see [RequestOrder]), so of two warms the older finishes first
 * instead of both crawling. A fetch nobody wants any more — its cursor moved or closed, and no
 * warm covers it — is cancelled, because what the stream needs back is the connection.
 *
 * A [BlockStore], when given, is asked for every block before the network and offered every
 * block the network delivers.
 *
 * A warm can also be durable: a download into a [DurableBlockStore]. It skips what the store
 * already holds, waits for each block to be written rather than offering it, and completes
 * only once every block is held. Blocks only a durable warm wants are not kept in memory, so a
 * whole film going to disk does not churn the cache the streams read from. Because a download
 * is a warm like any other, a block a stream fetched is written from memory instead of being
 * fetched a second time, and a block the download wrote is read back from the store.
 *
 * A foreground cursor counts as foreground demand only while it reads, and for
 * [foregroundIdle] after. A paused player, or one whose buffer is full, would otherwise hold
 * every background file on the account at two requests for as long as it stays open.
 *
 * A block that fails [MAX_ATTEMPTS] times fails the reads and warms waiting on it and nothing
 * else. The next read of it tries again, once, before reporting the failure. The previous
 * design retired the whole reader on the first such block and took the cache down with it,
 * which is what forced callers to rebuild readers and fetch everything again.
 */
internal class BlockCache(
    private val source: RangeSource,
    val size: Long,
    private val concurrency: Int,
    parentCoroutineContext: CoroutineContext,
    /** The account's foreground count. Null for a cache built outside a client, which then only bands priorities. */
    private val foregroundStreams: ForegroundStreams?,
    private val store: BlockStore? = null,
    /** What this content is called in [store]; see [BlockStore]. */
    private val storeKey: String = "",
    // The four below are coupled by the check in init, so they are given here once and
    // overridden only by tests that need a small cache to show a schedule.
    val blockSize: Long = PikPakStreamReader.DEFAULT_BLOCK_SIZE,
    /** The deepest a cursor's read-ahead window may be. */
    val readAheadBytes: Long = PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES,
    private val memoryCapBytes: Long = PikPakStreamReader.DEFAULT_MEMORY_CAP_BYTES,
    private val wideBlockThresholdBytes: Long = PikPakStreamReader.DEFAULT_WIDE_BLOCK_THRESHOLD_BYTES,
    /** How long a foreground cursor keeps counting as foreground demand after its last read. */
    private val foregroundIdle: Duration = FOREGROUND_IDLE,
    private val scheduler: TransferScheduler? = null,
    private val downloadLimiter: BandwidthLimiter? = null,
    @Volatile private var mediaBytesPerSecond: Long? = null,
    internal val timeSource: TimeSource = TimeSource.Monotonic,
    memoryBudget: TransportMemoryBudget? = null,
) {
    private val memoryBudget = memoryBudget ?: TransportMemoryBudget(memoryCapBytes)
    private val owned = HashMap<ByteArray, Long>()
    private val durableWrites = Channel<PersistTask>(1)
    private val reclaim: () -> Unit = {
        scope.launch {
            locked {
                val protected = cursors.value.filter { it.reading }.map { slotOf(it.position) }.toSet()
                val victims = cache.keys.filter { it !in protected && it !in persisting }
                for (slot in victims) evict(slot)
                if (victims.isNotEmpty()) bump()
            }
        }
    }
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))
    private val cleanupComplete = CompletableDeferred<Unit>()

    /**
     * The one lock over [cache], [cachedBytes], [inFlight], [wanted] and [failed].
     *
     * A coroutine [Mutex] rather than a monitor because kotlin-stdlib has no common
     * `synchronized` and atomicfu is not a dependency here. Every critical section below is
     * non-suspending; see [locked].
     */
    private val mutex = Mutex()

    /** Cached slots, least recently used first. A hit removes and reinserts. */
    private val cache = LinkedHashMap<Int, ByteArray>()
    private var cachedBytes = 0L

    /** Cached slots nobody has read yet. See [wastedBytes]. */
    private val unreadSlots = HashSet<Int>()

    /** Slot -> the fetch that will fill it. One fetch may own several slots. */
    private val inFlight = FlightIndex()

    /** Range ownership is revocable and independent of resident buffer ownership. */
    private inner class FlightIndex {
        operator fun get(slot: Int): Fetch? = activeFetches.firstOrNull { !it.released && slot in it.slots }
        operator fun contains(slot: Int): Boolean = get(slot) != null
        fun isNotEmpty(): Boolean = activeFetches.any { !it.released && it.nextSlot < it.endSlot }
        val size: Int get() = activeFetches.filter { !it.released }.sumOf { (it.endSlot - it.nextSlot).coerceAtLeast(0) }
    }
    // Stable admission order breaks equal-tail ties without identity-hash randomness.
    private val activeFetches = linkedSetOf<Fetch>()
    private var pendingPersistBytes = 0L
    private var hungry = false
    private var wasAccountHungry = false
    private val registration by lazy { scheduler?.register(concurrency, blockSize, ::bump) }

    /**
     * Range-backed view of all warm claims. A whole-file download creates no per-slot
     * objects until data arrives; withdrawing one warm leaves overlapping demands intact.
     */
    private val wanted = Wanted()

    /** Warms still waiting on some of their slots, in the order they were made; see [claimNext]. */
    private val wants = mutableListOf<Want>()

    /** Durable slots whose bytes are in hand and on their way into the store. */
    private val persisting = HashMap<Int, PersistTask>()

    private class PersistTask(val slot: Int, var bytes: ByteArray?, val accounted: Boolean, val optional: StoreWrite? = null) {
        val started = MutableStateFlow(false)
        val done = CompletableDeferred<Unit>()
    }

    /** Durable slots the store has confirmed. Cleared for a slot when a new durable warm asks for it. */
    private val durableDone = SlotBits()

    /** Durable slots whose write failed, and why; fails the durable warms waiting on them only. */
    private val persistFailed = HashMap<Int, Throwable>()

    /**
     * Blocks waiting to be offered to [store], bounded: offers are optional, and an unbounded
     * queue behind a slow disk would hold fetched bytes the memory cap does not count.
     */
    private val storeWrites = store?.let { Channel<StoreWrite>(STORE_QUEUE_BLOCKS) }

    /**
     * Blocks in [storeWrites] or being written from it, by slot. The stream that fetched one may
     * have evicted it from memory already, and until its write lands the store reports it
     * missing; a download takes the bytes from here rather than fetching them again.
     */
    private val offered = HashMap<Int, ByteArray>()
    // Queued and running offers keep both ownership and a shared completion for promotion.
    private val optionalWrites = HashMap<Int, StoreWrite>()

    /**
     * Slots whose last fetch gave up, and why. Workers do not claim them again on their own:
     * a block the CDN will not serve would otherwise be retried for as long as a cursor sits
     * in front of it, and the player would wait on it forever instead of hearing why.
     */
    private val failed = HashMap<Int, Throwable>()

    private val cursors = MutableStateFlow<List<Cursor>>(emptyList())

    @Volatile
    private var foregroundWarms = 0

    /**
     * Bumped whenever a worker or a reader could have new work to see. Waiters park on it so
     * they cannot miss a wake-up landing between their check and their suspend.
     */
    private val revision = MutableStateFlow(0L)

    /**
     * Bumped whenever a read ends, for [watchForegroundIdle] alone. A read that stays inside one
     * block changes nothing the workers care about, so it does not bump [revision]; waking every
     * worker for each of a player's small reads to tell one watcher would cost more than this.
     */
    private val readsEnded = MutableStateFlow(0L)

    @Volatile
    var closed = false
        private set

    /** Whether this cache is counted in [foregroundStreams] right now; a flow for its compareAndSet. */
    private val counted = MutableStateFlow(false)

    /**
     * Bytes fetched and then evicted without ever being served. See
     * [PikPakStreamReader.wastedBytes] for why it is counted.
     */
    @Volatile
    var wastedBytes: Long = 0
        private set

    /** Bytes the CDN has handed over, monotonic. Blocks read back from a [store] are not counted. */
    @Volatile
    var deliveredBytes: Long = 0
        private set

    /**
     * Completion metadata is compact even when a warm covers hundreds of thousands of blocks.
     */
    /** One bit per completed block, instead of one object/map entry per demanded block. */
    private class SlotBits {
        private val words = HashMap<Int, Long>()
        operator fun contains(slot: Int): Boolean = (words[slot ushr 6] ?: 0L) and (1L shl (slot and 63)) != 0L
        operator fun plusAssign(slot: Int) { words[slot ushr 6] = (words[slot ushr 6] ?: 0L) or (1L shl (slot and 63)) }
        fun remove(slot: Int) {
            val key = slot ushr 6
            val word = (words[key] ?: return) and (1L shl (slot and 63)).inv()
            if (word == 0L) words.remove(key) else words[key] = word
        }
    }

    private class SlotRanges(ranges: List<IntRange>) : Iterable<Int> {
        val ranges: List<IntRange> = buildList {
            for (range in ranges) {
                var pieces = listOf(range)
                for (existing in this) pieces = pieces.flatMap { piece ->
                    if (piece.last < existing.first || piece.first > existing.last) listOf(piece)
                    else buildList {
                        if (piece.first < existing.first) add(piece.first until existing.first)
                        if (piece.last > existing.last) add(existing.last + 1..piece.last)
                    }
                }
                addAll(pieces)
            }
        }
        val size: Int = this.ranges.sumOf { it.last - it.first + 1 }
        operator fun contains(slot: Int): Boolean = ranges.any { slot in it }
        operator fun get(index: Int): Int {
            var remaining = index
            for (range in ranges) {
                val count = range.last - range.first + 1
                if (remaining < count) return range.first + remaining
                remaining -= count
            }
            error("slot index out of bounds")
        }
        override fun iterator(): Iterator<Int> = ranges.asSequence().flatMap { it.asSequence() }.iterator()
    }

    private class Want(val priority: Int, val order: Long, val durable: Boolean, val slots: SlotRanges) {
        val completed = SlotBits()
        var next = 0
    }

    private inner class Wanted {
        operator fun get(slot: Int): List<Want>? = wants.filter {
            slot in it.slots && slot !in it.completed && (!it.durable || slot !in durableDone)
        }.takeIf { it.isNotEmpty() }
        fun getValue(slot: Int): List<Want> = get(slot) ?: error("unclaimed slot")
        operator fun contains(slot: Int): Boolean = get(slot) != null
        fun isNotEmpty(): Boolean = wants.any { it.next < it.slots.size }
        fun remove(slot: Int): List<Want>? = get(slot)?.also { claims -> claims.forEach { it.completed += slot } }
    }

    private class StoreWrite(val slot: Int, val offset: Long, val bytes: ByteArray) {
        val done = CompletableDeferred<Throwable?>()
    }

    /** A read position and the window it keeps filled. Used by one caller at a time; see [PikPakStreamReader]. */
    inner class Cursor internal constructor(initialRole: StreamRole) {
        @Volatile
        var position = 0L
            internal set

        /**
         * Whether anyone has read through this cursor yet. Until then the position implies
         * nothing, so it has no window: a reader opened only to prefetch would otherwise pull
         * a whole read-ahead from offset zero that nobody asked for.
         */
        @Volatile
        internal var consuming = false

        @Volatile
        var readAhead = readAheadBytes
            internal set

        @Volatile
        var role = initialRole
            internal set

        /** See [PikPakStreamReader.urgent]. */
        @Volatile
        var urgent = false
            internal set

        internal val order = RequestOrder.Sequence.next()
        internal val rateStarted = timeSource.markNow()
        @Volatile internal var bufferedDuration: Duration = Duration.ZERO
        @Volatile internal var feedbackAt: TimeMark? = null
        @Volatile internal var rescued = false
        internal var bytesRead = 0L
        internal var hungry = false

        /** When this cursor last read or moved; see [foregroundIdle]. */
        @Volatile
        internal var lastActive: TimeMark = timeSource.markNow()

        /** A read is parked on this cursor: it is waiting, not idle, however long that takes. */
        @Volatile
        internal var reading = false

        @Volatile
        internal var seeking = true

        @Volatile
        internal var headClaimedAt: TimeMark? = null

        @Volatile
        internal var closed = false

        /**
         * Completed by [closeCursor]. A read parked on a fetch another demand still wants is
         * not woken by that fetch's cancellation, because there is none; it waits on this too.
         */
        internal val closing = CompletableDeferred<Unit>()
    }

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, got $concurrency" }
        require(size >= 0) { "size must be >= 0, got $size" }
        require(blockSize in 1..Int.MAX_VALUE.toLong())
        require(size == 0L || (size - 1) / blockSize < Int.MAX_VALUE)
        require(mediaBytesPerSecond == null || mediaBytesPerSecond!! > 0)
        // Preserve the constructor's existing local window/headroom contract. HTTP range
        // length is independent: allocations acquire shared credits one block at a time.
        val headroom = (2L * concurrency + 1) * blockSize
        require(memoryCapBytes >= readAheadBytes + headroom) {
            "memoryCapBytes ($memoryCapBytes) must clear readAheadBytes ($readAheadBytes) by $headroom"
        }
        // Cancelling the job passed in through parentCoroutineContext takes the whole scope
        // down without anyone calling close, and a reader in another coroutine would then wait
        // on a revision nobody will bump again.
        scope.coroutineContext.job.invokeOnCompletion {
            closed = true
            registration?.let { scheduler?.unregister(it) }
            syncForegroundCount()
            bump()
        }
        this.memoryBudget.register(reclaim)
        scope.coroutineContext.job.invokeOnCompletion {
            this.memoryBudget.unregister(reclaim)
            CoroutineScope(parentCoroutineContext + NonCancellable).launch {
                // Also covers cancellation before the optional writer ever starts.
                storeWrites?.cancel()
                locked {
                    cache.clear()
                    offered.clear()
                    optionalWrites.clear()
                    persisting.clear()
                    activeFetches.clear()
                    cachedBytes = 0
                    pendingPersistBytes = 0
                    bump()
                }
                cleanupComplete.complete(Unit)
            }
        }
        scope.launch {
            try {
                for (task in durableWrites) persist(durableStore(), task)
            } finally {
                withContext(NonCancellable) {
                    durableWrites.close()
                    while (true) {
                        val task = durableWrites.tryReceive().getOrNull() ?: break
                        persist(durableStore(), task)
                    }
                }
            }
        }
        repeat(concurrency) { index -> scope.launch { runWorker(index) } }
        if (scheduler != null) scope.launch {
            var probe: Job? = null
            while (isActive) {
                val active = locked {
                    val live = liveCursors()
                    val busy = inFlight.isNotEmpty() || live.isNotEmpty() || wanted.isNotEmpty()
                    if (busy) updateSchedule(live)
                    busy
                }
                if (active) {
                    val reg = registration!!
                    if (probe?.isActive != true && scheduler.tryAcquireProbe(reg)) {
                        probe = launch {
                            try { delay(3.seconds) }
                            finally { withContext(NonCancellable) { scheduler.releaseProbe(reg); bump() } }
                        }
                    }
                    bump()
                }
                delay(250)
            }
        }
        if (foregroundStreams != null) scope.launch { watchForegroundIdle() }
        if (store != null && storeWrites != null) {
            scope.launch {
                try {
                    for (write in storeWrites) {
                        var failure: Throwable? = null
                        try {
                            store.write(storeKey, write.offset, write.bytes)
                        } catch (e: CancellationException) {
                            failure = e
                            throw e
                        } catch (e: Throwable) {
                            failure = e
                        } finally {
                            withContext(NonCancellable) {
                                locked {
                                    if (optionalWrites[write.slot] === write) optionalWrites.remove(write.slot)
                                    if (failure == null) durableDone += write.slot
                                    forgetOffer(write.slot, write.bytes)
                                    write.done.complete(failure)
                                    bump()
                                }
                            }
                        }
                    }
                } finally {
                    // The closed cache remains reachable by callers. Drop queued array
                    // references before scope completion releases their ownership credits.
                    storeWrites.cancel()
                    withContext(NonCancellable) {
                        locked {
                            for (write in optionalWrites.values) write.done.complete(CancellationException("cache closed"))
                            optionalWrites.clear()
                            for (slot in offered.keys.filter { it !in persisting }) offered.remove(slot)
                            bump()
                        }
                    }
                }
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // demand
    ///////////////////////////////////////////////////////////////////////////

    fun openCursor(role: StreamRole): Cursor {
        checkOpen()
        val cursor = Cursor(role)
        cursors.update { it + cursor }
        syncForegroundCount()
        bump()
        return cursor
    }

    /** Not suspending, like [PikPakStreamReader.close]; the fetches it orphans are cancelled from the scope. */
    fun closeCursor(cursor: Cursor) {
        if (cursor.closed) return
        cursor.closed = true
        cursor.closing.complete(Unit)
        cursors.update { it - cursor }
        syncForegroundCount()
        if (!closed) scope.launch { cancelUnwanted() }
        bump()
    }

    fun setRole(cursor: Cursor, role: StreamRole) {
        if (cursor.role == role) return
        cursor.role = role
        syncForegroundCount()
        bump()
    }

    fun updateMediaBytesPerSecond(bytes: Long?) {
        require(bytes == null || bytes > 0)
        mediaBytesPerSecond = bytes
        bump()
    }

    fun reportPlaybackBuffer(cursor: Cursor, bufferedDuration: Duration, mediaBytesPerSecond: Long? = null) {
        require(bufferedDuration >= Duration.ZERO)
        cursor.bufferedDuration = bufferedDuration
        cursor.feedbackAt = timeSource.markNow()
        if (mediaBytesPerSecond != null) updateMediaBytesPerSecond(mediaBytesPerSecond)
        bump()
    }

    fun setUrgent(cursor: Cursor, urgent: Boolean) {
        if (cursor.urgent == urgent) return
        cursor.urgent = urgent
        bump()
    }

    fun setReadAhead(cursor: Cursor, bytes: Long) {
        val previous = cursor.readAhead
        cursor.readAhead = bytes.coerceIn(0, readAheadBytes)
        if (cursor.readAhead < previous && !closed) scope.launch { cancelUnwanted(); syncForegroundCount() }
        bump()
    }

    /** Cheap: nothing is fetched here, the workers pick the new window up on the next claim. */
    suspend fun seek(cursor: Cursor, position: Long) {
        checkOpen(cursor)
        touch(cursor)
        cursor.position = position
        cursor.seeking = true
        cursor.rescued = false
        cursor.headClaimedAt = null
        cancelUnwanted()
        bump()
    }

    /**
     * Fills [buffer] from the cursor's position and advances it. At most one block's worth
     * however large [length] is, or -1 at end of file.
     */
    suspend fun read(cursor: Cursor, buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen(cursor)
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        val pos = cursor.position
        if (pos >= size) return -1
        if (length == 0) return 0
        if (!cursor.consuming) {
            cursor.consuming = true
            bump()
        }
        val slot = slotOf(pos)
        cursor.reading = true
        touch(cursor)
        syncForegroundCount()
        if (cursor.readAhead == 0L) bump()
        val rescue = scope.launch {
            while (isActive && !cursor.rescued) {
                val seen = revision.value
                val feedback = cursor.feedbackAt
                val remaining = feedback?.let { cursor.bufferedDuration - it.elapsedNow() }
                val victim = locked {
                    val fetch = inFlight[slot]
                    val within = (pos - slot.toLong() * blockSize).toInt()
                    val urgentPromotion = cursor.role == StreamRole.FOREGROUND && cursor.urgent &&
                        fetch != null && fetch.priority < PikPakStreamReader.BLOCKING_PRIORITY
                    // The deadline belongs to this head demand, not the fetch's start time.
                    // rescued survives replacement and subsequent reads within this slot.
                    val expired = remaining != null && remaining <= Duration.ZERO
                    if (!cursor.reading || fetch == null || fetch.nextSlot != slot ||
                        fetch.buffered > within || (!urgentPromotion && !expired)) null
                    else {
                        cursor.rescued = true
                        fetch.cancelled = true
                        fetch
                    }
                }
                // Replacement admission happens only after the source unwinds its transport.
                if (victim != null) {
                    withContext(NonCancellable) { victim.job?.cancelAndJoin(); bump() }
                    break
                }
                if (remaining != null && remaining > Duration.ZERO) {
                    withTimeoutOrNull(remaining) { revision.first { it != seen } }
                } else revision.first { it != seen }
            }
        }
        val available = try {
            awaitSlot(cursor, slot, (pos - slot.toLong() * blockSize).toInt(), buffer, offset, length)
        } finally {
            rescue.cancel()
            cursor.reading = false
            cursor.lastActive = timeSource.markNow()
            readsEnded.update { it + 1 }
            syncForegroundCount()
            if (cursor.readAhead == 0L) bump()
        }
        if (cursor.seeking) {
            cursor.seeking = false
            bump()
        }
        cursor.position = pos + available
        cursor.bytesRead += available
        // Crossing into the next block moves the window and makes the block behind it
        // evictable, so it is the only moment a worker parked against the memory cap could
        // have work again. Without this the stream stops the first time the cache fills.
        if (slotOf(cursor.position) != slot) {
            cursor.rescued = false
            bump()
        }
        return available
    }

    /**
     * Fetches [ranges] into the cache without a read position. The job completes once every
     * block has been cached, fails with the first block that could not be, and is cancelled
     * by [close].
     *
     * Cancelling it withdraws the request, and the fetches nobody else wants go with it. A feed
     * warms the clips ahead and gives up on the ones the user has scrolled past; left standing,
     * those would be the oldest demand on the account and win every tie against the clip now
     * on screen.
     *
     * [durable] makes it a download into the [DurableBlockStore]: what the store holds is
     * skipped, and the job completes once every other block has been written there. Blocks are
     * fetched in the order [ranges] gives them.
     */
    fun warm(ranges: List<LongRange>, role: StreamRole, priority: Int?, durable: Boolean = false): Deferred<Unit> = scope.async {
        val store = if (durable) durableStore() else null
        val clamped = ranges.mapNotNull { range ->
            val first = range.first.coerceAtLeast(0)
            val last = range.last.coerceAtMost(size - 1)
            if (first > last) null else first..last
        }
        val requested = store?.missing(storeKey, clamped) ?: clamped
        val slots = SlotRanges(requested.map { slotOf(it.first)..slotOf(it.last) })
        val want = Want(priority ?: warmPriorityFor(role), RequestOrder.Sequence.next(), durable, slots)
        // A download is never what someone is watching, whatever band it asks at
        val foreground = role == StreamRole.FOREGROUND && !durable
        // Durable slots already in memory, or still queued for the store, are written from there;
        // fetching them again would spend a request on bytes a stream brought in a moment ago
        val fromMemory = mutableListOf<PersistTask>()
        val fromOffers = mutableListOf<Int>()
        locked {
            if (durable) for (slot in slots) durableDone.remove(slot)
            for (slot in failed.keys.filter { it in slots }) failed.remove(slot)
            for (slot in persistFailed.keys.filter { it in slots }) persistFailed.remove(slot)
            wants += want
            for ((slot, cached) in cache) if (slot in slots) {
                want.completed += slot
                if (durable && slot !in persisting) {
                    val task = PersistTask(slot, cached, accounted = false, optional = optionalWrites[slot])
                    persisting[slot] = task
                    fromMemory += task
                }
            }
            if (durable) fromOffers += offered.keys.filter { it in slots && it !in cache && it !in persisting }
            if (foreground) foregroundWarms++
        }
        syncForegroundCount()
        bump()
        var warmed = false
        try {
            for (task in fromMemory) persist(store!!, task)
            for (slot in fromOffers) {
                val task = admitOffer(slot) ?: continue
                // Admission owns the bytes even if this warm is cancelled immediately after it.
                withContext(NonCancellable) { persist(store!!, task) }
            }
            for (slot in slots) if (durable) awaitWarmed(slot, durable = true)
            for (slot in want.slots) awaitWarmed(slot, durable)
            warmed = true
        } finally {
            withContext(NonCancellable) {
                if (!warmed) {
                    withdraw(want)
                    // Those not written yet are no longer anyone's to write
                    for (task in fromMemory) if (!task.done.isCompleted) persist(durableStore(), task)
                }
                locked {
                    wants.remove(want)
                    if (foreground) foregroundWarms--
                }
            }
            syncForegroundCount()
            bump()
        }
    }

    private suspend fun withdraw(want: Want) {
        locked { wants.remove(want) }
        cancelUnwanted()
    }

    private fun durableStore(): DurableBlockStore =
        store as? DurableBlockStore
            ?: throw IllegalStateException("a download needs a DurableBlockStore; this cache has ${store ?: "none"}")

    /**
     * Writes one durable slot and records the outcome. A failure fails the durable warms
     * waiting on the slot and nothing else: the readers already have the bytes.
     */
    private suspend fun persist(store: DurableBlockStore, task: PersistTask) {
        if (!task.started.compareAndSet(false, true)) {
            withContext(NonCancellable) { task.done.await() }
            return
        }
        val bytes = task.bytes ?: return
        var failure: Throwable? = null
        try {
            // Independent of a cancelled caller/fetch, but still cancelled by cache.close().
            if (task.optional != null) {
                failure = withContext(NonCancellable) { task.optional.done.await() }
            } else if (!locked { task.slot in durableDone }) {
                val job = scope.async { store.write(storeKey, task.slot.toLong() * blockSize, bytes) }
                withContext(NonCancellable) { job.await() }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            withContext(NonCancellable) {
                locked {
                    if (persisting[task.slot] === task) {
                        persisting.remove(task.slot)
                        if (task.accounted) pendingPersistBytes -= bytes.size
                        forgetOffer(task.slot, bytes)
                        if (failure == null) durableDone += task.slot else persistFailed[task.slot] = failure
                    }
                    task.bytes = null
                    task.done.complete(Unit)
                    bump()
                }
            }
        }
    }

    /** Promoting optional queued bytes to a durable obligation must first fit the hard cap. */
    private suspend fun admitOffer(slot: Int): PersistTask? {
        while (true) {
            var seen = 0L
            var waiting = false
            val task = locked {
                seen = revision.value
                checkOpen()
                if (slot in persisting) return@locked null
                val bytes = offered[slot] ?: return@locked null
                val accounted = slot !in cache
                if (accounted) {
                    val windows = liveCursors().map(::windowOf)
                    val victims = cache.keys.filter { it !in persisting && windows.none { window -> it in window } }
                    val free = memoryCapBytes - cachedBytes - inFlightBytes() - pendingPersistBytes
                    if (free + victims.sumOf { cache.getValue(it).size.toLong() } < bytes.size) {
                        waiting = true
                        return@locked null
                    }
                    for (victim in victims) {
                        if (cachedBytes + inFlightBytes() + pendingPersistBytes + bytes.size <= memoryCapBytes) break
                        val removed = cache.remove(victim)!!.size.toLong()
                        cachedBytes -= removed
                        if (unreadSlots.remove(victim)) wastedBytes += removed
                    }
                    pendingPersistBytes += bytes.size
                }
                PersistTask(slot, bytes, accounted, optionalWrites[slot]).also {
                    persisting[slot] = it
                    wanted.remove(slot)
                    bump()
                }
            }
            if (!waiting) return task
            revision.first { it != seen }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        registration?.let { scheduler?.unregister(it) }
        syncForegroundCount()
        scope.cancel()
        bump()
    }

    private fun warmPriorityFor(role: StreamRole): Int = when (role) {
        StreamRole.FOREGROUND -> PikPakStreamReader.WARM_PRIORITY
        StreamRole.BACKGROUND -> PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
    }

    private fun outranks(a: Want, b: Want): Boolean =
        a.priority > b.priority || (a.priority == b.priority && a.order < b.order)

    /** The request that decides when a slot several warms want is fetched. */
    private fun strongest(wants: List<Want>): Want = wants.reduce { best, next -> if (outranks(next, best)) next else best }

    private fun hasForegroundDemand(): Boolean =
        foregroundWarms > 0 || cursors.value.any { it.role == StreamRole.FOREGROUND && isActive(it) }

    private fun isActive(cursor: Cursor): Boolean =
        !cursor.closed && (cursor.reading || (cursor.readAhead > 0L && cursor.lastActive.elapsedNow() < foregroundIdle))

    /** Marks [cursor] busy, and counts it as foreground again if it had gone idle. */
    private fun touch(cursor: Cursor) {
        val wasActive = isActive(cursor)
        cursor.lastActive = timeSource.markNow()
        if (!wasActive && cursor.role == StreamRole.FOREGROUND) {
            syncForegroundCount()
            bump()
        }
    }

    /**
     * Takes a foreground cursor out of the account's count once it has been idle for
     * [foregroundIdle]. Nothing else would: going idle is the absence of an event.
     */
    private suspend fun watchForegroundIdle() {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value to readsEnded.value
            val nextIdle = cursors.value
                .filter { it.role == StreamRole.FOREGROUND && !it.closed && !it.reading }
                .map { foregroundIdle - it.lastActive.elapsedNow() }
                .filter { it.isPositive() }
                .minOrNull()
            if (nextIdle == null) {
                syncForegroundCount()
                combine(revision, readsEnded) { r, e -> r to e }.first { it != seen }
                continue
            }
            delay(nextIdle)
            syncForegroundCount()
            bump()
        }
    }

    /**
     * Brings [counted] in line with the demand, and keeps at it until the two agree. The
     * demand is changed from several threads without a common lock, so one pass could apply
     * a decision made before another thread's change and leave the account's count wrong for
     * as long as nothing else here moves. Whoever changes the demand calls this afterwards,
     * so the last caller always sees the final state.
     */
    private fun syncForegroundCount() {
        while (true) {
            val want = !closed && hasForegroundDemand()
            if (counted.value == want) return
            if (counted.compareAndSet(!want, want)) {
                if (want) foregroundStreams?.enter() else foregroundStreams?.leave()
            }
        }
    }

    /** Nothing here the user is watching, while the account has something that is. */
    private fun throttled(): Boolean =
        !hasForegroundDemand() && (foregroundStreams?.active?.value ?: 0) > 0

    /**
     * Whether anything on the account is being watched, this file included. A download then
     * keeps at most [PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND] requests in flight,
     * each a single block, even on the file being played: priority cannot take back a slot, and
     * eight download requests on the file would leave its own stream queued behind them.
     */
    private fun downloadsThrottled(): Boolean =
        hasForegroundDemand() || (foregroundStreams?.active?.value ?: 0) > 0

    private fun activeWorkers(): Int = registration?.let { scheduler!!.workersFor(it, false) }
        ?: if (throttled()) minOf(concurrency, PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND) else concurrency

    /** Only bytes actually available to the player count as runway. Called under mutex. */
    private fun readyAheadBytes(cursor: Cursor): Long {
        var end = cursor.position
        while (end < size) {
            val bytes = cache[slotOf(end)] ?: break
            val next = slotOf(end).toLong() * blockSize + bytes.size
            if (next <= end) break
            end = next.coerceAtMost(size)
        }
        return end - cursor.position
    }

    private fun updateSchedule(live: List<Cursor>) {
        val foreground = live.filter { it.role == StreamRole.FOREGROUND && isActive(it) }
        val wasHungry = hungry
        for (cursor in foreground) {
            val seconds = cursor.rateStarted.elapsedNow().inWholeMilliseconds / 1000.0
            val measured = if (cursor.bytesRead > 0 && seconds > 0) (cursor.bytesRead / seconds).toLong().coerceAtLeast(1) else null
            val rate = (mediaBytesPerSecond ?: measured ?: (1024L * 1024)).coerceAtLeast(1)
            val threshold = minOf(rate * (if (cursor.hungry) 6 else 4),
                cursor.readAhead * (if (cursor.hungry) 3 else 2) / 4, size - cursor.position)
            cursor.hungry = cursor.urgent || (cursor.reading && slotOf(cursor.position) !in cache) || readyAheadBytes(cursor) < threshold
        }
        hungry = foregroundWarms > 0 || foreground.any { it.hungry }
        registration?.let { scheduler!!.update(it, hasForegroundDemand(), hungry,
            activeFetches.any { f -> f.durable } || wants.any { want -> want.durable && nextClaimable(want) != null } ||
                live.any { cursor -> cursor.role == StreamRole.BACKGROUND && windowOf(cursor).any { slot ->
                    slot !in cache && slot !in inFlight && slot !in failed && slot !in persisting
                } }) }
        val accountHungry = scheduler?.isAccountHungry() ?: hungry
        if (scheduler != null && accountHungry) {
            for (fetch in activeFetches) if (fetch.durable || !hasForegroundDemand()) {
                if (fetch.waitingForLimiter) { fetch.cancelled = true; fetch.job?.cancel() }
                if (!wasAccountHungry && fetch.endSlot > fetch.nextSlot + 1) {
                    fetch.endSlot = fetch.nextSlot + 1
                }
            }
        }
        wasAccountHungry = accountHungry
    }

    ///////////////////////////////////////////////////////////////////////////
    // waiting
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Suspends until the slot is cached, then returns it.
     *
     * The loop re-checks rather than trusting one wake-up, because a fetch this read was
     * waiting on can be cancelled when another cursor moves and has to be reclaimed. A failed
     * slot is cleared and fetched once more before its failure is reported: it may have failed
     * as read-ahead a while ago, on a host that has since been replaced.
     */
    private suspend fun awaitSlot(cursor: Cursor, slot: Int, within: Int,
                                  destination: ByteArray, offset: Int, length: Int): Int {
        var retried = false
        while (true) {
            checkOpen(cursor)
            var seen = 0L
            var failure: Throwable? = null
            var cleared = false
            val cached = locked {
                seen = revision.value
                checkOpen(cursor)
                updateSchedule(liveCursors())
                inFlight[slot]?.takeIf { slot > it.nextSlot }?.let {
                    // Hand the untouched tail to a head worker without duplicating its prefix.
                    it.endSlot = slot
                    bump()
                }
                inFlight[slot]?.takeIf { it.waitingForLimiter && it.durable }?.let {
                    it.cancelled = true
                    it.job?.cancel()
                }
                val slotFailure = failed[slot]
                if (slotFailure != null) {
                    if (retried) {
                        failure = slotFailure
                    } else {
                        failed.remove(slot)
                        retried = true
                        cleared = true
                    }
                }
                val complete = hit(slot) ?: persisting[slot]?.bytes ?: offered[slot]
                val fetch = inFlight[slot]?.takeIf { it.nextSlot == slot }
                val bytes = complete ?: fetch?.buffer
                val end = complete?.size ?: fetch?.buffered ?: 0
                if (bytes != null && end > within) {
                    val count = minOf(end - within, length)
                    // The published prefix is append-only. Copy under the ownership lock, so
                    // no snapshot allocation or escaped cache reference needs extra credits.
                    bytes.copyInto(destination, offset, within, within + count)
                    if (complete == null) fetch?.consumed = true
                    count
                } else null
            }
            if (cached != null) return cached
            failure?.let { throw PikPakException(-1, "stream failed at offset ${slot.toLong() * blockSize}", cause = it) }
            // Once, for the workers to see the slot claimable again. On every pass it would
            // spin: first() below returns at once for a revision this loop just moved.
            if (cleared) bump()
            revision.first { it != seen }
        }
    }

    private suspend fun awaitWarmed(slot: Int, durable: Boolean) {
        while (true) {
            checkOpen()
            var seen = 0L
            var failure: Throwable? = null
            var lost = false
            val pending = locked {
                seen = revision.value
                failure = failed[slot] ?: if (durable) persistFailed[slot] else null
                if (!durable) {
                    slot !in cache && slot in wanted
                } else {
                    // Every way a durable slot leaves `wanted` goes through persisting or failed;
                    // one found in none of them would otherwise be waited on forever
                    lost = failure == null && slot !in durableDone && slot !in wanted && slot !in persisting
                    slot !in durableDone
                }
            }
            failure?.let {
                val what = if (durable) "download" else "prefetch"
                throw PikPakException(-1, "$what failed at offset ${slot.toLong() * blockSize}", cause = it)
            }
            if (lost) throw PikPakException(-1, "download lost track of offset ${slot.toLong() * blockSize}")
            // Neither cached nor wanted: fetched and then evicted, which still counts as warmed
            if (!pending) return
            revision.first { it != seen }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // fetching
    ///////////////////////////////////////////////////////////////////////////

    internal inner class Fetch(
        val firstSlot: Int,
        slotCount: Int,
        val priority: Int,
        val order: Long,
        /** Claimed for a download; see [downloadsThrottled]. */
        val durable: Boolean = false,
    ) {
        /**
         * Parented to the scope's job so that cancelling the scope completes it; a reader
         * waiting here joins rather than awaits, so arriving cancelled reads as "look again".
         */
        val done = CompletableDeferred<Unit>(scope.coroutineContext[Job])

        @Volatile
        var job: Job? = null

        @Volatile
        var cancelled = false

        val startedAt = timeSource.markNow()
        var released = false
        @Volatile var nextSlot = firstSlot
        @Volatile var endSlot = firstSlot + slotCount
        @Volatile var waitingForLimiter = false
        var buffer: ByteArray? = null
        var buffered = 0
        var consumed = false
        var reserved = 0L
        var transportReserved = 0L
        val slotCount: Int get() = endSlot - firstSlot
        val startOffset: Long get() = firstSlot.toLong() * blockSize
        val endOffset: Long get() = (endSlot.toLong() * blockSize).coerceAtMost(size)
        val slots: IntRange get() = nextSlot until endSlot
    }

    private suspend fun runWorker(index: Int) {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value
            if (index >= activeWorkers()) {
                // Parked above the background cap. It lifts when this file gains foreground
                // demand, which bumps, or when the account's last foreground file leaves,
                // which only the count says.
                if (scheduler != null) revision.first { it != seen }
                else {
                    val foreground = foregroundStreams?.active ?: flowOf(0)
                    combine(revision, foreground) { r, count -> r != seen || count == 0 }.first { it }
                }
                continue
            }
            val fetch = mutex.withLock { claimNext() }
            if (fetch == null) {
                revision.first { it != seen }
                continue
            }
            // Its own child job so losing its demand can cancel this block without taking the
            // worker down with it. Started lazily and registered first: a cancellation landing
            // in between would otherwise find no job to cancel.
            val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(fetch) }
            val startIt = mutex.withLock {
                if (fetch.cancelled) false else { fetch.job = job; true }
            }
            if (!startIt) {
                // A lazy child that is neither started nor cancelled stays attached for the
                // life of the scope — one leak per cancellation that lands in this window.
                job.cancel()
                abandon(fetch)
                continue
            }
            try {
                job.start()
                job.join()
            } finally {
                // Not inside runFetch: a cancellation landing between the registration above
                // and start() cancels the lazy job before its body ever runs.
                withContext(NonCancellable) {
                    // Fetches are scope siblings, not worker children. Cancellation of
                    // join() does not wait for their transport cleanup to finish.
                    job.cancelAndJoin()
                    abandon(fetch)
                }
            }
        }
    }

    /**
     * Picks the most urgent slot nobody is fetching: for each cursor the first missing slot
     * of its window, for each warm every missing slot, ranked by priority and then by the age
     * of the demand. A cursor's pick is widened to two slots once its stream has settled.
     *
     * Must be called under [mutex].
     */
    private fun claimNext(enforceWorkerLimit: Boolean = true): Fetch? {
        if (closed) return null
        val live = liveCursors()
        updateSchedule(live)
        if (enforceWorkerLimit && activeFetches.size >= activeWorkers()) return null
        var target = -1
        var targetPriority = Int.MIN_VALUE
        var targetOrder = Long.MAX_VALUE
        var targetCursor: Cursor? = null
        fun consider(slot: Int, priority: Int, order: Long, cursor: Cursor?): Boolean {
            if (priority > targetPriority || (priority == targetPriority && order < targetOrder)) {
                target = slot
                targetPriority = priority
                targetOrder = order
                targetCursor = cursor
                return true
            }
            return false
        }
        for (cursor in live) {
            val headSlot = slotOf(cursor.position)
            val headStagger = cursor.headClaimedAt?.let { it.elapsedNow() < 30.milliseconds } ?: true
            val headPending = scheduler != null && cursor.seeking && headSlot !in cache && (headSlot !in inFlight || headStagger)
            for (slot in windowOf(cursor)) {
                if (headPending && slot != headSlot) break
                val existing = inFlight[slot]
                if (existing?.waitingForLimiter == true && existing.durable) {
                    existing.cancelled = true
                    existing.job?.cancel()
                }
                if (slot in cache || slot in offered || slot in inFlight || slot in failed || slot in persisting) continue
                consider(slot, priorityFor(cursor, slot), cursor.order, cursor)
                break
            }
        }
        var targetWant: Want? = null
        val durableLimit = registration?.let { scheduler!!.workersFor(it, true) }
            ?: if (downloadsThrottled()) PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND else concurrency
        val localLimit = if (scheduler != null && cursors.value.any { !it.closed && it.role == StreamRole.FOREGROUND }) maxOf(2, concurrency - 2) else concurrency
        val downloadsHeld = activeFetches.count { it.durable } >= minOf(durableLimit, localLimit)
        for (want in wants) {
            if (want.durable && downloadsHeld) continue
            val slot = nextClaimable(want) ?: continue
            // A slot several warms want goes at the strongest of them
            val strongest = strongest(wanted.getValue(slot))
            if (consider(slot, strongest.priority, strongest.order, null)) targetWant = want
        }
        // Only idle capacity rescues owned work; unclaimed demand always wins. Keep the
        // current slot with its reader even if it is suspended before allocating a buffer.
        // The ownership transfer below commits under the same lock as memory admission.
        var tailDonor: Fetch? = null
        if (target == -1 && !downloadsHeld) {
            val donor = activeFetches.filter {
                it.durable && !it.released && !it.cancelled && it.endSlot - it.nextSlot > 1
            }.sortedByDescending { it.endSlot - it.nextSlot }.firstOrNull { fetch ->
                val split = fetch.endSlot - (fetch.endSlot - fetch.nextSlot) / 2
                wanted[split]?.any { it.durable } == true
            }
            if (donor != null) {
                target = donor.endSlot - (donor.endSlot - donor.nextSlot) / 2
                targetWant = strongest(wanted.getValue(target).filter { it.durable })
                targetPriority = targetWant.priority
                targetOrder = targetWant.order
                tailDonor = donor
            }
        }
        if (target == -1) return null
        val owner = targetCursor
        val blocking = owner != null && target == slotOf(owner.position)
        val downloadWant = targetWant?.takeIf { owner == null && it.durable }
        val streamWarm = targetWant?.takeIf { owner == null && !it.durable }
        val mayWiden = if (scheduler != null) {
            (owner != null && !blocking && (!owner.seeking || wide(owner))) ||
                (downloadWant != null && !wasAccountHungry) ||
                (streamWarm != null && !wasAccountHungry)
        } else {
            !throttled() && ((owner != null && wide(owner)) ||
                (downloadWant != null && !downloadsThrottled()) ||
                streamWarm != null)
        }
        val spanWorkers = (if (downloadWant != null) minOf(durableLimit, localLimit) else activeWorkers()).coerceAtLeast(1)
        val fairShare = when {
            downloadWant != null -> {
                val remaining = (downloadWant.slots.size - downloadWant.next).coerceAtLeast(1).toLong()
                ((remaining + spanWorkers - 1) / spanWorkers).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            owner != null -> {
                val win = windowOf(owner)
                val winCount = (if (win.isEmpty()) 1 else (win.last - target + 1).coerceAtLeast(1)).toLong()
                ((winCount + spanWorkers - 1) / spanWorkers).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            else -> 2
        }
        val maxSpan = when {
            tailDonor != null -> tailDonor.endSlot - target
            !mayWiden -> 1
            streamWarm != null -> 2
            else -> minOf(fairShare, registration?.let { scheduler!!.spanFor(it, spanWorkers, downloadWant != null) } ?: 2)
        }
        val windows = live.map(::windowOf)
        val outside = cache.keys.filter { slot -> slot !in persisting && windows.none { slot in it } }
        val baseFree = memoryCapBytes - cachedBytes - inFlightBytes() - pendingPersistBytes
        val outsideBytes = outside.sumOf { cache.getValue(it).size.toLong() }
        var slotCount = 0
        var bytes = 0L
        for (k in 1..maxSpan) {
            if (target.toLong() + k - 1 >= Int.MAX_VALUE) break
            val slot = target + k - 1
            if (slot.toLong() * blockSize >= size || slot in cache ||
                (slot in inFlight && inFlight[slot] !== tailDonor) || slot in failed || slot in persisting) break
            if (owner != null && slot !in windowOf(owner)) break
            if (owner == null && wanted[slot]?.contains(targetWant) != true) break
            bytes = minOf(blockSize, size - target.toLong() * blockSize)
            val inside = if (k == 1 && blocking) cache.keys.filter { it !in outside && it !in persisting && live.none { c -> slotOf(c.position) == it } }.sumOf { cache.getValue(it).size.toLong() } else 0L
            if (bytes > baseFree + outsideBytes + inside) break
            slotCount = k
        }
        if (slotCount == 0) return null
        val needed = minOf(blockSize, size - target.toLong() * blockSize)
        while (cachedBytes + inFlightBytes() + pendingPersistBytes + needed > memoryCapBytes) {
            val victim = cache.keys.firstOrNull { it in outside }
                ?: (if (slotCount == 1 && blocking) neededLast(live) else null)
                ?: return null
            val removed = cache.remove(victim)!!.size.toLong()
            cachedBytes -= removed
            if (unreadSlots.remove(victim)) wastedBytes += removed
        }
        val fetch = Fetch(target, slotCount, targetPriority, targetOrder, durable = downloadWant != null)
        tailDonor?.let { it.endSlot = target }
        activeFetches += fetch
        if (owner != null && blocking && owner.seeking && owner.headClaimedAt == null) {
            owner.headClaimedAt = timeSource.markNow()
            scope.launch { delay(30.milliseconds); bump() }
        }
        return fetch
    }

    /**
     * The first slot [want] still waits on that nobody is fetching. Slots done for it are
     * stepped over for good; slots in flight only for this call, since an abandoned fetch hands
     * its slots back.
     *
     * Must be called under [mutex].
     */
    private fun nextClaimable(want: Want): Int? {
        val slots = want.slots
        var i = want.next
        while (i < slots.size && wanted[slots[i]]?.contains(want) != true) i++
        want.next = i
        while (i < slots.size) {
            val slot = slots[i]
            if (slot !in inFlight && slot !in cache && slot !in offered && slot !in failed && slot !in persisting && wanted[slot]?.contains(want) == true) return slot
            i++
        }
        return null
    }

    private fun liveCursors(): List<Cursor> = cursors.value.filter { it.consuming && !it.closed && it.position < size }

    private fun windowOf(cursor: Cursor): IntRange {
        if (cursor.readAhead == 0L) return if (cursor.reading && cursor.position < size) slotOf(cursor.position)..slotOf(cursor.position) else IntRange.EMPTY
        val end = (cursor.position + cursor.readAhead).coerceAtMost(size)
        return if (cursor.position >= end) IntRange.EMPTY else slotOf(cursor.position)..slotOf(end - 1)
    }

    /**
     * Higher for the block a cursor is blocked on, so it wins a contended connection slot, and
     * highest when its caller has said someone is waiting on it; a whole band lower for a
     * background cursor, so none of its requests outranks any foreground one.
     */
    private fun priorityFor(cursor: Cursor, slot: Int): Int {
        val blocking = slot == slotOf(cursor.position)
        return when (cursor.role) {
            StreamRole.FOREGROUND -> when {
                !blocking -> PikPakStreamReader.READ_AHEAD_PRIORITY
                cursor.urgent -> PikPakStreamReader.BLOCKING_PRIORITY
                else -> PikPakStreamReader.STREAMING_PRIORITY
            }
            StreamRole.BACKGROUND ->
                if (blocking) PikPakStreamReader.BACKGROUND_BLOCKING_PRIORITY else PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
        }
    }

    /**
     * Whether the stream has enough ahead of the cursor to be worth asking for double-sized
     * blocks. Everything up to [wideBlockThresholdBytes] past it must already be cached or on
     * its way, which a start and a seek both undo, so both fall back to single slots.
     *
     * Must be called under [mutex].
     */
    private fun wide(cursor: Cursor): Boolean {
        val end = (cursor.position + wideBlockThresholdBytes).coerceAtMost(size)
        for (slot in slotOf(cursor.position)..slotOf(end - 1)) {
            if (slot !in cache && slot !in inFlight) return false
        }
        return true
    }

    /** True once the block under [cursor] is in memory, keeping cold opens and seeks sharp. */
    private fun nearCovered(cursor: Cursor): Boolean = slotOf(cursor.position) in cache

    /** The cached slot farthest ahead of the nearest cursor behind it; never one a cursor is stopped on. */
    private fun neededLast(live: List<Cursor>): Int? {
        val stoppedOn = live.map { slotOf(it.position) }.toSet()
        return cache.keys.filter { it !in stoppedOn && it !in persisting }.maxByOrNull { slot ->
            live.filter { slot in windowOf(it) }.minOfOrNull { slot - slotOf(it.position) } ?: Int.MAX_VALUE
        }
    }

    private fun inFlightBytes(): Long = activeFetches.sumOf { minOf(blockSize, size - it.nextSlot.toLong() * blockSize).coerceAtLeast(0) }

    private suspend fun runFetch(fetch: Fetch) {
        try {
            while (fetch.nextSlot < fetch.endSlot) {
                val slot = fetch.nextSlot
                val offset = slot.toLong() * blockSize
                val length = minOf(blockSize, size - offset).toInt()
                if (!reserveBuffer(fetch, length)) return
                val bytes = try { store?.read(storeKey, offset, length) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Throwable) { null }
                if (bytes == null || bytes.size != length) break
                adoptBuffer(fetch, bytes)
                publish(fetch, slot, bytes, fromNetwork = false)
            }
            var attempts = 0
            while (fetch.nextSlot < fetch.endSlot) {
                val offset = fetch.nextSlot.toLong() * blockSize + fetch.buffered
                val length = fetch.endOffset - offset
                val telemetry = ReadTelemetry(timeSource = timeSource, onBytes = { bytes ->
                    registration?.let { scheduler!!.recordProgress(it, bytes) }
                })
                var completed = false
                var retired = false
                try {
                    val firstLimitedSlot = fetch.nextSlot
                    limitBlock(fetch, firstLimitedSlot)
                    withContext(RequestOrder(fetch.order) + telemetry) {
                        source.read(offset, length, fetch.priority) { channel ->
                            while (fetch.nextSlot < fetch.endSlot) {
                                val slot = fetch.nextSlot
                                val expected = minOf(blockSize, size - slot.toLong() * blockSize).toInt()
                                if (slot != firstLimitedSlot) limitBlock(fetch, slot)
                                if (!reserveBuffer(fetch, expected)) {
                                    retired = true
                                    return@read
                                }
                                val bytes = fetch.buffer ?: ByteArray(expected).also { adoptBuffer(fetch, it) }
                                while (fetch.buffered < expected) {
                                    val count = channel.readAvailable(bytes, fetch.buffered, expected - fetch.buffered)
                                    if (count < 0) throw PikPakException(-1,
                                        "stream: got ${fetch.buffered} of $expected bytes at offset ${slot.toLong() * blockSize}")
                                    if (count == 0) continue
                                    locked { fetch.buffered += count; bump() }
                                }
                                publish(fetch, slot, bytes, fromNetwork = true)
                            }
                        }
                    }
                    completed = true
                    if (retired) return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    attempts++
                    if (attempts >= MAX_ATTEMPTS) { fail(fetch, e); return }
                } finally {
                    registration?.let { scheduler!!.recordSample(it, telemetry, length,
                        !completed || retired || fetch.endOffset < offset + length) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(fetch, e)
        } finally {
            withContext(NonCancellable) {
                abandon(fetch)
            }
        }
    }

    private suspend fun limitBlock(fetch: Fetch, slot: Int) {
        val limited = locked {
            val windows = liveCursors().map(::windowOf)
            val bytes = if (wanted[slot]?.all { it.durable } == true && windows.none { slot in it })
                minOf(blockSize, size - slot.toLong() * blockSize) else 0L
            fetch.waitingForLimiter = bytes > 0 && downloadLimiter != null
            bytes
        }
        try { downloadLimiter?.acquire(limited) }
        finally { withContext(NonCancellable) { locked { fetch.waitingForLimiter = false } } }
    }

    private suspend fun publish(fetch: Fetch, slot: Int, bytes: ByteArray, fromNetwork: Boolean) {
        var queued: PersistTask? = null
        locked {
            if (inFlight[slot] !== fetch) return@locked
            val claims = wanted.remove(slot)
            failed.remove(slot)
            val windows = liveCursors().map(::windowOf)
            val downloadOnly = claims != null && claims.all { it.durable } && windows.none { slot in it }
            if (!downloadOnly) {
                put(slot, bytes)
                if (fetch.consumed) unreadSlots.remove(slot)
            }
            val durable = claims?.any { it.durable } == true
            if (durable && !fromNetwork) durableDone += slot
            else if (durable && slot !in persisting) {
                val task = PersistTask(slot, bytes, accounted = downloadOnly, optional = optionalWrites[slot])
                persisting[slot] = task
                offered[slot] = bytes
                if (downloadOnly) pendingPersistBytes += bytes.size
                queued = task
            }
            if (fromNetwork) {
                deliveredBytes += bytes.size
                if (!durable && storeWrites != null && slot !in optionalWrites) {
                    offered[slot] = bytes
                    val write = StoreWrite(slot, slot.toLong() * blockSize, bytes)
                    optionalWrites[slot] = write
                    if (!storeWrites.trySend(write).isSuccess) {
                        optionalWrites.remove(slot)
                        forgetOffer(slot, bytes)
                    }
                }
            }
            fetch.nextSlot = slot + 1
            fetch.buffer = null
            fetch.buffered = 0
            bump()
        }
        queued?.let { task ->
            // A single writer overlaps disk IO with the next block's network IO. The
            // bounded channel plus ownership credits backpressure before another allocation.
            try { durableWrites.send(task) }
            catch (e: Throwable) {
                withContext(NonCancellable) { persist(durableStore(), task) }
                throw e
            }
        }
    }

    /** Drops [slot]'s offer if it is still [bytes]: a later fetch of the slot may have replaced it. */
    private fun forgetOffer(slot: Int, bytes: ByteArray) {
        if (offered[slot] === bytes) {
            offered.remove(slot)
            // A durable promotion may be waiting for capacity while this optional write finishes.
            bump()
        }
    }

    private suspend fun fail(fetch: Fetch, cause: Throwable) {
        mutex.withLock {
            releaseSlotsLocked(fetch)
            for (slot in fetch.slots) {
                wanted.remove(slot)
                failed[slot] = cause
            }
        }
        fetch.done.completeExceptionally(cause)
        bump()
    }

    /**
     * Cancels the fetches no demand covers any more: outside every cursor's window and not
     * asked for by a warm. Their partial bytes are lost, which is the point.
     */
    private suspend fun cancelUnwanted() {
        val victims = locked {
            updateSchedule(liveCursors())
            val windows = liveCursors().map(::windowOf)
            activeFetches.toList()
                .filter { fetch -> !fetch.cancelled && fetch.slots.none { it in wanted || windows.any { window -> it in window } } }
                .onEach { it.cancelled = true }
        }
        for (fetch in victims) fetch.job?.cancel()
    }

    /**
     * Gives up a claimed fetch that will never deliver bytes.
     *
     * Idempotent, and safe to run after publication or [fail]: the slot removal only touches
     * slots this fetch still owns, and `completeExceptionally` returns false once the deferred
     * is settled. That is what lets it run on every exit path of a claimed fetch, which is the
     * only way to cover a fetch whose body never ran at all.
     */
    private suspend fun abandon(fetch: Fetch) = withContext(NonCancellable) {
        mutex.withLock {
            releaseSlotsLocked(fetch)
            activeFetches.remove(fetch)
            fetch.buffer = null
            fetch.buffered = 0
            if (fetch.reserved + fetch.transportReserved > 0) {
                memoryBudget.release(fetch.reserved + fetch.transportReserved)
                fetch.reserved = 0
                fetch.transportReserved = 0
            }
            releaseUnowned()
            fetch.done.complete(Unit)
            bump()
        }
    }

    /** Must be called under [mutex]. */
    private fun releaseSlotsLocked(fetch: Fetch) {
        fetch.released = true
    }

    ///////////////////////////////////////////////////////////////////////////
    // cache
    ///////////////////////////////////////////////////////////////////////////

    private fun slotOf(offset: Long): Int = (offset / blockSize).toInt()

    /** Must be called under [mutex]. Reinserting is what makes the map's order an LRU order. */
    private fun hit(slot: Int): ByteArray? {
        val data = cache.remove(slot) ?: return null
        cache[slot] = data
        // A hit is the only evidence a block was wanted, which is why the waste counter is
        // kept the other way round: a slot is presumed wasted until it is served.
        unreadSlots.remove(slot)
        return data
    }

    /** Must be called under [mutex]. */
    private fun put(slot: Int, data: ByteArray) {
        val previous = cache.put(slot, data)
        cachedBytes += data.size - (previous?.size ?: 0)
        unreadSlots.add(slot)
    }

    /**
     * Takes [mutex] around a section that must not suspend. A critical section that suspends
     * would hold the lock across a fetch and stall every worker behind it.
     */
    private suspend fun <T> locked(body: () -> T): T = mutex.withLock {
        try { body() } finally { releaseUnowned() }
    }

    private fun releaseUnowned() {
        val live = HashSet<ByteArray>()
        live.addAll(cache.values)
        live.addAll(offered.values)
        optionalWrites.values.mapTo(live) { it.bytes }
        persisting.values.mapNotNullTo(live) { it.bytes }
        activeFetches.mapNotNullTo(live) { it.buffer }
        for (bytes in owned.keys.toList()) if (bytes !in live) {
            memoryBudget.release(owned.remove(bytes)!!)
        }
    }

    private fun evict(slot: Int) {
        val bytes = cache.remove(slot) ?: return
        cachedBytes -= bytes.size
        if (unreadSlots.remove(slot)) wastedBytes += bytes.size
    }

    private suspend fun reserveBuffer(fetch: Fetch, bytes: Int): Boolean {
        if (fetch.reserved != 0L || fetch.buffer != null) return true
        // RangeReader owns one pump page outside this cache. Reserve it before admitting
        // the first block, atomically, so two credit waiters cannot deadlock each other.
        val transport = if ((source is RangeReader || source is PikPakFileHandle) && fetch.transportReserved == 0L) RangeReader.READ_CHUNK.toLong() else 0L
        val required = bytes + transport
        while (true) {
            val seen = memoryBudget.revision.value to revision.value
            val acquired = locked {
                checkOpen()
                require(required <= memoryBudget.budgetBytes && required <= memoryCapBytes) {
                    "transport budget must hold one block and its pump page ($required bytes)"
                }
                for (slot in cache.keys.toList()) {
                    val used = owned.values.sum() + activeFetches.sumOf { it.reserved + it.transportReserved }
                    if (used + required <= memoryCapBytes && memoryBudget.usedBytes + required <= memoryBudget.budgetBytes) break
                    if (slot !in persisting) evict(slot)
                    releaseUnowned()
                }
                val used = owned.values.sum() + activeFetches.sumOf { it.reserved + it.transportReserved }
                if (used + required <= memoryCapBytes && memoryBudget.tryAcquire(required)) {
                    fetch.reserved = bytes.toLong()
                    fetch.transportReserved += transport
                    true
                } else false
            }
            if (acquired) return true
            // Never wait for another block while holding a live transport's pump credits.
            // Returning from the source callback unwinds the pump before abandon releases them;
            // unstarted slots then become demand again, including after a shared-budget shrink.
            if (fetch.transportReserved > 0L) return false
            memoryBudget.requestReclaim()
            combine(memoryBudget.revision, revision) { a, b -> a to b }.first { it != seen }
        }
    }

    private suspend fun adoptBuffer(fetch: Fetch, bytes: ByteArray) = locked {
        check(fetch.reserved == bytes.size.toLong())
        owned[bytes] = fetch.reserved
        fetch.reserved = 0
        fetch.buffer = bytes
        fetch.buffered = 0
        fetch.consumed = false
    }

    private fun bump() {
        revision.update { it + 1 }
    }

    private fun checkOpen() {
        if (closed) throw PikPakException(-1, "PikPakStreamReader is closed")
    }

    private fun checkOpen(cursor: Cursor) {
        if (closed || cursor.closed) throw PikPakException(-1, "PikPakStreamReader is closed")
    }

    /** close() requests cancellation; this also awaits transport and ownership cleanup. */
    internal suspend fun awaitClosedForTest() {
        scope.coroutineContext.job.join()
        cleanupComplete.await()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun optionalWriteQueueEmptyForTest(): Boolean =
        storeWrites?.let { it.isClosedForReceive || it.isEmpty } ?: true

    internal suspend fun demandRangeCountForTest(): Int = locked { wants.sumOf { it.slots.ranges.size } }

    internal suspend fun cachedBytesForTest(): Long = locked { cachedBytes }

    internal suspend fun inFlightSlotsForTest(): Int = locked { inFlight.size }

    internal suspend fun accountedBytesForTest(): Long = locked { owned.values.sum() + activeFetches.sumOf { it.reserved + it.transportReserved } }

    /** Bytes from the cursor that are cached or on their way, counted contiguously and stopping at its window. */
    internal suspend fun readAheadDepthForTest(cursor: Cursor): Long = locked {
        val pos = cursor.position
        val windowEnd = (pos + cursor.readAhead).coerceAtMost(size)
        var covered = pos
        while (covered < windowEnd) {
            val slot = slotOf(covered)
            if (slot !in cache && slot !in inFlight) break
            covered = (slot + 1).toLong() * blockSize
        }
        covered.coerceAtMost(windowEnd) - pos
    }

    // Tests may claim a not-yet-started fetch while all actual workers are occupied.
    internal suspend fun claimForTest(): Fetch? = locked { claimNext(enforceWorkerLimit = false) }

    internal suspend fun abandonForTest(fetch: Fetch): Unit = abandon(fetch)

    internal companion object {
        /** Attempts per block. RangeReader already retries transport failures and refreshes expired links. */
        const val MAX_ATTEMPTS = 3

        /** Optional queue length. Its arrays retain the same ownership credits after eviction. */
        const val STORE_QUEUE_BLOCKS = 16

        /**
         * How long a foreground cursor that stopped reading still counts as foreground demand.
         * Long enough to ride out the gaps of a player reading steadily; short enough that a
         * paused one, or one sitting on a full buffer, gives the account back within seconds.
         */
        val FOREGROUND_IDLE: Duration = 5.seconds
    }
}
