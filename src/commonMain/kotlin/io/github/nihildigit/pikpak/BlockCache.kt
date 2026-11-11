package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AdaptiveRangeSource
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * A block that exhausts its source's recovery fails the reads and warms waiting on it and nothing
 * else. The next read of it tries again, once, before reporting the failure. The previous
 * design retired the whole reader on the first such block and took the cache down with it,
 * which is what forced callers to rebuild readers and fetch everything again.
 */
internal class BlockCache(
    private val source: RangeSource,
    val size: Long,
    private val concurrency: Int = DEFAULT_READ_AHEAD_CONCURRENCY,
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
    internal val timeSource: TimeSource = scheduler?.timeSource ?: TimeSource.Monotonic,
    memoryBudget: TransportMemoryBudget? = null,
    /** Default 4-way striping across 256 KiB slots = 1 MiB read-ahead window. */
    val readAheadConcurrency: Int = DEFAULT_READ_AHEAD_CONCURRENCY,
    /** Live admission ceiling; [concurrency] remains the pre-created worker hard limit. */
    private val workerLimit: () -> Int = { concurrency },
) {
    private val adaptiveSource = source as? AdaptiveRangeSource
    private val memoryBudget = memoryBudget ?: TransportMemoryBudget(memoryCapBytes)
    private val owned = HashMap<ByteArray, Long>()
    private var ownedBytes = 0L
    // Guarded by mutex. Additions and LRU hits cannot orphan an owned array.
    private var ownershipDirty = false
    private var ownershipSweeps = 0L
    private val durableWrites = Channel<PersistTask>(1)
    private val reclaim: () -> Unit = {
        scope.launch {
            locked {
                val protected = HashSet<Int>()
                val allCursors = cursors.value
                for (i in 0 until allCursors.size) {
                    val c = allCursors[i]
                    if (c.reading) protected.add(slotOf(c.position))
                }
                val victims = cache.keys.filter { it !in protected && it !in persisting }
                for (slot in victims) evict(slot)
                for (slot in partials.keys.toList()) if (slot !in protected && slot !in inFlight) {
                    partials.remove(slot)
                    ownershipDirty = true
                }
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
    private class Partial(val bytes: ByteArray, val length: Int)
    private val partials = LinkedHashMap<Int, Partial>()
    private var cachedBytes = 0L

    /** Cached slots nobody has read yet. See [wastedBytes]. */
    private val unreadSlots = HashSet<Int>()

    /** Slot -> the fetch that will fill it. One fetch may own several slots. */
    private val inFlight = FlightIndex()

    /** Range ownership is revocable and independent of resident buffer ownership. */
    private inner class FlightIndex {
        operator fun get(slot: Int): Fetch? {
            for (i in 0 until activeFetches.size) {
                val fetch = activeFetches[i]
                if (!fetch.released && fetch.containsSlot(slot)) return fetch
            }
            return null
        }
        operator fun contains(slot: Int): Boolean = get(slot) != null
        fun isNotEmpty(): Boolean {
            for (i in 0 until activeFetches.size) {
                val fetch = activeFetches[i]
                if (!fetch.released && fetch.nextSlot < fetch.endSlot) return true
            }
            return false
        }
        val size: Int
            get() {
                var total = 0
                for (i in 0 until activeFetches.size) {
                    val fetch = activeFetches[i]
                    if (!fetch.released && fetch.endSlot > fetch.nextSlot) {
                        total += fetch.endSlot - fetch.nextSlot
                    }
                }
                return total
            }
    }
    // Stable admission order breaks equal-tail ties without identity-hash randomness.
    private val activeFetches = ArrayList<Fetch>()
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
    // Scheduling reads this list outside the cache mutex. Membership is immutable;
    // progress within each Want remains protected by the mutex.
    @Volatile private var wants: List<Want> = emptyList()

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

    private class RevisionSignal {
        val flow = MutableStateFlow(0L)
        private val waiter = MutableStateFlow<CompletableDeferred<Unit>?>(null)

        val value: Long get() = flow.value

        fun bump() {
            flow.update { it + 1 }
            waiter.getAndUpdate { null }?.complete(Unit)
        }

        suspend fun awaitChange(seen: Long) {
            while (flow.value == seen) {
                val current = waiter.value ?: CompletableDeferred<Unit>().let { created ->
                    if (waiter.compareAndSet(null, created)) created else waiter.value
                }
                if (flow.value != seen) return
                current?.await()
            }
        }
    }

    /**
     * Bumped whenever scheduling state, demand, or whole-slot state changes. Mid-slot TCP
     * chunks do not bump [revision], avoiding waking parked workers and schedule reconcilers
     * on every socket segment; readers parked on an in-flight slot wait on [readSignal].
     */
    private val revision = RevisionSignal()

    /**
     * Bumped on every [revision] change and selectively on mid-slot partial growth when an
     * active [Cursor] in [awaitSlot] is waiting past its current offset in that slot.
     */
    private val readSignal = RevisionSignal()

    /**
     * Bumped whenever cursor foreground state or a foreground read's completion may start or
     * refresh the idle countdown in [watchForegroundIdle].
     */
    private val idleSignal = RevisionSignal()

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

    /** Chunk-level bytes read from the CDN socket, monotonic (including in-flight blocks). */
    @Volatile
    var receivedBytes: Long = 0
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

    internal class SlotRanges(ranges: List<IntRange>) : Iterable<Int> {
        val ranges: List<IntRange> = buildList {
            for (range in ranges) {
                if (range.isEmpty()) continue
                // Stores may report every missing block separately. Preserve request order
                // while collapsing adjacent runs before overlap checks or indexed scans.
                val last = lastOrNull()
                if (this.size == 1 && last != null && last.last.toLong() + 1 == range.first.toLong()) {
                    this[lastIndex] = last.first..range.last
                    continue
                }
                var pieces = listOf(range)
                for (existing in this) pieces = pieces.flatMap { piece ->
                    if (piece.last < existing.first || piece.first > existing.last) listOf(piece)
                    else buildList {
                        if (piece.first < existing.first) add(piece.first until existing.first)
                        if (piece.last > existing.last) add(existing.last + 1..piece.last)
                    }
                }
                for (piece in pieces) {
                    val previous = lastOrNull()
                    if (previous != null && previous.last.toLong() + 1 == piece.first.toLong())
                        this[lastIndex] = previous.first..piece.last
                    else add(piece)
                }
            }
        }
        private val ends = IntArray(this.ranges.size).also { ends ->
            var total = 0
            this.ranges.forEachIndexed { index, range ->
                total += range.last - range.first + 1
                ends[index] = total
            }
        }
        val size: Int = ends.lastOrNull() ?: 0
        operator fun contains(slot: Int): Boolean {
            for (i in 0 until ranges.size) {
                val range = ranges[i]
                if (slot >= range.first && slot <= range.last) return true
            }
            return false
        }
        fun overlaps(first: Int, lastInclusive: Int): Boolean {
            if (first > lastInclusive) return false
            for (i in 0 until ranges.size) {
                val range = ranges[i]
                if (first <= range.last && lastInclusive >= range.first) return true
            }
            return false
        }
        operator fun get(index: Int): Int {
            require(index in 0 until size)
            var low = 0
            var high = ends.lastIndex
            while (low < high) {
                val middle = (low + high) ushr 1
                if (index < ends[middle]) high = middle else low = middle + 1
            }
            return ranges[low].first + index - (if (low == 0) 0 else ends[low - 1])
        }
        override fun iterator(): Iterator<Int> = ranges.asSequence().flatMap { it.asSequence() }.iterator()
    }

    private class Want(val basePriority: Int, val order: Long, val durable: Boolean, val slots: SlotRanges, val role: StreamRole,
        val demand: TransferDemand? = null) {
        val priority: Int get() = demand?.priority?.let { if (it == PikPakStreamReader.WARM_PRIORITY && basePriority < it) basePriority else it } ?: basePriority
        val completed = SlotBits()
        var next = 0
    }

    private fun wantsSlot(want: Want, slot: Int): Boolean =
        slot !in want.completed && (!want.durable || slot !in durableDone) && slot in want.slots

    private inner class Wanted {
        fun claims(want: Want, slot: Int): Boolean = wantsSlot(want, slot)

        operator fun contains(slot: Int): Boolean {
            val list = wants
            for (i in 0 until list.size) {
                if (wantsSlot(list[i], slot)) return true
            }
            return false
        }

        fun overlapsRange(first: Int, lastExclusive: Int): Boolean {
            if (first >= lastExclusive) return false
            val list = wants
            val lastInclusive = lastExclusive - 1
            for (i in 0 until list.size) {
                val want = list[i]
                if (!want.slots.overlaps(first, lastInclusive)) continue
                for (slot in first until lastExclusive) {
                    if (wantsSlot(want, slot)) return true
                }
            }
            return false
        }

        fun maxPriority(slot: Int): Int {
            val list = wants
            var best = Int.MIN_VALUE
            for (i in 0 until list.size) {
                val want = list[i]
                if (wantsSlot(want, slot)) {
                    val p = want.priority
                    if (p > best) best = p
                }
            }
            return best
        }

        fun strongestOrNull(slot: Int, durableOnly: Boolean = false): Want? {
            val list = wants
            var best: Want? = null
            for (i in 0 until list.size) {
                val want = list[i]
                if ((durableOnly && !want.durable) || !wantsSlot(want, slot)) continue
                if (best == null || outranks(want, best)) best = want
            }
            return best
        }

        fun hasDurable(slot: Int): Boolean {
            val list = wants
            for (i in 0 until list.size) {
                val want = list[i]
                if (want.durable && wantsSlot(want, slot)) return true
            }
            return false
        }

        fun allDurable(slot: Int): Boolean {
            val list = wants
            var any = false
            for (i in 0 until list.size) {
                val want = list[i]
                if (wantsSlot(want, slot)) {
                    if (!want.durable) return false
                    any = true
                }
            }
            return any
        }

        /** Marks [slot] completed in every claiming [Want] and returns bitmask: 1 = any, 2 = durable, 4 = non-durable. */
        fun completeSlot(slot: Int): Int {
            val list = wants
            var mask = 0
            for (i in 0 until list.size) {
                val want = list[i]
                if (wantsSlot(want, slot)) {
                    want.completed += slot
                    mask = mask or 1 or (if (want.durable) 2 else 4)
                }
            }
            return mask
        }
    }

    private class StoreWrite(val slot: Int, val offset: Long, val bytes: ByteArray) {
        val done = CompletableDeferred<Throwable?>()
    }

    /** A read position and the window it keeps filled. Used by one caller at a time; see [PikPakStreamReader]. */
    inner class Cursor internal constructor(initialRole: StreamRole) {
        internal var demand: TransferDemand? = null
        internal var demandWatch: Job? = null
        internal val effectiveRole: StreamRole
            get() = demand?.let {
                if (it.priority >= PikPakStreamReader.STREAMING_PRIORITY) StreamRole.FOREGROUND else StreamRole.BACKGROUND
            } ?: role
        internal var feedbackGeneration = Long.MIN_VALUE
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
        internal var demandActive = true

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
        internal var urgentSince: TimeMark? = null

        internal val order = RequestOrder.Sequence.next()
        @Volatile internal var bufferedDuration: Duration = Duration.ZERO
        @Volatile internal var feedbackAt: TimeMark? = null
        internal var hungry = false

        /** When this cursor last read or moved; see [foregroundIdle]. */
        @Volatile
        internal var lastActive: TimeMark = timeSource.markNow()

        /** A read is parked on this cursor: it is waiting, not idle, however long that takes. */
        @Volatile
        internal var reading = false

        /** Slot index and intra-slot byte offset [awaitSlot] is currently waiting past, or -1. */
        @Volatile
        internal var waitingSlot: Int = -1

        @Volatile
        internal var waitingWithin: Int = 0

        @Volatile
        internal var seeking = true

        /**
         * Suppresses multi-slot read-ahead while a caller probes the file head at offset 0
         * (`<= 2 * blockSize`, e.g. mpv reading MP4 `ftyp`/`moov` or MKV EBML headers before
         * seeking to a resume offset) until sequential reading advances past the first slot
         * (`offset >= blockSize` or cumulative sequential read at `0` exceeds `2 * blockSize`).
         */
        @Volatile
        internal var headHysteresis = blockSize >= PikPakStreamReader.DEFAULT_BLOCK_SIZE

        @Volatile
        internal var headTargetBytes: Long = blockSize.coerceAtMost(size)

        @Volatile
        internal var headSequentialBytes: Long = 0L

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
        require(readAheadConcurrency >= 1) { "readAheadConcurrency must be >= 1, got $readAheadConcurrency" }
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
            adaptiveSource?.updateConnectionAllowance(0)
            registration?.let { scheduler?.unregister(it) }
            syncForegroundCount()
            idleSignal.bump()
            bump()
        }
        this.memoryBudget.register(reclaim)
        scope.coroutineContext.job.invokeOnCompletion {
            this.memoryBudget.unregister(reclaim)
            CoroutineScope(parentCoroutineContext + NonCancellable).launch {
                // Also covers cancellation before the optional writer ever starts.
                storeWrites?.cancel()
                locked {
                    ownershipDirty = true
                    cache.clear()
                    partials.clear()
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
        // Scheduler callbacks only bump revision. Reconcile under the cache lock rather
        // than re-entering scheduler.update or the lazy registration from its callback.
        if (adaptiveSource != null || scheduler != null) {
            scope.launch {
                revision.flow.collect { locked { updateSchedule(liveCursors()) } }
            }
        }
        if (adaptiveSource != null) {
            scope.launch {
                adaptiveSource.connectionLimit.collect { workerLimitChanged() }
            }
        }
        if (scheduler == null && foregroundStreams != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                foregroundStreams.active.collect { bump() }
            }
        }
        repeat(concurrency) { index -> scope.launch { runWorker(index) } }
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
                                    if (optionalWrites[write.slot] === write) {
                                        optionalWrites.remove(write.slot)
                                        ownershipDirty = true
                                    }
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
                            ownershipDirty = true
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
        idleSignal.bump()
        bump()
        return cursor
    }

    /** Not suspending, like [PikPakStreamReader.close]; the fetches it orphans are cancelled from the scope. */
    fun closeCursor(cursor: Cursor) {
        cursor.demandWatch?.cancel()
        if (cursor.closed) return
        cursor.closed = true
        cursor.closing.complete(Unit)
        cursors.update { it - cursor }
        syncForegroundCount()
        if (!closed) scope.launch { cancelUnwanted() }
        idleSignal.bump()
        bump()
    }

    fun setDemandActive(cursor: Cursor, active: Boolean) {
        if (cursor.demandActive == active) return
        cursor.demandActive = active
        syncForegroundCount()
        if (!active && !closed) scope.launch { cancelUnwanted() }
        idleSignal.bump()
        bump()
    }

    fun setRole(cursor: Cursor, role: StreamRole) {
        if (cursor.role == role) return
        cursor.role = role
        syncForegroundCount()
        idleSignal.bump()
        bump()
    }

    fun setDemand(cursor: Cursor, demand: TransferDemand?) {
        cursor.demandWatch?.cancel()
        cursor.demand = demand
        if ((demand?.priority ?: 0) >= PikPakStreamReader.BLOCKING_PRIORITY && cursor.position == 0L && cursor.headSequentialBytes == 0L) {
            cursor.headHysteresis = true
        }
        cursor.demandWatch = demand?.let { owner -> scope.launch {
            owner.changes.collect { if (owner.isClosed) closeCursor(cursor) else { syncForegroundCount(); idleSignal.bump(); bump() } }
        } }
        idleSignal.bump()
        bump()
    }

    fun reportPlaybackBuffer(cursor: Cursor, feedback: PlaybackBufferSnapshot) {
        if (feedback.generation < cursor.feedbackGeneration) return
        cursor.feedbackGeneration = feedback.generation
        cursor.feedbackAt = feedback.sampledAt.takeIf { feedback.bufferedDuration != null }
        cursor.bufferedDuration = feedback.bufferedDuration ?: Duration.ZERO
        feedback.mediaBytesPerSecond?.let { updateMediaBytesPerSecond(it) }
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
        cursor.urgentSince = if (urgent) timeSource.markNow() else null
        if (urgent && cursor.position == 0L && cursor.headSequentialBytes == 0L) {
            cursor.headHysteresis = true
        }
        bump()
    }

    fun setReadAhead(cursor: Cursor, bytes: Long) {
        val previous = cursor.readAhead
        cursor.readAhead = bytes.coerceIn(0, readAheadBytes)
        if (cursor.readAhead < previous && !closed) scope.launch { cancelUnwanted(); syncForegroundCount() }
        idleSignal.bump()
        bump()
    }

    /** Cheap: nothing is fetched here, the workers pick the new window up on the next claim. */
    suspend fun seek(cursor: Cursor, position: Long) {
        checkOpen(cursor)
        touch(cursor)
        val inNearWindow = cursor.consuming && !cursor.seeking &&
            cursor.readAhead >= 2L * 1024 * 1024 &&
            position >= cursor.position && position - cursor.position <= 2L * 1024 * 1024
        cursor.position = position
        if (!inNearWindow) {
            cursor.seeking = true
        }
        if (position == 0L) {
            cursor.headSequentialBytes = 0L
            cursor.headTargetBytes = blockSize.coerceAtMost(size)
            cursor.headHysteresis = blockSize >= PikPakStreamReader.DEFAULT_BLOCK_SIZE ||
                cursor.urgent ||
                (cursor.demand?.priority ?: 0) >= PikPakStreamReader.BLOCKING_PRIORITY
        } else {
            cursor.headHysteresis = false
            cursor.headSequentialBytes = -1L
        }
        cancelUnwanted()
        idleSignal.bump()
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
        if (pos == 0L) {
            if (length > 2L * blockSize) {
                cursor.headHysteresis = false
            } else if (blockSize >= PikPakStreamReader.DEFAULT_BLOCK_SIZE ||
                cursor.urgent ||
                (cursor.demand?.priority ?: 0) >= PikPakStreamReader.BLOCKING_PRIORITY
            ) {
                cursor.headHysteresis = true
                cursor.headTargetBytes = maxOf(blockSize, length.toLong()).coerceAtMost(size)
            }
        } else if (cursor.headHysteresis && pos >= blockSize) {
            cursor.headHysteresis = false
            if (cursor.seeking && cursor.headSequentialBytes >= blockSize) {
                cursor.seeking = false
            }
            bump()
        }
        if (!cursor.consuming) {
            cursor.consuming = true
            bump()
        }
        val slot = slotOf(pos)
        cursor.reading = true
        touch(cursor)
        syncForegroundCount()
        if (cursor.readAhead == 0L || !cursor.demandActive) bump()
        val available = try {
            awaitSlot(cursor, slot, (pos - slot.toLong() * blockSize).toInt(), buffer, offset, length)
        } finally {
            cursor.reading = false
            cursor.lastActive = timeSource.markNow()
            if (cursor.effectiveRole == StreamRole.FOREGROUND) idleSignal.bump()
            syncForegroundCount()
            if (!cursor.demandActive && !closed) scope.launch { cancelUnwanted() }
            if (cursor.readAhead == 0L || !cursor.demandActive) bump()
        }
        if (cursor.headSequentialBytes >= 0L) {
            cursor.headSequentialBytes += available
        }
        if (cursor.headHysteresis && (pos + available >= blockSize ||
                cursor.headSequentialBytes > 2L * blockSize ||
                (!cursor.urgent && (cursor.demand?.priority ?: 0) < PikPakStreamReader.BLOCKING_PRIORITY && pos + available >= cursor.headTargetBytes))
        ) {
            cursor.headHysteresis = false
        }
        if (cursor.seeking && !cursor.headHysteresis && (slotOf(pos + available) != slot || slot in cache)) {
            cursor.seeking = false
            bump()
        }
        val previousWindowEnd = windowLast(packedWindowOf(cursor))
        cursor.position = pos + available
        // Advancing the head can free a block; advancing the tail can expose a missing
        // block even when a small cached read stays inside the same head block.
        if (slotOf(cursor.position) != slot || windowLast(packedWindowOf(cursor)) != previousWindowEnd) {
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
    fun warm(ranges: List<LongRange>, role: StreamRole, priority: Int?, durable: Boolean = false,
        demand: TransferDemand? = null): Deferred<Unit> = scope.async {
        val store = if (durable) durableStore() else null
        val clamped = ranges.mapNotNull { range ->
            val first = range.first.coerceAtLeast(0)
            val last = range.last.coerceAtMost(size - 1)
            if (first > last) null else first..last
        }
        val requested = store?.missing(storeKey, clamped) ?: clamped
        val slots = SlotRanges(requested.map { slotOf(it.first)..slotOf(it.last) })
        val want = Want(priority ?: warmPriorityFor(role), RequestOrder.Sequence.next(), durable, slots, role, demand)
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
        val warmJob = currentCoroutineContext()[Job]!!
        val demandWatch = demand?.let { owner -> launch {
            owner.changes.collect { if (owner.isClosed) warmJob.cancel() else bump() }
        } }
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
            demandWatch?.cancel()
            withContext(NonCancellable) {
                if (!warmed) {
                    withdraw(want)
                    // Those not written yet are no longer anyone's to write
                    for (task in fromMemory) if (!task.done.isCompleted) persist(durableStore(), task)
                }
                locked {
                    wants = wants - want
                    if (foreground) foregroundWarms--
                }
            }
            syncForegroundCount()
            bump()
        }
    }

    private suspend fun withdraw(want: Want) {
        locked { wants = wants - want }
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
                    ownershipDirty = true
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
                    val live = liveCursors()
                    val victims = cache.keys.filter { it !in persisting && !isSlotInWindows(live, it) }
                    val free = memoryCapBytes - cachedBytes - inFlightBytes() - pendingPersistBytes
                    if (free + victims.sumOf { cache.getValue(it).size.toLong() } < bytes.size) {
                        waiting = true
                        return@locked null
                    }
                    for (victim in victims) {
                        if (cachedBytes + inFlightBytes() + pendingPersistBytes + bytes.size <= memoryCapBytes) break
                        evict(victim)
                    }
                    pendingPersistBytes += bytes.size
                }
                PersistTask(slot, bytes, accounted, optionalWrites[slot]).also {
                    persisting[slot] = it
                    wanted.completeSlot(slot)
                    bump()
                }
            }
            if (!waiting) return task
            revision.awaitChange(seen)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        adaptiveSource?.updateConnectionAllowance(0)
        registration?.let { scheduler?.unregister(it) }
        syncForegroundCount()
        scope.cancel()
        idleSignal.bump()
        bump()
    }

    private fun warmPriorityFor(role: StreamRole): Int = when (role) {
        StreamRole.FOREGROUND -> PikPakStreamReader.WARM_PRIORITY
        StreamRole.BACKGROUND -> PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
    }

    private fun outranks(a: Want, b: Want): Boolean =
        a.priority > b.priority || (a.priority == b.priority && a.order < b.order)

    private fun hasForegroundDemand(): Boolean {
        val allCursors = cursors.value
        for (i in 0 until allCursors.size) {
            val c = allCursors[i]
            if (c.effectiveRole == StreamRole.FOREGROUND && isActive(c)) return true
        }
        val allWants = wants
        for (i in 0 until allWants.size) {
            val w = allWants[i]
            if (!w.durable && w.priority >= PikPakStreamReader.STREAMING_PRIORITY) return true
        }
        return false
    }

    private fun isActive(cursor: Cursor): Boolean =
        !cursor.closed && (cursor.reading || (cursor.demandActive && cursor.readAhead > 0L && cursor.lastActive.elapsedNow() < foregroundIdle))

    /** Marks [cursor] busy, and counts it as foreground again if it had gone idle. */
    private fun touch(cursor: Cursor) {
        val wasActive = isActive(cursor)
        cursor.lastActive = timeSource.markNow()
        if (!wasActive && cursor.effectiveRole == StreamRole.FOREGROUND) {
            syncForegroundCount()
            idleSignal.bump()
            bump()
        }
    }

    /**
     * Takes a foreground cursor out of the account's count once it has been idle for
     * [foregroundIdle]. Nothing else would: going idle is the absence of an event.
     */
    private suspend fun watchForegroundIdle() {
        while (currentCoroutineContext().isActive) {
            val seen = idleSignal.value
            var nextIdle: Duration? = null
            val allCursors = cursors.value
            for (i in 0 until allCursors.size) {
                val c = allCursors[i]
                if (c.effectiveRole == StreamRole.FOREGROUND && !c.closed && !c.reading) {
                    val remaining = foregroundIdle - c.lastActive.elapsedNow()
                    if (remaining.isPositive() && (nextIdle == null || remaining < nextIdle)) {
                        nextIdle = remaining
                    }
                }
            }
            if (nextIdle == null) {
                syncForegroundCount()
                idleSignal.awaitChange(seen)
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

    /** Wakes parked workers after the caller changes the live limit; active reads drain normally. */
    fun workerLimitChanged() = bump()

    private fun activeWorkers(): Int {
        val scheduled = registration?.let { scheduler!!.workersFor(it, false) }
            ?: if (throttled()) PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND else concurrency
        val capacity = minOf(concurrency, workerLimit().coerceAtLeast(0), scheduled,
            adaptiveSource?.connectionLimit?.value?.coerceAtLeast(0) ?: concurrency)
        return if (!hasForegroundDemand() && foregroundWarms > 0)
            minOf(capacity, if (scheduler?.isAccountHungry() == true) 1 else 2) else capacity
    }

    private fun pendingCursor(cursor: Cursor): Boolean {
        if (cursor.reading) return true
        val win = packedWindowOf(cursor)
        if (isWindowEmpty(win)) return false
        for (slot in windowFirst(win)..windowLast(win)) {
            if (slot !in cache && slot !in offered && slot !in failed && slot !in persisting) return true
        }
        return false
    }

    private fun updateSchedule(live: List<Cursor>) {
        var anyForegroundActive = false
        var anyForegroundHungry = false
        var anyForegroundUrgent = false
        var anyForegroundNotSeeking = false
        var anyPendingCursor = false
        var anyBackgroundPendingCursor = false
        for (i in 0 until live.size) {
            val cursor = live[i]
            val isFgActive = cursor.effectiveRole == StreamRole.FOREGROUND && isActive(cursor)
            if (isFgActive) {
                anyForegroundActive = true
                val isUrgent = cursor.urgent || (cursor.demand?.priority ?: 0) >= PikPakStreamReader.BLOCKING_PRIORITY
                val runway = cursor.feedbackAt?.let { cursor.bufferedDuration - it.elapsedNow() }
                cursor.hungry = isUrgent || if (runway != null) runway <= Duration.ZERO
                    else cursor.reading && slotOf(cursor.position) !in cache
                if (cursor.hungry) anyForegroundHungry = true
                if (isUrgent) anyForegroundUrgent = true
                if (!cursor.seeking) anyForegroundNotSeeking = true
            }
            if (!anyPendingCursor || (!anyBackgroundPendingCursor && cursor.effectiveRole == StreamRole.BACKGROUND)) {
                if (pendingCursor(cursor)) {
                    anyPendingCursor = true
                    if (cursor.effectiveRole == StreamRole.BACKGROUND) anyBackgroundPendingCursor = true
                }
            }
        }
        val allWants = wants
        var requiredWarm = false
        var urgentWarm = false
        var backgroundWarm = false
        var pendingWarm = false
        for (i in 0 until allWants.size) {
            val want = allWants[i]
            val p = want.priority
            if (!want.durable && p >= PikPakStreamReader.STREAMING_PRIORITY) requiredWarm = true
            if (!want.durable && p >= PikPakStreamReader.BLOCKING_PRIORITY) urgentWarm = true
            if (want.durable || want.role == StreamRole.BACKGROUND) backgroundWarm = true
            if (!pendingWarm && nextClaimable(want) != null) pendingWarm = true
        }
        if (!pendingWarm) {
            for (i in 0 until activeFetches.size) {
                val fetch = activeFetches[i]
                if (!fetch.cancelled && !fetch.released && wanted.overlapsRange(fetch.nextSlot, fetch.endSlot)) {
                    pendingWarm = true
                    break
                }
            }
        }
        hungry = anyForegroundHungry || requiredWarm
        val hasDemand = !closed && (pendingWarm || anyPendingCursor)
        val urgent = (anyForegroundUrgent || urgentWarm) && hasDemand
        registration?.let { scheduler!!.reportDemand(it, io.github.nihildigit.pikpak.internal.CacheDemand(
            foreground = (anyForegroundActive || requiredWarm) && hasDemand,
            warm = !closed && foregroundWarms > 0 && pendingWarm,
            hungry = hungry && hasDemand,
            urgent = urgent,
            background = hasDemand && (backgroundWarm || anyBackgroundPendingCursor),
        )) }
        adaptiveSource?.updateConnectionAllowance(if (hasDemand) {
            registration?.let { scheduler!!.connectionAllowanceFor(it) } ?: concurrency
        } else 0)
        val accountHungry = scheduler?.isAccountHungry() ?: hungry
        val minActivePriority = if (anyForegroundNotSeeking) {
            PikPakStreamReader.READ_AHEAD_PRIORITY
        } else {
            PikPakStreamReader.STREAMING_PRIORITY
        }
        for (i in 0 until activeFetches.size) {
            val fetch = activeFetches[i]
            val nextSlot = fetch.nextSlot
            var hasReadingHead = false
            var maxCursorPriority = Int.MIN_VALUE
            for (j in 0 until live.size) {
                val c = live[j]
                if (c.reading && slotOf(c.position) == nextSlot) hasReadingHead = true
                if (windowContains(packedWindowOf(c), nextSlot)) {
                    val p = priorityFor(c, nextSlot)
                    if (p > maxCursorPriority) maxCursorPriority = p
                }
            }
            val priority = maxOf(maxCursorPriority, wanted.maxPriority(nextSlot))
            fetch.demand.updatePriority(priority)
            if (accountHungry && fetch.durable && hasReadingHead) {
                // Inherit the useful head, but do not carry its priority into the remaining download.
                fetch.endSlot = nextSlot + 1
            }
            if (scheduler != null && accountHungry && priority < minActivePriority) {
                fetch.cancelled = true
                fetch.job?.cancel()
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
        var firstPass = true
        var retried = false
        try {
            while (true) {
                checkOpen(cursor)
                var seen = 0L
                var failure: Throwable? = null
                val cached = locked {
                    val isFirstPass = firstPass
                    firstPass = false
                    seen = readSignal.value
                    checkOpen(cursor)
                    updateSchedule(liveCursors())
                    inFlight[slot]?.takeIf { slot > it.nextSlot }?.let {
                        // A future range tail is a plan, not a reason to wait for its predecessor.
                        it.endSlot = slot
                        if (activeFetches.size >= activeWorkers()) {
                            it.cancelled = true
                            it.job?.cancel()
                        }
                        revision.bump()
                    }
                    val slotFailure = failed[slot]
                    if (slotFailure != null) {
                        if (!isFirstPass || retried) {
                            failure = slotFailure
                        } else {
                            failed.remove(slot)
                            retried = true
                            revision.bump()
                        }
                    }
                    val complete = hit(slot) ?: persisting[slot]?.bytes ?: offered[slot]
                    val fetch = inFlight[slot]?.takeIf { it.nextSlot == slot }
                    val partial = partials[slot]
                    val bytes = complete ?: fetch?.buffer ?: partial?.bytes
                    val end = complete?.size ?: fetch?.buffered ?: partial?.length ?: 0
                    if (bytes != null && end > within) {
                        cursor.waitingSlot = -1
                        val count = minOf(end - within, length)
                        // The published prefix is append-only. Copy under the ownership lock, so
                        // no snapshot allocation or escaped cache reference needs extra credits.
                        bytes.copyInto(destination, offset, within, within + count)
                        if (complete == null) fetch?.consumed = true
                        count
                    } else {
                        if (failure == null && fetch != null && fetch.priority < PikPakStreamReader.STREAMING_PRIORITY) {
                            val urgentWait = cursor.urgent || (cursor.demand?.priority ?: 0) >= PikPakStreamReader.BLOCKING_PRIORITY
                            val mark = cursor.feedbackAt
                            val runway = mark?.let { cursor.bufferedDuration - it.elapsedNow() }
                            val deadline = when {
                                urgentWait && fetch.priority < PikPakStreamReader.READ_AHEAD_PRIORITY -> timeSource.markNow()
                                urgentWait -> if (runway != null && runway < URGENT_TAKEOVER_DEADLINE) {
                                    mark + cursor.bufferedDuration
                                } else {
                                    timeSource.markNow() + URGENT_TAKEOVER_DEADLINE
                                }
                                mark != null -> mark + cursor.bufferedDuration
                                else -> null
                            }
                            if (deadline != null && (fetch.deadlineSlot != slot ||
                                    (urgentWait && !fetch.deadlineUrgent) ||
                                    (!urgentWait && fetch.deadlineFeedback !== mark))
                            ) {
                                fetch.deadlineSlot = slot
                                fetch.deadlineUrgent = urgentWait
                                fetch.deadlineFeedback = mark
                                fetch.demand.requireBytesBy(slot.toLong() * blockSize + within + 1, deadline)
                            }
                        }
                        if (failure == null) {
                            cursor.waitingSlot = slot
                            cursor.waitingWithin = within
                        } else {
                            cursor.waitingSlot = -1
                        }
                        null
                    }
                }
                if (cached != null) return cached
                failure?.let { throw PikPakException(-1, "stream failed at offset ${slot.toLong() * blockSize}", cause = it) }
                readSignal.awaitChange(seen)
            }
        } finally {
            cursor.waitingSlot = -1
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
            revision.awaitChange(seen)
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
        val demand = TransferDemand(priority)
        var deadlineSlot: Int = -1
        var deadlineUrgent: Boolean = false
        var deadlineFeedback: TimeMark? = null
        val done = CompletableDeferred<Unit>(scope.coroutineContext[Job])

        @Volatile
        var job: Job? = null

        @Volatile
        var cancelled = false

        var released = false
        var lastProgressAt = timeSource.markNow()
        @Volatile var nextSlot = firstSlot
        @Volatile var endSlot = firstSlot + slotCount
        @Volatile var waitingForLimiter = false
        var networkStarted = false
        var buffer: ByteArray? = null
        var buffered = 0
        var consumed = false
        var reserved = 0L
        var transportReserved = 0L
        val slotCount: Int get() = endSlot - firstSlot
        val startOffset: Long get() = firstSlot.toLong() * blockSize
        val endOffset: Long get() = (endSlot.toLong() * blockSize).coerceAtMost(size)
        val slots: IntRange get() = nextSlot until endSlot
        fun containsSlot(slot: Int): Boolean = slot >= nextSlot && slot < endSlot
    }

    private suspend fun runWorker(index: Int) {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value
            if (index >= activeWorkers()) {
                // Parked above the background cap. It lifts when this file gains foreground
                // demand, which bumps, or when the account's last foreground file leaves.
                revision.awaitChange(seen)
                continue
            }
            val fetch = locked { claimNext() }
            if (fetch == null) {
                revision.awaitChange(seen)
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
     * of the demand. The scheduler sizes playback ranges independently of their distance
     * from the head; the useful window and fair share bound their ownership.
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
        var anyFgNotSeeking = false
        for (i in 0 until live.size) {
            val c = live[i]
            if (c.effectiveRole == StreamRole.FOREGROUND && isActive(c) && !c.seeking) {
                anyFgNotSeeking = true
                break
            }
        }
        val minCursorPriority = if (anyFgNotSeeking) {
            PikPakStreamReader.READ_AHEAD_PRIORITY
        } else {
            PikPakStreamReader.STREAMING_PRIORITY
        }
        val effectiveReadAhead = effectiveReadAheadConcurrency()
        var readAheadActive = 0
        var durableActive = 0
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            if (f.durable) {
                durableActive++
            } else if (!wanted.overlapsRange(f.nextSlot, f.endSlot)) {
                readAheadActive++
            }
        }
        val readAheadHeld = readAheadActive >= effectiveReadAhead
        val accountHungryNow = scheduler?.isAccountHungry() == true
        for (i in 0 until live.size) {
            val cursor = live[i]
            val win = packedWindowOf(cursor)
            if (isWindowEmpty(win)) continue
            val cursorHead = slotOf(cursor.position)
            for (slot in windowFirst(win)..windowLast(win)) {
                if (slot in cache || slot in offered || slot in inFlight || slot in failed || slot in persisting) continue
                if (readAheadHeld && slot != cursorHead) continue
                val prio = priorityFor(cursor, slot)
                if (accountHungryNow && prio < minCursorPriority) continue
                consider(slot, prio, cursor.order, cursor)
                break
            }
        }
        var targetWant: Want? = null
        val durableLimit = registration?.let { scheduler!!.workersFor(it, true) }
            ?: if (downloadsThrottled()) PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND else concurrency
        val localLimit = concurrency
        val downloadsHeld = durableActive >= minOf(durableLimit, localLimit)
        val allWants = wants
        for (i in 0 until allWants.size) {
            val want = allWants[i]
            if (want.durable && downloadsHeld) continue
            val slot = nextClaimable(want) ?: continue
            // A slot several warms want goes at the strongest of them
            val strongest = wanted.strongestOrNull(slot) ?: continue
            if (accountHungryNow && strongest.priority < PikPakStreamReader.STREAMING_PRIORITY) continue
            if (consider(slot, strongest.priority, strongest.order, null)) targetWant = want
        }
        // Only idle capacity rescues owned work; unclaimed demand always wins. Keep the
        // current slot with its reader even if it is suspended before allocating a buffer.
        // The ownership transfer below commits under the same lock as memory admission.
        var tailDonor: Fetch? = null
        if (target == -1 && !downloadsHeld) {
            var donor: Fetch? = null
            var donorSpan = 1
            for (i in 0 until activeFetches.size) {
                val f = activeFetches[i]
                val span = f.endSlot - f.nextSlot
                if (f.durable && !f.released && !f.cancelled && !f.networkStarted && span > donorSpan &&
                    f.lastProgressAt.elapsedNow() > Duration.ZERO
                ) {
                    val split = f.endSlot - span / 2
                    if (wanted.hasDurable(split)) {
                        donor = f
                        donorSpan = span
                    }
                }
            }
            if (donor != null) {
                val split = donor.endSlot - (donor.endSlot - donor.nextSlot) / 2
                val durableStrongest = wanted.strongestOrNull(split, durableOnly = true)
                if (durableStrongest != null) {
                    target = split
                    targetWant = durableStrongest
                    targetPriority = durableStrongest.priority
                    targetOrder = durableStrongest.order
                    tailDonor = donor
                }
            }
        }
        if (target == -1) return null
        val owner = targetCursor
        val blocking = owner != null && target == slotOf(owner.position)
        val downloadWant = targetWant?.takeIf { owner == null && it.durable }
        val streamWarm = targetWant?.takeIf { owner == null && !it.durable }
        val mayWiden = if (scheduler != null) {
            (owner != null && (!blocking || !owner.seeking)) ||
                (downloadWant != null && !wasAccountHungry) ||
                (streamWarm != null && (!wasAccountHungry || streamWarm.role == StreamRole.FOREGROUND))
        } else {
            !throttled() && ((owner != null && wide(owner)) ||
                (downloadWant != null && !downloadsThrottled()) ||
                streamWarm != null)
        }
        val spanWorkers = when {
            downloadWant != null -> minOf(durableLimit, localLimit)
            owner != null -> minOf(activeWorkers(), effectiveReadAhead)
            else -> activeWorkers()
        }.coerceAtLeast(1)
        val ownerWindow = if (owner != null) packedWindowOf(owner) else EMPTY_WINDOW
        val fairShare = when {
            downloadWant != null -> {
                val remaining = (downloadWant.slots.size - downloadWant.next).coerceAtLeast(1).toLong()
                ((remaining + spanWorkers - 1) / spanWorkers).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            owner != null -> {
                val winCount = (if (isWindowEmpty(ownerWindow)) 1 else (windowLast(ownerWindow) - target + 1).coerceAtLeast(1)).toLong()
                ((winCount + spanWorkers - 1) / spanWorkers).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            else -> 2
        }
        val highBitrateSpan = if (!blocking) highBitratePlaybackSpan() else 1
        val playbackSpan = owner?.let { cursor ->
            val baseSpan = if (registration == null) {
                if (wide(cursor)) 4 else 2
            } else {
                scheduler!!.spanFor(registration!!, spanWorkers)
            }
            maxOf(baseSpan, highBitrateSpan)
        } ?: (registration?.let { scheduler!!.spanFor(it, spanWorkers) } ?: 2)
        val maxSpan = when {
            owner != null && targetPriority == PikPakStreamReader.READ_AHEAD_PRIORITY && comfortable(owner) -> 1
            tailDonor != null -> tailDonor.endSlot - target
            owner != null && registration == null && !throttled() -> minOf(fairShare, playbackSpan)
            !mayWiden -> 1
            owner != null && !throttled() -> minOf(fairShare, playbackSpan)
            streamWarm != null -> 2
            else -> minOf(fairShare, registration?.let { scheduler!!.spanFor(it, spanWorkers, downloadWant != null) } ?: 2)
        }
        val cap = minOf(memoryCapBytes, memoryBudget.budgetBytes)
        val windows = packedWindowsOf(live)
        var pendingFetchBytes = 0L
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            val blockNeed = if (f.buffer != null) 0L else maxOf(f.reserved, minOf(blockSize, size - f.nextSlot.toLong() * blockSize).coerceAtLeast(0L))
            pendingFetchBytes += blockNeed + f.transportReserved
        }
        val newFetchTransport = if (source is RangeReader || source is PikPakFileHandle) RangeReader.READ_CHUNK.toLong() else 0L
        val actualUsed = maxOf(
            cachedBytes + inFlightBytes() + pendingPersistBytes,
            ownedBytes + pendingFetchBytes + newFetchTransport,
        )
        val baseFree = (cap - actualUsed).coerceAtLeast(0L) + (partials[target]?.bytes?.size ?: 0)
        var outsideBytes = 0L
        var insideBlockingBytes = 0L
        for ((cachedSlot, cachedArr) in cache) {
            if (cachedSlot in persisting || cachedSlot in offered) continue
            if (!inPackedWindows(windows, cachedSlot)) {
                outsideBytes += cachedArr.size.toLong()
            } else if (blocking) {
                var stoppedHere = false
                for (i in 0 until live.size) {
                    if (slotOf(live[i].position) == cachedSlot) {
                        stoppedHere = true
                        break
                    }
                }
                if (!stoppedHere) insideBlockingBytes += cachedArr.size.toLong()
            }
        }
        for ((partialSlot, partial) in partials) {
            if (partialSlot != target && partialSlot !in inFlight && !inPackedWindows(windows, partialSlot)) {
                outsideBytes += partial.bytes.size.toLong()
            }
        }
        var slotCount = 0
        var bytes = 0L
        for (k in 1..maxSpan) {
            if (target.toLong() + k - 1 >= Int.MAX_VALUE) break
            val slot = target + k - 1
            if (owner != null && targetPriority == PikPakStreamReader.READ_AHEAD_PRIORITY) {
                val boundary = (owner.position + wideBlockThresholdBytes + blockSize - 1) / blockSize
                if (target < boundary && slot >= boundary) break
            }
            if (slot.toLong() * blockSize >= size || slot in cache || (k > 1 && slot in partials) ||
                (slot in inFlight && inFlight[slot] !== tailDonor) || slot in failed || slot in persisting) break
            if (owner != null && !windowContains(ownerWindow, slot)) break
            if (owner == null && (targetWant == null || !wanted.claims(targetWant, slot))) break
            bytes = if (downloadWant != null) {
                minOf(blockSize, size - target.toLong() * blockSize)
            } else {
                bytes + minOf(blockSize, size - slot.toLong() * blockSize)
            }
            val inside = if (k == 1 && blocking) insideBlockingBytes else 0L
            if (bytes > baseFree + outsideBytes + inside) break
            slotCount = k
        }
        if (slotCount == 0) return null
        val needed = if (target in partials) 0L else minOf(blockSize, size - target.toLong() * blockSize)
        while (maxOf(cachedBytes + inFlightBytes() + pendingPersistBytes, ownedBytes + pendingFetchBytes + newFetchTransport) + needed > cap) {
            val partialVictim = partials.keys.firstOrNull { slot -> slot != target && slot !in inFlight && !inPackedWindows(windows, slot) }
            if (partialVictim != null) {
                partials.remove(partialVictim)
                ownershipDirty = true
                releaseUnowned()
                continue
            }
            val victim = cache.keys.firstOrNull { slot -> slot !in persisting && slot !in offered && !inPackedWindows(windows, slot) }
                ?: (if (slotCount == 1 && blocking) neededLast(live) else null)
                ?: return null
            evict(victim)
            releaseUnowned()
        }
        val fetch = Fetch(target, slotCount, targetPriority, targetOrder, durable = downloadWant != null)
        tailDonor?.let { it.endSlot = target }
        activeFetches.add(fetch)
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
        while (i < slots.size) {
            val slot = slots[i]
            if (slot !in want.completed && (!want.durable || slot !in durableDone)) break
            i++
        }
        want.next = i
        while (i < slots.size) {
            val slot = slots[i]
            if (slot !in want.completed && (!want.durable || slot !in durableDone) &&
                slot !in inFlight && slot !in cache && slot !in offered && slot !in failed && slot !in persisting
            ) return slot
            i++
        }
        return null
    }

    private fun liveCursors(): List<Cursor> {
        val all = cursors.value
        if (all.isEmpty()) return emptyList()
        var result: ArrayList<Cursor>? = null
        for (i in 0 until all.size) {
            val c = all[i]
            if (c.consuming && !c.closed && c.position < size && (c.demandActive || c.reading)) {
                (result ?: ArrayList<Cursor>(all.size).also { result = it }).add(c)
            }
        }
        return result ?: emptyList()
    }

    private fun packedWindowOf(cursor: Cursor): Long {
        if (!cursor.demandActive && !cursor.reading) return EMPTY_WINDOW
        if (cursor.readAhead == 0L) {
            return if (cursor.reading && cursor.position < size) {
                val s = slotOf(cursor.position)
                packWindow(s, s)
            } else EMPTY_WINDOW
        }
        if (cursor.headHysteresis) {
            if (size <= 0L || cursor.position >= size) return EMPTY_WINDOW
            val endByte = minOf(size - 1, maxOf(cursor.position, cursor.headTargetBytes - 1))
            return packWindow(slotOf(cursor.position), slotOf(endByte))
        }
        if (cursor.effectiveRole == StreamRole.FOREGROUND && cursor.seeking &&
            (cursor.urgent || (cursor.demand?.priority ?: 0) >= PikPakStreamReader.BLOCKING_PRIORITY)
        ) {
            return if (cursor.position < size) {
                val s = slotOf(cursor.position)
                packWindow(s, s)
            } else EMPTY_WINDOW
        }
        val end = (cursor.position + cursor.readAhead).coerceAtMost(size)
        return if (cursor.position >= end) EMPTY_WINDOW else packWindow(slotOf(cursor.position), slotOf(end - 1))
    }

    private fun packedWindowsOf(live: List<Cursor>): LongArray {
        if (live.isEmpty()) return EMPTY_WINDOWS
        return LongArray(live.size) { packedWindowOf(live[it]) }
    }

    private fun inPackedWindows(windows: LongArray, slot: Int): Boolean {
        for (i in 0 until windows.size) {
            if (windowContains(windows[i], slot)) return true
        }
        return false
    }

    private fun isSlotInWindows(live: List<Cursor>, slot: Int): Boolean {
        for (i in 0 until live.size) {
            if (windowContains(packedWindowOf(live[i]), slot)) return true
        }
        return false
    }

    /**
     * Higher for the block a cursor is blocked on, so it wins a contended connection slot, and
     * highest when its caller has said someone is waiting on it; a whole band lower for a
     * background cursor, so none of its requests outranks any foreground one.
     */
    private fun priorityFor(cursor: Cursor, slot: Int): Int {
        val blocking = slot == slotOf(cursor.position)
        if (blocking) cursor.demand?.let { return it.priority }
        return when (cursor.effectiveRole) {
            StreamRole.FOREGROUND -> when {
                !blocking && slot.toLong() * blockSize - cursor.position >= wideBlockThresholdBytes &&
                    comfortable(cursor) -> PikPakStreamReader.FAR_READ_AHEAD_PRIORITY
                !blocking -> PikPakStreamReader.READ_AHEAD_PRIORITY
                cursor.urgent -> PikPakStreamReader.BLOCKING_PRIORITY
                else -> PikPakStreamReader.STREAMING_PRIORITY
            }
            StreamRole.BACKGROUND ->
                if (blocking) PikPakStreamReader.BACKGROUND_BLOCKING_PRIORITY else PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
        }
    }

    /** Only published bytes count: an in-flight reservation cannot keep playback running. */
    private fun comfortable(cursor: Cursor): Boolean {
        if (wideBlockThresholdBytes <= 0 || size - cursor.position < wideBlockThresholdBytes) return false
        val end = cursor.position + wideBlockThresholdBytes
        for (slot in slotOf(cursor.position)..slotOf(end - 1)) {
            val bytes = cache[slot] ?: return false
            if (slot.toLong() * blockSize + bytes.size < minOf(end, (slot + 1L) * blockSize)) return false
        }
        return true
    }

    /**
     * Whether the stream has enough ahead of the cursor to be worth asking for double-sized
     * blocks under the legacy fallback policy used without an account scheduler.
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

    /** The cached slot farthest ahead of the nearest cursor behind it; never one a cursor is stopped on. */
    private fun neededLast(live: List<Cursor>): Int? {
        var bestSlot = -1
        var bestDistance = Int.MIN_VALUE
        for (slot in cache.keys) {
            if (slot in persisting || slot in offered) continue
            var stopped = false
            var minDistance = Int.MAX_VALUE
            for (i in 0 until live.size) {
                val c = live[i]
                val head = slotOf(c.position)
                if (head == slot) {
                    stopped = true
                    break
                }
                if (windowContains(packedWindowOf(c), slot)) {
                    val d = slot - head
                    if (d < minDistance) minDistance = d
                }
            }
            if (!stopped && minDistance > bestDistance) {
                bestDistance = minDistance
                bestSlot = slot
            }
        }
        return if (bestSlot >= 0) bestSlot else null
    }

    private fun inFlightBytes(): Long {
        var total = 0L
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            total += minOf(blockSize, size - f.nextSlot.toLong() * blockSize).coerceAtLeast(0L)
        }
        return total
    }

    private fun activeReservedBytes(): Long {
        var total = 0L
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            total += f.reserved + f.transportReserved
        }
        return total
    }

    private suspend fun runFetch(fetch: Fetch) {
        try {
            while (fetch.nextSlot < fetch.endSlot) {
                val slot = fetch.nextSlot
                val offset = slot.toLong() * blockSize
                val length = minOf(blockSize, size - offset).toInt()
                if (!reserveBuffer(fetch, length)) return
                if (fetch.buffer != null) {
                    if (fetch.buffered == length) {
                        publish(fetch, slot, fetch.buffer!!, fromNetwork = true)
                        continue
                    }
                    break
                }
                val bytes = try { store?.read(storeKey, offset, length) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Throwable) { null }
                if (bytes == null || bytes.size != length) break
                adoptBuffer(fetch, bytes)
                publish(fetch, slot, bytes, fromNetwork = false)
            }
            var sourceFailures = 0
            while (fetch.nextSlot < fetch.endSlot) {
                val telemetry = ReadTelemetry(timeSource = timeSource)
                var retired = false
                try {
                    val firstLimitedSlot = fetch.nextSlot
                    limitBlock(fetch, firstLimitedSlot)
                    val (offset, length) = locked {
                        // Once issued, even an unread tail may already be in HTTP buffers.
                        // Only unissued plans can be split without downloading bytes twice.
                        fetch.networkStarted = true
                        val offset = fetch.nextSlot.toLong() * blockSize + fetch.buffered
                        offset to (fetch.endOffset - offset)
                    }
                    if (length <= 0) return
                    withContext(RequestOrder(fetch.order) + telemetry + fetch.demand) {
                        source.read(offset, length, fetch.priority) { channel ->
                            while (fetch.nextSlot < fetch.endSlot) {
                                val slot = fetch.nextSlot
                                val expected = minOf(blockSize, size - slot.toLong() * blockSize).toInt()
                                if (slot != firstLimitedSlot) {
                                    telemetry.beginLimiterWait()
                                    try { limitBlock(fetch, slot) } finally { telemetry.endLimiterWait() }
                                }
                                if (!reserveBuffer(fetch, expected, allowPartialOffset = slot == firstLimitedSlot)) {
                                    retired = true
                                    return@read
                                }
                                val bytes = fetch.buffer ?: ByteArray(expected).also { adoptBuffer(fetch, it) }
                                while (fetch.buffered < expected) {
                                    val count = channel.readAvailable(bytes, fetch.buffered, expected - fetch.buffered)
                                    if (count < 0) throw PikPakException(-1,
                                        "stream: got ${fetch.buffered} of $expected bytes at offset ${slot.toLong() * blockSize}")
                                    if (count == 0) continue
                                    locked {
                                        fetch.buffered += count
                                        receivedBytes += count
                                        if (fetch.buffered < expected) {
                                            val allCursors = cursors.value
                                            var wakeReaders = false
                                            for (i in 0 until allCursors.size) {
                                                val c = allCursors[i]
                                                if (c.waitingSlot == slot && fetch.buffered > c.waitingWithin) {
                                                    c.waitingSlot = -1
                                                    wakeReaders = true
                                                }
                                            }
                                            if (wakeReaders) readSignal.bump()
                                        }
                                    }
                                }
                                publish(fetch, slot, bytes, fromNetwork = true)
                            }
                        }
                    }
                    if (retired) return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    sourceFailures++
                    if (source is RangeReader || source is PikPakFileHandle || sourceFailures >= MAX_ATTEMPTS) {
                        fail(fetch, e)
                        return
                    }
                    fetch.endSlot = fetch.nextSlot + 1
                } finally {
                    // Pump backpressure and token waiting may overlap. Their actual interval
                    // intersection is tracked by telemetry; unrelated disk waits do not cancel
                    // out token waits. The initial pre-request token wait is never included.
                    telemetry.networkDuration = (telemetry.networkDuration - telemetry.uncoveredLimiterWait).coerceAtLeast(Duration.ZERO)

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

    private suspend fun limitBlock(fetch: Fetch, slot: Int): Duration {
        val limited = locked {
            val live = liveCursors()
            val bytes = if (wanted.allDurable(slot) && !isSlotInWindows(live, slot))
                minOf(blockSize, size - slot.toLong() * blockSize) else 0L
            fetch.waitingForLimiter = bytes > 0 && downloadLimiter != null
            bytes
        }
        if (limited == 0L || downloadLimiter == null) return Duration.ZERO
        val started = timeSource.markNow()
        try {
            coroutineScope {
                val tokens = async { downloadLimiter.acquire(limited) }
                val promoted = async {
                    while (true) {
                        val seen = revision.value
                        if (locked { isSlotInWindows(liveCursors(), slot) }) break
                        revision.awaitChange(seen)
                    }
                }
                try {
                    select<Unit> {
                        tokens.onAwait { }
                        promoted.onAwait { }
                    }
                } finally {
                    tokens.cancelAndJoin()
                    promoted.cancelAndJoin()
                }
            }
        } finally { withContext(NonCancellable) { locked { fetch.waitingForLimiter = false } } }
        return started.elapsedNow()
    }

    private suspend fun publish(fetch: Fetch, slot: Int, bytes: ByteArray, fromNetwork: Boolean) {
        var queued: PersistTask? = null
        locked {
            if (inFlight[slot] !== fetch) return@locked
            val claimMask = wanted.completeSlot(slot)
            failed.remove(slot)
            val downloadOnly = claimMask == 3 && !isSlotInWindows(liveCursors(), slot)
            if (!downloadOnly) {
                put(slot, bytes)
                if (fetch.consumed) unreadSlots.remove(slot)
            }
            val durable = (claimMask and 2) != 0
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
            fetch.lastProgressAt = timeSource.markNow()
            ownershipDirty = true
            fetch.buffer = null
            fetch.buffered = 0
            fetch.consumed = false
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
            ownershipDirty = true
            // A durable promotion may be waiting for capacity while this optional write finishes.
            bump()
        }
    }

    private suspend fun fail(fetch: Fetch, cause: Throwable) {
        mutex.withLock {
            releaseSlotsLocked(fetch)
            for (slot in fetch.nextSlot until fetch.endSlot) {
                wanted.completeSlot(slot)
                failed[slot] = cause
            }
        }
        fetch.done.completeExceptionally(cause)
        bump()
    }

    /**
     * Cancels the fetches no demand covers any more: outside every cursor's window and not
     * asked for by a warm. Partial bytes remain cache-owned and may be reused or evicted normally.
     */
    private suspend fun cancelUnwanted() {
        val victims = locked {
            val live = liveCursors()
            updateSchedule(live)
            val windows = packedWindowsOf(live)
            fun consumedHead(fetch: Fetch): Boolean {
                if (!fetch.consumed) return false
                val all = cursors.value
                for (i in 0 until all.size) {
                    val c = all[i]
                    if (!c.closed && c.demandActive && c.consuming && slotOf(c.position) == fetch.nextSlot) return true
                }
                return false
            }
            var toCancel: ArrayList<Fetch>? = null
            for (i in 0 until activeFetches.size) {
                val fetch = activeFetches[i]
                if (fetch.cancelled) continue
                var lastWanted = -1
                for (slot in fetch.endSlot - 1 downTo fetch.nextSlot) {
                    if (slot in wanted || inPackedWindows(windows, slot)) {
                        lastWanted = slot
                        break
                    }
                }
                val headConsumed = lastWanted < 0 && consumedHead(fetch)
                if (!fetch.released) {
                    val keepLast = if (lastWanted >= 0) lastWanted else if (headConsumed) fetch.nextSlot else -1
                    if (keepLast >= 0 && keepLast + 1 < fetch.endSlot) {
                        fetch.endSlot = maxOf(fetch.nextSlot + 1, keepLast + 1)
                    }
                }
                if (lastWanted < 0 && !headConsumed) {
                    fetch.cancelled = true
                    (toCancel ?: ArrayList<Fetch>(4).also { toCancel = it }).add(fetch)
                }
            }
            toCancel
        }
        if (victims != null) {
            for (i in 0 until victims.size) victims[i].job?.cancel()
        }
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
            ownershipDirty = true
            if (!closed && fetch.buffered > 0) {
                fetch.buffer?.let { partials[fetch.nextSlot] = Partial(it, fetch.buffered) }
            }
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
        if (previous != null && previous !== data) ownershipDirty = true
        cachedBytes += data.size - (previous?.size ?: 0)
        unreadSlots.add(slot)
    }

    /**
     * Takes [mutex] around a section that must not suspend. A critical section that suspends
     * would hold the lock across a fetch and stall every worker behind it.
     */
    private suspend fun <T> locked(body: () -> T): T = mutex.withLock {
        try { body() } finally { if (ownershipDirty) releaseUnowned() }
    }

    private fun releaseUnowned() {
        if (!ownershipDirty) return
        ownershipDirty = false
        if (owned.isEmpty()) return
        ownershipSweeps++
        val live = HashSet<ByteArray>(cache.size + partials.size + offered.size + activeFetches.size + 4)
        live.addAll(cache.values)
        for (partial in partials.values) live.add(partial.bytes)
        live.addAll(offered.values)
        for (write in optionalWrites.values) live.add(write.bytes)
        for (task in persisting.values) task.bytes?.let { live.add(it) }
        for (i in 0 until activeFetches.size) activeFetches[i].buffer?.let { live.add(it) }
        val entries = owned.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key !in live) {
                memoryBudget.release(entry.value)
                ownedBytes -= entry.value
                entries.remove()
            }
        }
    }

    private fun evict(slot: Int) {
        val bytes = cache.remove(slot) ?: return
        ownershipDirty = true
        cachedBytes -= bytes.size
        if (unreadSlots.remove(slot)) wastedBytes += bytes.size
    }

    private suspend fun reserveBuffer(fetch: Fetch, bytes: Int, allowPartialOffset: Boolean = true): Boolean {
        val priority = fetch.demand.priority
        val waiter = memoryBudget.registerWaiter(priority)
        try { return reserveBuffer(fetch, bytes, priority, allowPartialOffset) }
        finally { memoryBudget.unregisterWaiter(waiter) }
    }

    private suspend fun reserveBuffer(fetch: Fetch, bytes: Int, priority: Int, allowPartialOffset: Boolean): Boolean {
        locked {
            if (fetch.buffer == null) partials.remove(fetch.nextSlot)?.let {
                if (it.bytes.size == bytes) {
                    fetch.buffer = it.bytes
                    fetch.buffered = if (allowPartialOffset) it.length else 0
                } else {
                    ownershipDirty = true
                }
            }
        }
        if (fetch.reserved != 0L || (fetch.buffer != null &&
                (fetch.transportReserved > 0 || (source !is RangeReader && source !is PikPakFileHandle)))) return true
        // RangeReader owns one pump page outside this cache. Reserve it before admitting
        // the first block, atomically, so two credit waiters cannot deadlock each other.
        val transport = if ((source is RangeReader || source is PikPakFileHandle) && fetch.transportReserved == 0L) RangeReader.READ_CHUNK.toLong() else 0L
        val required = (if (fetch.buffer == null) bytes else 0) + transport
        while (true) {
            val seenBudget = memoryBudget.revision.value
            val seenCache = revision.value
            val acquired = locked {
                checkOpen()
                require(required <= memoryBudget.budgetBytes && required <= memoryCapBytes) {
                    "transport budget must hold one block and its pump page ($required bytes)"
                }
                fun fitsMemory(): Boolean =
                    ownedBytes + activeReservedBytes() + required <= memoryCapBytes &&
                        memoryBudget.usedBytes + required <= memoryBudget.budgetBytes
                if (!fitsMemory()) {
                    val live = liveCursors()
                    val windows = packedWindowsOf(live)
                    var blocking = false
                    for (i in 0 until live.size) {
                        if (slotOf(live[i].position) == fetch.nextSlot) {
                            blocking = true
                            break
                        }
                    }
                    if (!fitsMemory() && partials.isNotEmpty()) {
                        for (slot in partials.keys.toList()) {
                            if (fitsMemory()) break
                            if (slot != fetch.nextSlot && slot !in inFlight && !inPackedWindows(windows, slot)) {
                                partials.remove(slot)
                                ownershipDirty = true
                                releaseUnowned()
                            }
                        }
                    }
                    if (!fitsMemory() && cache.isNotEmpty()) {
                        for (slot in cache.keys.toList()) {
                            if (fitsMemory()) break
                            if (slot !in persisting && slot !in offered && !inPackedWindows(windows, slot)) {
                                evict(slot)
                                releaseUnowned()
                            }
                        }
                    }
                    if (blocking) {
                        while (!fitsMemory()) {
                            val victim = neededLast(live) ?: break
                            evict(victim)
                            releaseUnowned()
                        }
                    }
                }
                val used = ownedBytes + activeReservedBytes()
                if (used + required <= memoryCapBytes && memoryBudget.tryAcquire(required, priority)) {
                    fetch.reserved = if (fetch.buffer == null) bytes.toLong() else 0L
                    fetch.transportReserved += transport
                    true
                } else false
            }
            if (acquired) return true
            // Never wait for another block while holding a live transport's pump credits.
            // Returning from the source callback unwinds the pump before abandon releases them;
            // unstarted slots then become demand again, including after a shared-budget shrink.
            if (fetch.transportReserved > 0L) return false
            if (memoryBudget.usedBytes + required > memoryBudget.budgetBytes) memoryBudget.requestReclaim()
            combine(memoryBudget.revision, revision.flow) { a, b -> a != seenBudget || b != seenCache }.first { it }
        }
    }

    private suspend fun adoptBuffer(fetch: Fetch, bytes: ByteArray) = locked {
        check(fetch.reserved == bytes.size.toLong())
        val previous = owned.put(bytes, fetch.reserved)
        ownedBytes += fetch.reserved - (previous ?: 0L)
        fetch.reserved = 0
        if (fetch.buffer != null && fetch.buffer !== bytes) ownershipDirty = true
        fetch.buffer = bytes
        fetch.buffered = 0
        fetch.consumed = false
    }

    private fun bump() {
        revision.bump()
        readSignal.bump()
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
    internal fun optionalWriteQueueEmptyForTest(): Boolean =
        storeWrites?.let { it.isClosedForReceive || it.isEmpty } ?: true

    internal suspend fun demandRangeCountForTest(): Int = locked { wants.sumOf { it.slots.ranges.size } }

    internal suspend fun cachedBytesForTest(): Long = locked { cachedBytes }

    internal suspend fun ownershipSweepsForTest(): Long = locked { ownershipSweeps }

    internal suspend fun inFlightSlotsForTest(): Int = locked { inFlight.size }

    internal suspend fun accountedBytesForTest(): Long = locked { ownedBytes + activeReservedBytes() }

    internal fun revisionForTest(): Long = revision.value

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

    private fun effectiveReadAheadConcurrency(): Int {
        if (readAheadConcurrency != DEFAULT_READ_AHEAD_CONCURRENCY) return readAheadConcurrency
        val rate = mediaBytesPerSecond ?: return readAheadConcurrency
        if (rate < HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC) return readAheadConcurrency
        val scaled = ((rate + BYTES_PER_SEC_PER_READ_AHEAD_WORKER - 1L) / BYTES_PER_SEC_PER_READ_AHEAD_WORKER).toInt()
        return scaled.coerceIn(readAheadConcurrency, concurrency)
    }

    private fun highBitratePlaybackSpan(): Int {
        val rate = mediaBytesPerSecond ?: return 1
        if (rate < HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC) return 1
        val targetSpanBytes = (rate / 4L).coerceIn(blockSize, 4L * blockSize)
        return (targetSpanBytes / blockSize).toInt().coerceIn(1, 4)
    }

    // Tests may claim a not-yet-started fetch while all actual workers are occupied.
    internal suspend fun claimForTest(): Fetch? = locked { claimNext(enforceWorkerLimit = false) }

    internal suspend fun abandonForTest(fetch: Fetch): Unit = abandon(fetch)

    internal companion object {
        private const val EMPTY_WINDOW: Long = 0xFFFF_FFFFL // windowFirst = 0, windowLast = -1
        private val EMPTY_WINDOWS = LongArray(0)
        private fun packWindow(first: Int, last: Int): Long = (first.toLong() shl 32) or (last.toLong() and 0xFFFF_FFFFL)
        private fun windowFirst(packed: Long): Int = (packed shr 32).toInt()
        private fun windowLast(packed: Long): Int = packed.toInt()
        private fun isWindowEmpty(packed: Long): Boolean = windowFirst(packed) > windowLast(packed)
        private fun windowContains(packed: Long, slot: Int): Boolean = slot >= windowFirst(packed) && slot <= windowLast(packed)

        /** Default 4-way striping across 256 KiB slots = 1 MiB read-ahead window. */
        const val DEFAULT_READ_AHEAD_CONCURRENCY = 4
        private const val HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC = 3L * 1024 * 1024
        private const val BYTES_PER_SEC_PER_READ_AHEAD_WORKER = 2L * 1024 * 1024

        /** Deadline before an urgent foreground read takes over an already in-flight low-priority slot. */
        val URGENT_TAKEOVER_DEADLINE: Duration = 1500.milliseconds

        /** Attempts per block. RangeReader already retries transport failures and refreshes expired links. */
        const val MAX_ATTEMPTS = 2

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
