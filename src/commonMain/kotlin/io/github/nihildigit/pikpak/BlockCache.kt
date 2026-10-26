package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import io.github.nihildigit.pikpak.internal.RequestOrder
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransferScheduler
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
    private val mediaBytesPerSecond: Long? = null,
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
    private val activeFetches = HashSet<Fetch>()
    private var pendingPersistBytes = 0L
    private var hungry = false
    private var wasAccountHungry = false
    private val registration by lazy { scheduler?.register(concurrency, blockSize, ::bump) }

    /**
     * Slots warms asked for and not cached yet, with every warm still asking. A list rather
     * than the strongest request alone, so a warm withdrawn by its caller takes only its own
     * claim with it.
     */
    private val wanted = LinkedHashMap<Int, MutableList<Want>>()

    /** Warms still waiting on some of their slots, in the order they were made; see [claimNext]. */
    private val wants = mutableListOf<Want>()

    /** Durable slots whose bytes are in hand and on their way into the store. */
    private val persisting = HashMap<Int, PersistTask>()

    private class PersistTask(val slot: Int, var bytes: ByteArray?, val accounted: Boolean) {
        val done = CompletableDeferred<Unit>()
    }

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
        internal val rateStarted = TimeSource.Monotonic.markNow()
        internal var bytesRead = 0L
        internal var hungry = false

        /** When this cursor last read or moved; see [foregroundIdle]. */
        @Volatile
        internal var lastActive: TimeMark = TimeSource.Monotonic.markNow()

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
        // Retain standalone headroom for two-slot fetches. Adaptive claims additionally
        // calculate their complete reservation before evicting any cached bytes.
        val headroom = (2 * concurrency + 1) * blockSize
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
                for (write in storeWrites) {
                    try {
                        store.write(storeKey, write.offset, write.bytes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                    locked { forgetOffer(write.slot, write.bytes); bump() }
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
        val data = try {
            awaitSlot(cursor, slot)
        } finally {
            cursor.reading = false
            cursor.lastActive = TimeSource.Monotonic.markNow()
            readsEnded.update { it + 1 }
            syncForegroundCount()
            if (cursor.readAhead == 0L) bump()
        }
        if (cursor.seeking) {
            cursor.seeking = false
            bump()
        }
        val within = (pos - slot.toLong() * blockSize).toInt()
        val available = minOf(minOf(data.size - within, length).toLong(), size - pos).toInt()
        data.copyInto(buffer, offset, within, within + available)
        cursor.position = pos + available
        cursor.bytesRead += available
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
        val fromMemory = mutableListOf<PersistTask>()
        val fromOffers = mutableListOf<Int>()
        locked {
            val pending = ArrayList<Int>(slots.size)
            for (slot in slots) {
                if (durable) durableDone.remove(slot)
                if (durable && slot in persisting) continue
                val cached = cache[slot]
                if (durable && cached == null && slot in offered) fromOffers += slot
                if (cached != null) {
                    if (durable) {
                        if (slot !in persisting) {
                            val task = PersistTask(slot, cached, accounted = slot !in cache)
                            if (task.accounted) pendingPersistBytes += cached.size
                            persisting[slot] = task
                            fromMemory += task
                        }
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
    private suspend fun persist(store: DurableBlockStore, task: PersistTask) {
        val bytes = task.bytes ?: return
        if (task.done.isCompleted) return
        var failure: Throwable? = null
        try {
            // Independent of a cancelled caller/fetch, but still cancelled by cache.close().
            val job = scope.async { store.write(storeKey, task.slot.toLong() * blockSize, bytes) }
            withContext(NonCancellable) { job.await() }
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
                PersistTask(slot, bytes, accounted).also {
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
                    for (slot in fetch.nextSlot + 1 until fetch.endSlot) if (inFlight[slot] === fetch) inFlight.remove(slot)
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
    private suspend fun awaitSlot(cursor: Cursor, slot: Int): ByteArray {
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
                hit(slot)
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

        @Volatile var nextSlot = firstSlot
        @Volatile var endSlot = firstSlot + slotCount
        @Volatile var waitingForLimiter = false
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
                if (slot in cache || slot in inFlight || slot in failed || slot in persisting) continue
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
        if (target == -1) return null
        val owner = targetCursor
        val blocking = owner != null && target == slotOf(owner.position)
        val downloadWant = targetWant?.takeIf { owner == null && it.durable }
        val mayWiden = if (scheduler != null) {
            (owner != null && !blocking && (!owner.seeking || wide(owner))) || (downloadWant != null && !wasAccountHungry)
        } else {
            !throttled() && ((owner != null && wide(owner)) || (downloadWant != null && !downloadsThrottled()))
        }
        val spanWorkers = if (downloadWant != null) minOf(durableLimit, localLimit) else activeWorkers()
        val maxSpan = if (mayWiden) registration?.let { scheduler!!.spanFor(it, spanWorkers, downloadWant != null) } ?: 2 else 1
        val windows = live.map(::windowOf)
        val outside = cache.keys.filter { slot -> slot !in persisting && windows.none { slot in it } }
        val baseFree = memoryCapBytes - cachedBytes - inFlightBytes() - pendingPersistBytes
        val outsideBytes = outside.sumOf { cache.getValue(it).size.toLong() }
        var slotCount = 0
        var bytes = 0L
        for (k in 1..maxSpan) {
            val slot = target + k - 1
            if (slot.toLong() * blockSize >= size || slot in cache || slot in inFlight || slot in failed || slot in persisting) break
            if (owner != null && slot !in windowOf(owner)) break
            if (owner == null && wanted[slot]?.contains(targetWant) != true) break
            bytes += minOf(blockSize, size - slot.toLong() * blockSize)
            val inside = if (k == 1 && blocking) cache.keys.filter { it !in outside && it !in persisting && live.none { c -> slotOf(c.position) == it } }.sumOf { cache.getValue(it).size.toLong() } else 0L
            if (bytes > baseFree + outsideBytes + inside) break
            slotCount = k
        }
        if (slotCount == 0) return null
        val needed = minOf(size, (target + slotCount).toLong() * blockSize) - target.toLong() * blockSize
        while (cachedBytes + inFlightBytes() + pendingPersistBytes + needed > memoryCapBytes) {
            val victim = cache.keys.firstOrNull { it in outside }
                ?: (if (slotCount == 1 && blocking) neededLast(live) else null)
                ?: return null
            val removed = cache.remove(victim)!!.size.toLong()
            cachedBytes -= removed
            if (unreadSlots.remove(victim)) wastedBytes += removed
        }
        val fetch = Fetch(target, slotCount, targetPriority, targetOrder, durable = downloadWant != null)
        for (slot in fetch.slots) inFlight[slot] = fetch
        activeFetches += fetch
        if (owner != null && blocking && owner.seeking && owner.headClaimedAt == null) {
            owner.headClaimedAt = TimeSource.Monotonic.markNow()
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

    private fun inFlightBytes(): Long = inFlight.keys.sumOf { minOf(blockSize, size - it.toLong() * blockSize) }

    private suspend fun runFetch(fetch: Fetch) {
        val tasks = mutableListOf<PersistTask>()
        try {
            while (fetch.nextSlot < fetch.endSlot) {
                val slot = fetch.nextSlot
                val offset = slot.toLong() * blockSize
                val length = minOf(blockSize, size - offset).toInt()
                val bytes = try { store?.read(storeKey, offset, length) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Throwable) { null }
                if (bytes == null || bytes.size != length) break
                publish(fetch, slot, bytes, fromNetwork = false, tasks = tasks)
            }
            var attempts = 0
            while (fetch.nextSlot < fetch.endSlot) {
                val offset = fetch.nextSlot.toLong() * blockSize
                val length = fetch.endOffset - offset
                val telemetry = ReadTelemetry()
                var completed = false
                try {
                    val limited = locked {
                        val windows = liveCursors().map(::windowOf)
                        val bytes = fetch.slots.filter { slot ->
                            wanted[slot]?.all { it.durable } == true && windows.none { slot in it }
                        }.sumOf { minOf(blockSize, size - it.toLong() * blockSize) }
                        fetch.waitingForLimiter = bytes > 0 && downloadLimiter != null
                        bytes
                    }
                    try { downloadLimiter?.acquire(limited) }
                    finally { withContext(NonCancellable) { locked { fetch.waitingForLimiter = false } } }
                    withContext(RequestOrder(fetch.order) + telemetry) {
                        source.read(offset, length, fetch.priority) { channel ->
                            while (fetch.nextSlot < fetch.endSlot) {
                                val slot = fetch.nextSlot
                                val expected = minOf(blockSize, size - slot.toLong() * blockSize).toInt()
                                val bytes = channel.readFully(expected)
                                if (bytes.size != expected) throw PikPakException(-1,
                                    "stream: got ${bytes.size} of $expected bytes at offset ${slot.toLong() * blockSize}")
                                publish(fetch, slot, bytes, fromNetwork = true, tasks = tasks)
                            }
                        }
                    }
                    completed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    attempts++
                    if (attempts >= MAX_ATTEMPTS) { fail(fetch, e); return }
                } finally {
                    registration?.let { scheduler!!.recordSample(it, telemetry, length,
                        !completed || fetch.endOffset < offset + length) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                for (task in tasks) persist(durableStore(), task)
                abandon(fetch)
            }
        }
    }

    private suspend fun publish(fetch: Fetch, slot: Int, bytes: ByteArray, fromNetwork: Boolean,
                                tasks: MutableList<PersistTask>) {
        locked {
            if (inFlight[slot] !== fetch) return@locked
            inFlight.remove(slot)
            val claims = wanted.remove(slot)
            failed.remove(slot)
            val windows = liveCursors().map(::windowOf)
            val downloadOnly = claims != null && claims.all { it.durable } && windows.none { slot in it }
            if (!downloadOnly) put(slot, bytes)
            val durable = claims?.any { it.durable } == true
            if (durable && !fromNetwork) durableDone += slot
            else if (durable && slot !in persisting) {
                val task = PersistTask(slot, bytes, accounted = downloadOnly)
                persisting[slot] = task
                offered[slot] = bytes
                if (downloadOnly) pendingPersistBytes += bytes.size
                tasks += task
            }
            if (fromNetwork) {
                deliveredBytes += bytes.size
                if (!durable && storeWrites != null) {
                    offered[slot] = bytes
                    if (!storeWrites.trySend(StoreWrite(slot, slot.toLong() * blockSize, bytes)).isSuccess) forgetOffer(slot, bytes)
                }
            }
            fetch.nextSlot = slot + 1
            bump()
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
            fetch.done.complete(Unit)
            bump()
        }
    }

    /** Must be called under [mutex]. */
    private fun releaseSlotsLocked(fetch: Fetch) {
        val owned = inFlight.filterValues { it === fetch }.keys.toList()
        for (slot in owned) inFlight.remove(slot)
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

    internal suspend fun accountedBytesForTest(): Long = locked { cachedBytes + inFlightBytes() + pendingPersistBytes }

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
