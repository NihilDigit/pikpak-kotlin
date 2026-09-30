package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import io.github.nihildigit.pikpak.internal.RequestOrder
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
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
) {
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

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
    private val inFlight = HashMap<Int, Fetch>()

    /**
     * Slots warms asked for and not cached yet, with every warm still asking. A list rather
     * than the strongest request alone, so a warm withdrawn by its caller takes only its own
     * claim with it.
     */
    private val wanted = LinkedHashMap<Int, MutableList<Want>>()

    /** Warms still waiting on some of their slots, in the order they were made; see [claimNext]. */
    private val wants = mutableListOf<Want>()

    /** Durable slots whose bytes are in hand and on their way into the store. */
    private val persisting = HashSet<Int>()

    /** Durable slots the store has confirmed. Cleared for a slot when a new durable warm asks for it. */
    private val durableDone = HashSet<Int>()

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
     * One warm's claim. [slots] are the slots it registered, in the order asked, and [next] is
     * how far along them nothing is pending any more, so a claim looks at the next few slots of
     * each warm instead of every slot every warm wants: a download registers thousands.
     */
    private class Want(val priority: Int, val order: Long, val durable: Boolean) {
        var slots: IntArray = IntArray(0)
        var next = 0
    }

    private class StoreWrite(val slot: Int, val offset: Long, val bytes: ByteArray)

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

        /** When this cursor last read or moved; see [foregroundIdle]. */
        @Volatile
        internal var lastActive: TimeMark = TimeSource.Monotonic.markNow()

        /** A read is parked on this cursor: it is waiting, not idle, however long that takes. */
        @Volatile
        internal var reading = false

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
        // A wide fetch claims two slots, so concurrency fetches can hold twice that many, and
        // makeRoom wants one more block free on top before it will let a claim through. Below
        // this the cap alone stops one cursor's read-ahead short of its window.
        val headroom = (2 * concurrency + 1) * blockSize
        require(memoryCapBytes >= readAheadBytes + headroom) {
            "memoryCapBytes ($memoryCapBytes) must clear readAheadBytes ($readAheadBytes) by $headroom"
        }
        // Cancelling the job passed in through parentCoroutineContext takes the whole scope
        // down without anyone calling close, and a reader in another coroutine would then wait
        // on a revision nobody will bump again.
        scope.coroutineContext.job.invokeOnCompletion {
            closed = true
            syncForegroundCount()
            bump()
        }
        repeat(concurrency) { index -> scope.launch { runWorker(index) } }
        if (foregroundStreams != null) scope.launch { watchForegroundIdle() }
        if (store != null && storeWrites != null) {
            scope.launch {
                for (write in storeWrites) {
                    try {
                        store.write(storeKey, write.offset, write.bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                    locked { forgetOffer(write.slot, write.bytes) }
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

    fun setUrgent(cursor: Cursor, urgent: Boolean) {
        if (cursor.urgent == urgent) return
        cursor.urgent = urgent
        bump()
    }

    fun setReadAhead(cursor: Cursor, bytes: Long) {
        cursor.readAhead = bytes.coerceIn(blockSize, readAheadBytes)
        bump()
    }

    /** Cheap: nothing is fetched here, the workers pick the new window up on the next claim. */
    suspend fun seek(cursor: Cursor, position: Long) {
        checkOpen(cursor)
        touch(cursor)
        cursor.position = position
        cancelUnwanted()
        bump()
    }

    /**
     * Fills [buffer] from the cursor's position and advances it. At most one block's worth
     * however large [length] is, or -1 at end of file.
     */
    suspend fun read(cursor: Cursor, buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen(cursor)
        val pos = cursor.position
        if (pos >= size) return -1
        if (length == 0) return 0
        if (!cursor.consuming) {
            cursor.consuming = true
            bump()
        }
        val slot = slotOf(pos)
        touch(cursor)
        cursor.reading = true
        val data = try {
            awaitSlot(cursor, slot)
        } finally {
            cursor.reading = false
            cursor.lastActive = TimeSource.Monotonic.markNow()
            readsEnded.update { it + 1 }
        }
        val within = (pos - slot.toLong() * blockSize).toInt()
        val available = minOf(minOf(data.size - within, length).toLong(), size - pos).toInt()
        data.copyInto(buffer, offset, within, within + available)
        cursor.position = pos + available
        // Crossing into the next block moves the window and makes the block behind it
        // evictable, so it is the only moment a worker parked against the memory cap could
        // have work again. Without this the stream stops the first time the cache fills.
        if (slotOf(cursor.position) != slot) bump()
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
        val slots = requested.flatMap { range -> (slotOf(range.first)..slotOf(range.last)).toList() }.distinct()
        val want = Want(priority ?: warmPriorityFor(role), RequestOrder.Sequence.next(), durable)
        // A download is never what someone is watching, whatever band it asks at
        val foreground = role == StreamRole.FOREGROUND && !durable
        // Durable slots already in memory, or still queued for the store, are written from there;
        // fetching them again would spend a request on bytes a stream brought in a moment ago
        val fromMemory = mutableListOf<Pair<Int, ByteArray>>()
        locked {
            val pending = ArrayList<Int>(slots.size)
            for (slot in slots) {
                if (durable) durableDone.remove(slot)
                val cached = cache[slot] ?: if (durable) offered[slot] else null
                if (cached != null) {
                    if (durable) {
                        persisting += slot
                        fromMemory += slot to cached
                    }
                    continue
                }
                failed.remove(slot)
                persistFailed.remove(slot)
                wanted.getOrPut(slot) { mutableListOf() } += want
                pending += slot
            }
            want.slots = pending.toIntArray()
            if (pending.isNotEmpty()) wants += want
            if (foreground) foregroundWarms++
        }
        syncForegroundCount()
        bump()
        var warmed = false
        try {
            for ((slot, bytes) in fromMemory) persist(store!!, slot, bytes)
            for ((slot, _) in fromMemory) awaitWarmed(slot, durable = true)
            for (slot in want.slots) awaitWarmed(slot, durable)
            warmed = true
        } finally {
            withContext(NonCancellable) {
                if (!warmed) {
                    withdraw(want)
                    // Those not written yet are no longer anyone's to write
                    locked { for ((slot, _) in fromMemory) persisting.remove(slot) }
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
        locked {
            for (slot in want.slots) {
                val claims = wanted[slot] ?: continue
                claims.remove(want)
                if (claims.isEmpty()) wanted.remove(slot)
            }
        }
        cancelUnwanted()
    }

    private fun durableStore(): DurableBlockStore =
        store as? DurableBlockStore
            ?: throw IllegalStateException("a download needs a DurableBlockStore; this cache has ${store ?: "none"}")

    /**
     * Writes one durable slot and records the outcome. A failure fails the durable warms
     * waiting on the slot and nothing else: the readers already have the bytes.
     */
    private suspend fun persist(store: DurableBlockStore, slot: Int, bytes: ByteArray) {
        try {
            store.write(storeKey, slot.toLong() * blockSize, bytes)
            locked {
                persisting.remove(slot)
                durableDone += slot
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { locked { persisting.remove(slot) } }
            throw e
        } catch (e: Throwable) {
            locked {
                persisting.remove(slot)
                persistFailed[slot] = e
            }
        }
        bump()
    }

    fun close() {
        if (closed) return
        closed = true
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
        !cursor.closed && (cursor.reading || cursor.lastActive.elapsedNow() < foregroundIdle)

    /** Marks [cursor] busy, and counts it as foreground again if it had gone idle. */
    private fun touch(cursor: Cursor) {
        val wasActive = isActive(cursor)
        cursor.lastActive = TimeSource.Monotonic.markNow()
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

    private fun activeWorkers(): Int =
        if (throttled()) minOf(concurrency, PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND) else concurrency

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
    private suspend fun awaitSlot(cursor: Cursor, slot: Int): ByteArray {
        var retried = false
        while (true) {
            checkOpen(cursor)
            var waiter: CompletableDeferred<Unit>? = null
            var seen = 0L
            var failure: Throwable? = null
            var cleared = false
            val cached = locked {
                seen = revision.value
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
                waiter = inFlight[slot]?.done
                hit(slot)
            }
            if (cached != null) return cached
            failure?.let { throw PikPakException(-1, "stream failed at offset ${slot.toLong() * blockSize}", cause = it) }
            // Once, for the workers to see the slot claimable again. On every pass it would
            // spin: first() below returns at once for a revision this loop just moved.
            if (cleared) bump()
            val pending = waiter
            // join rather than await: a fetch cancelled because its demand went away completes
            // its deferred exceptionally, and that is a reason to look again, not to fail.
            if (pending != null) {
                select<Unit> {
                    pending.onJoin {}
                    cursor.closing.onJoin {}
                }
            } else {
                revision.first { it != seen }
            }
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
        val slotCount: Int,
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

        val startOffset: Long get() = firstSlot.toLong() * blockSize
        val endOffset: Long get() = (startOffset + slotCount * blockSize).coerceAtMost(size)
        val slots: IntRange get() = firstSlot until firstSlot + slotCount
    }

    private suspend fun runWorker(index: Int) {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value
            if (index >= activeWorkers()) {
                // Parked above the background cap. It lifts when this file gains foreground
                // demand, which bumps, or when the account's last foreground file leaves,
                // which only the count says.
                val foreground = foregroundStreams?.active ?: flowOf(0)
                combine(revision, foreground) { r, count -> r != seen || count == 0 }.first { it }
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
                abandon(fetch)
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
    private fun claimNext(): Fetch? {
        if (closed) return null
        val live = liveCursors()
        var target = -1
        var targetPriority = Int.MIN_VALUE
        var targetOrder = Long.MAX_VALUE
        var targetCursor: Cursor? = null
        fun consider(slot: Int, priority: Int, order: Long, cursor: Cursor?) {
            if (priority > targetPriority || (priority == targetPriority && order < targetOrder)) {
                target = slot
                targetPriority = priority
                targetOrder = order
                targetCursor = cursor
            }
        }
        for (cursor in live) {
            for (slot in windowOf(cursor)) {
                if (slot in cache || slot in inFlight || slot in failed) continue
                consider(slot, priorityFor(cursor, slot), cursor.order, cursor)
                break
            }
        }
        var targetWant: Want? = null
        val downloadsHeld = downloadsThrottled() &&
            inFlight.values.distinct().count { it.durable } >= PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND
        for (want in wants) {
            if (want.durable && downloadsHeld) continue
            val slot = nextClaimable(want) ?: continue
            // A slot several warms want goes at the strongest of them
            val strongest = strongest(wanted.getValue(slot))
            val before = target
            consider(slot, strongest.priority, strongest.order, null)
            if (target != before) targetWant = want
        }
        if (target == -1) return null
        val owner = targetCursor
        val blocking = owner != null && target == slotOf(owner.position)
        if (!makeRoom(live, blocking)) return null

        // A throttled file stays at single blocks: how long playback elsewhere can wait behind
        // one of its requests is bounded by that request's size. A download widens like a
        // settled stream, since neither has a reader waiting on the first block.
        val next = target + 1
        val downloadWant = targetWant?.takeIf { owner == null && it.durable }
        val slotCount = if (
            !throttled() && next !in cache && next !in inFlight && next !in failed && (
                (owner != null && wide(owner) && next <= windowOf(owner).last) ||
                    (downloadWant != null && !downloadsThrottled() && wanted[next]?.contains(downloadWant) == true)
                )
        ) 2 else 1
        val fetch = Fetch(target, slotCount, targetPriority, targetOrder, durable = downloadWant != null)
        for (slot in fetch.slots) inFlight[slot] = fetch
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
            if (slot !in inFlight && wanted[slot]?.contains(want) == true) return slot
            i++
        }
        return null
    }

    private fun liveCursors(): List<Cursor> = cursors.value.filter { it.consuming && !it.closed && it.position < size }

    private fun windowOf(cursor: Cursor): IntRange {
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

    /**
     * Frees enough of the cache to stay under [memoryCapBytes], dropping the least recently
     * used slot outside every cursor's window.
     *
     * Slots inside a window are exempt because evicting them is what the next claim would
     * have to undo. Hence the invariant in `init`: the cap has to fit one window with room to
     * spare, and what falls outside it is already-read history the LRU order ages out. Several
     * cursors can together cover more than the cap, and then the block a cursor is stopped on
     * may reach inside the windows, so a read always makes progress. It takes the slot needed
     * last, the one farthest ahead of its cursor: the least recently used one inside a window is
     * the one fetched first, right after some cursor's position, and would be read next.
     *
     * `false` means the cap is binding and nothing can be claimed until a reader advances.
     *
     * Must be called under [mutex].
     */
    private fun makeRoom(live: List<Cursor>, blocking: Boolean): Boolean {
        val windows = live.map(::windowOf)
        while (cachedBytes + inFlightBytes() + blockSize > memoryCapBytes) {
            val victim = cache.keys.firstOrNull { slot -> windows.none { slot in it } }
                ?: (if (blocking) neededLast(live) else null)
                ?: return false
            val size = (cache.remove(victim)?.size ?: 0).toLong()
            cachedBytes -= size
            if (unreadSlots.remove(victim)) wastedBytes += size
        }
        return true
    }

    /** The cached slot farthest ahead of the nearest cursor behind it; never one a cursor is stopped on. */
    private fun neededLast(live: List<Cursor>): Int? {
        val stoppedOn = live.map { slotOf(it.position) }.toSet()
        return cache.keys.filter { it !in stoppedOn }.maxByOrNull { slot ->
            live.filter { slot in windowOf(it) }.minOfOrNull { slot - slotOf(it.position) } ?: Int.MAX_VALUE
        }
    }

    private fun inFlightBytes(): Long = inFlight.size.toLong() * blockSize

    private suspend fun runFetch(fetch: Fetch) {
        stored(fetch)?.let {
            // Read back from the store, so already held there: nothing to write
            val durable = complete(fetch, it, fromNetwork = false)
            if (durable.isNotEmpty()) {
                locked { for (slot in durable) { persisting.remove(slot); durableDone += slot } }
                bump()
            }
            return
        }
        val offset = fetch.startOffset
        val length = fetch.endOffset - offset
        var attempt = 0
        while (true) {
            val bytes = try {
                withContext(RequestOrder(fetch.order)) { readRange(offset, length, fetch.priority) }
            } catch (e: CancellationException) {
                // Releasing the slots and waking the waiters is the worker's job; see abandon.
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt < MAX_ATTEMPTS) continue
                // The link refresh a 403 needs and the move off a dead host both happen inside
                // RangeReader, below this loop; what is left here is where even that did not help.
                fail(fetch, e)
                return
            }
            val durable = complete(fetch, bytes, fromNetwork = true)
            offerToStore(fetch, bytes, skip = durable)
            if (durable.isNotEmpty()) {
                // On the worker, not queued: a download that outruns the disk has to slow down,
                // and a durable block may not be dropped the way an offer may
                val store = durableStore()
                for (slot in durable) persist(store, slot, blockOf(fetch, slot, bytes) ?: continue)
            }
            return
        }
    }

    private fun blockOf(fetch: Fetch, slot: Int, bytes: ByteArray): ByteArray? {
        val from = ((slot - fetch.firstSlot) * blockSize).toInt()
        if (from >= bytes.size) return null
        return bytes.copyOfRange(from, minOf(bytes.size, from + blockSize.toInt()))
    }

    private suspend fun readRange(offset: Long, length: Long, priority: Int): ByteArray {
        val bytes = source.readBytes(offset, length, priority)
        if (bytes.size.toLong() != length) {
            // Not a transport hiccup: RangeReader resumes those itself. It means the remote
            // resource is shorter than the recorded size, i.e. the wrong bytes.
            throw PikPakException(-1, "stream: got ${bytes.size} of $length bytes at offset $offset")
        }
        return bytes
    }

    /** The whole fetch from [store], or null if any block of it is missing there. */
    private suspend fun stored(fetch: Fetch): ByteArray? {
        val store = store ?: return null
        val out = ByteArray((fetch.endOffset - fetch.startOffset).toInt())
        for (slot in fetch.slots) {
            val offset = slot.toLong() * blockSize
            val length = minOf(blockSize, size - offset).toInt()
            val bytes = try {
                store.read(storeKey, offset, length)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (bytes == null || bytes.size != length) return null
            bytes.copyInto(out, (offset - fetch.startOffset).toInt())
        }
        return out
    }

    // Queued rather than awaited: the readers waiting on these bytes have them already. A full
    // queue drops the offer, which the store's contract allows.
    private suspend fun offerToStore(fetch: Fetch, bytes: ByteArray, skip: Set<Int>) {
        val queue = storeWrites ?: return
        for (slot in fetch.slots) {
            if (slot in skip) continue
            val block = blockOf(fetch, slot, bytes) ?: break
            // Recorded before the send, so the writer can never forget an offer ahead of this
            locked { offered[slot] = block }
            if (!queue.trySend(StoreWrite(slot, slot.toLong() * blockSize, block)).isSuccess) {
                locked { forgetOffer(slot, block) }
            }
        }
    }

    /** Drops [slot]'s offer if it is still [bytes]: a later fetch of the slot may have replaced it. */
    private fun forgetOffer(slot: Int, bytes: ByteArray) {
        if (offered[slot] === bytes) offered.remove(slot)
    }

    /**
     * Publishes a fetch's bytes and returns the slots a durable warm is waiting on, which the
     * caller must now see written; they are marked persisting here, under the same lock that
     * takes them out of `wanted`, so a durable warm never finds a slot in neither.
     */
    private suspend fun complete(fetch: Fetch, bytes: ByteArray, fromNetwork: Boolean): Set<Int> {
        val durable = HashSet<Int>()
        mutex.withLock {
            val windows = liveCursors().map(::windowOf)
            for (slot in fetch.slots) {
                if (inFlight[slot] === fetch) inFlight.remove(slot)
                val claims = wanted.remove(slot)
                failed.remove(slot)
                val block = blockOf(fetch, slot, bytes) ?: continue
                if (claims != null && claims.any { it.durable }) {
                    durable += slot
                    persisting += slot
                }
                // A block only a download wants goes to the store and not into memory, where it
                // would only push out what the streams read from
                val downloadOnly = claims != null && claims.all { it.durable } && windows.none { slot in it }
                if (!downloadOnly) put(slot, block)
            }
            if (fromNetwork) deliveredBytes += bytes.size.toLong()
        }
        fetch.done.complete(Unit)
        bump()
        return durable
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
            val windows = liveCursors().map(::windowOf)
            inFlight.values.distinct()
                .filter { fetch -> !fetch.cancelled && fetch.slots.none { it in wanted || windows.any { window -> it in window } } }
                .onEach { it.cancelled = true }
        }
        for (fetch in victims) fetch.job?.cancel()
    }

    /**
     * Gives up a claimed fetch that will never deliver bytes.
     *
     * Idempotent, and safe to run after [complete] or [fail]: the slot removal only touches
     * slots this fetch still owns, and `completeExceptionally` returns false once the deferred
     * is settled. That is what lets it run on every exit path of a claimed fetch, which is the
     * only way to cover a fetch whose body never ran at all.
     */
    private suspend fun abandon(fetch: Fetch) {
        mutex.withLock { releaseSlotsLocked(fetch) }
        val abandoned = fetch.done.completeExceptionally(
            CancellationException("fetch at ${fetch.startOffset} was abandoned"),
        )
        if (abandoned) bump()
    }

    /** Must be called under [mutex]. */
    private fun releaseSlotsLocked(fetch: Fetch) {
        for (slot in fetch.slots) {
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
    private suspend fun <T> locked(body: () -> T): T = mutex.withLock { body() }

    private fun bump() {
        revision.update { it + 1 }
    }

    private fun checkOpen() {
        if (closed) throw PikPakException(-1, "PikPakStreamReader is closed")
    }

    private fun checkOpen(cursor: Cursor) {
        if (closed || cursor.closed) throw PikPakException(-1, "PikPakStreamReader is closed")
    }

    internal suspend fun cachedBytesForTest(): Long = locked { cachedBytes }

    internal suspend fun inFlightSlotsForTest(): Int = locked { inFlight.size }

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

    internal suspend fun claimForTest(): Fetch? = locked { claimNext() }

    internal suspend fun abandonForTest(fetch: Fetch): Unit = abandon(fetch)

    internal companion object {
        /** Attempts per block. RangeReader already retries transport failures and refreshes expired links. */
        const val MAX_ATTEMPTS = 3

        /** Blocks that may wait for the store at once: 4 MiB beyond the cap at most. */
        const val STORE_QUEUE_BLOCKS = 16

        /**
         * How long a foreground cursor that stopped reading still counts as foreground demand.
         * Long enough to ride out the gaps of a player reading steadily; short enough that a
         * paused one, or one sitting on a full buffer, gives the account back within seconds.
         */
        val FOREGROUND_IDLE: Duration = 5.seconds
    }
}
