package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AdaptiveRangeSource
import io.github.nihildigit.pikpak.internal.DemandWithdrawnException
import io.github.nihildigit.pikpak.internal.ForegroundStreams
import io.github.nihildigit.pikpak.internal.LiveSignedPriority
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.RecoveryPolicy
import io.github.nihildigit.pikpak.internal.RequestOrder
import io.github.nihildigit.pikpak.internal.TransferPreempted
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
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The bytes of one remote file, fetched on demand and shared by every reader, prefetch, and
 * durable download over it.
 *
 * All byte scheduling flows through a single pure [planLocked] projection over five explicit
 * claim kinds ([ByteClaim.Required], [ByteClaim.Continuation], [ByteClaim.LookAhead],
 * [ByteClaim.FinishInFlight], and [ByteClaim.StoreRange]) derived from per-consumer
 * [TransferDemand] snapshots, open [ReadTicket]s, active [ReadWaiter]s, per-`(owner, epoch)`
 * [ReadLane] read evidence, and active warms/downloads.
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
    val blockSize: Long = PikPakStreamReader.DEFAULT_BLOCK_SIZE,
    /** Hard per-cache ceiling on a consumer's speculative read-ahead window. */
    val readAheadBytes: Long = PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES,
    private val memoryCapBytes: Long = PikPakStreamReader.DEFAULT_MEMORY_CAP_BYTES,
    private val wideBlockThresholdBytes: Long = PikPakStreamReader.DEFAULT_WIDE_BLOCK_THRESHOLD_BYTES,
    /** How long a foreground owner keeps counting as foreground demand after its last read. */
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
    internal sealed interface ByteClaim {
        val priority: Int
        val order: Long

        data class Required(
            val owner: TransferDemand,
            val epoch: Long,
            val slot: Int,
            val endByteExclusive: Long,
            val foreground: Boolean,
            override val priority: Int,
            override val order: Long,
        ) : ByteClaim

        data class Continuation(
            val owner: TransferDemand,
            val epoch: Long,
            val slot: Int,
            val fetch: Fetch,
            val exchangeId: Long,
            val foreground: Boolean,
            override val priority: Int,
            override val order: Long,
        ) : ByteClaim

        data class LookAhead(
            val owner: TransferDemand,
            val epoch: Long,
            val activeHeadOffset: Long,
            val firstSlot: Int,
            val lastSlotInclusive: Int,
            val nearBoundarySlot: Int,
            val nearReady: Boolean,
            val foreground: Boolean,
            override val priority: Int,
            val farPriority: Int,
            override val order: Long,
        ) : ByteClaim {
            fun containsSlot(slot: Int): Boolean = slot in firstSlot..lastSlotInclusive
            fun priorityForSlot(slot: Int): Int =
                if (foreground && nearReady && slot >= nearBoundarySlot) farPriority else priority
        }

        data class FinishInFlight(
            val owner: TransferDemand,
            val epoch: Long,
            val slot: Int,
            override val order: Long,
        ) : ByteClaim {
            override val priority: Int get() = FINISH_IN_FLIGHT_PRIORITY
        }

        data class StoreRange(
            val durable: Boolean,
            val slots: SlotRanges,
            override val priority: Int,
            override val order: Long,
            val owner: TransferDemand?,
        ) : ByteClaim
    }

    internal class PlanSnapshot(
        val required: List<ByteClaim.Required>,
        val continuation: List<ByteClaim.Continuation>,
        val lookAhead: List<ByteClaim.LookAhead>,
        val finishInFlight: List<ByteClaim.FinishInFlight>,
        val storeWants: List<Want>,
    ) {
        fun storeRangeClaims(): List<ByteClaim.StoreRange> = storeWants.map { want ->
            ByteClaim.StoreRange(
                durable = want.durable,
                slots = want.slots,
                priority = want.priority,
                order = want.order,
                owner = want.demand,
            )
        }
    }

    internal class ReadWaiter(
        val slot: Int,
        val endByteExclusive: Long,
        val readEpoch: Long,
        @Volatile var waitingWithin: Int = -1,
    )

    internal class ReadLane(
        val epoch: Long,
        var speculationStamp: Long,
        val anchorOffset: Long,
        var frontierOffset: Long,
        var lastOrder: Long,
    ) {
        var contiguousBytesRead: Long = 0L
        var crossedBlockBoundary: Boolean = false
    }

    private class FinishInFlightEntry(
        val owner: TransferDemand,
        val epoch: Long,
        val speculationStamp: Long,
        val slot: Int,
        val fetch: Fetch,
        val order: Long,
    )

    private inner class OwnerState(val owner: TransferDemand) {
        var observedEpoch: Long = owner.intent.epoch
        var latestNativeTicketEpoch: Long = Long.MIN_VALUE
        val lanes = ArrayList<ReadLane>(4)
        val finishInFlightBySlot = LinkedHashMap<Int, FinishInFlightEntry>()
        @Volatile var lastForegroundActiveAt: TimeMark? = null
        val listenerHandle: AutoCloseable = owner.addListener { onOwnerChanged() }

        fun dispose() {
            listenerHandle.close()
            lanes.clear()
            finishInFlightBySlot.clear()
            lastForegroundActiveAt = null
        }
    }

    internal inner class TicketHandle(
        override val owner: TransferDemand,
        override val epoch: Long,
        override val startOffset: Long,
        override val endExclusive: Long,
        initialRoleOverride: StreamRole?,
    ) : ReadTicket {
        val effectiveEnd: Long = minOf(endExclusive, size).coerceAtLeast(startOffset.coerceAtMost(size))
        val order: Long = RequestOrder.Sequence.next()
        internal val activeReadGuard = MutableStateFlow(false)
        var registered: Boolean = false
            internal set
        var lane: ReadLane? = null
            internal set
        @Volatile override var position: Long = startOffset
            internal set
        @Volatile var roleOverride: StreamRole? = initialRoleOverride
            internal set
        @Volatile var activeWaiter: ReadWaiter? = null
            internal set
        @Volatile var continuationSlot: Int = -1
            internal set
        @Volatile var bytesDelivered: Long = 0L
            internal set
        @Volatile var lastReadEpoch: Long = Long.MIN_VALUE
            internal set
        @Volatile var lastReadSpeculationStamp: Long = Long.MIN_VALUE
            internal set
        var postEpochObserved: Long = epoch
            internal set
        var postEpochAnchorOffset: Long = startOffset
            internal set
        var postEpochFrontierOffset: Long = startOffset
            internal set
        var postEpochContiguousBytes: Long = 0L
            internal set
        @Volatile var ended: Boolean = false
            private set
        val closing = CompletableDeferred<Unit>()

        val effectiveRole: StreamRole
            get() {
                val override = roleOverride
                if (override == StreamRole.BACKGROUND) {
                    return if (owner.priority >= PikPakStreamReader.BLOCKING_PRIORITY) StreamRole.FOREGROUND else StreamRole.BACKGROUND
                }
                if (override == StreamRole.FOREGROUND) return StreamRole.FOREGROUND
                return if (owner.priority >= PikPakStreamReader.STREAMING_PRIORITY) StreamRole.FOREGROUND else StreamRole.BACKGROUND
            }

        override suspend fun readSome(buffer: ByteArray, offset: Int, length: Int): Int =
            this@BlockCache.readTicketSome(this, buffer, offset, length)

        override fun endRead(reason: ReadEndReason) {
            if (ended) return
            ended = true
            activeWaiter = null
            continuationSlot = -1
            closing.complete(Unit)
            if (closed) return
            val allowFinishInFlight = reason.allowsFinishInFlight
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var toCancelWithdrawn: List<Fetch>? = null
                var toCancelPreempted: List<Fetch>? = null
                locked {
                    openTickets.remove(this@TicketHandle)
                    val state = owners[owner]
                    if (state != null) {
                        syncOwnerEpochLocked(state)
                        val curEpoch = owner.intent.epoch
                        val canSeedFif = (epoch == curEpoch || lane?.epoch == curEpoch) && lastReadEpoch == curEpoch
                        if (allowFinishInFlight &&
                            !closed &&
                            !owner.isClosed &&
                            bytesDelivered > 0L &&
                            canSeedFif &&
                            lastReadSpeculationStamp == owner.speculationRevision &&
                            owner.intent.service != ServiceIntent.PAUSED &&
                            position > 0L
                        ) {
                            val lastConsumedSlot = slotOf(position - 1L)
                            val fetch = inFlight[lastConsumedSlot]
                            if (isFinishInFlightEligibleLocked(lastConsumedSlot, fetch)) {
                                upsertFinishInFlightLocked(
                                    state,
                                    FinishInFlightEntry(
                                        owner = owner,
                                        epoch = curEpoch,
                                        speculationStamp = owner.speculationRevision,
                                        slot = lastConsumedSlot,
                                        fetch = fetch!!,
                                        order = order,
                                    ),
                                )
                            } else {
                                state.finishInFlightBySlot.remove(lastConsumedSlot)
                            }
                        } else if (!allowFinishInFlight && size > 0L) {
                            if (state.finishInFlightBySlot.isNotEmpty()) {
                                val targetEpoch = if (lastReadEpoch >= 0L) lastReadEpoch else epoch
                                val firstTouched = slotOf(startOffset.coerceIn(0L, size - 1L))
                                val lastTouched = slotOf((position - 1L).coerceAtLeast(startOffset).coerceIn(0L, size - 1L))
                                val iter = state.finishInFlightBySlot.entries.iterator()
                                while (iter.hasNext()) {
                                    val entry = iter.next()
                                    if ((entry.value.order == order || bytesDelivered > 0L) &&
                                        entry.value.epoch == targetEpoch &&
                                        entry.key in firstTouched..lastTouched
                                    ) {
                                        iter.remove()
                                    }
                                }
                            }
                        }
                        pruneOwnerIfIdleLocked(state)
                    }
                    val actions = reconcileClaimsLocked()
                    toCancelWithdrawn = actions.withdrawn
                    toCancelPreempted = actions.preempted
                }
                cancelFetches(toCancelWithdrawn, toCancelPreempted)
                syncForegroundCount()
                idleSignal.bump()
                bump()
            }
        }
    }

    private data class ReconcileActions(
        val withdrawn: List<Fetch>? = null,
        val preempted: List<Fetch>? = null,
    )

    private val adaptiveSource = source as? AdaptiveRangeSource
    private val memoryBudget = memoryBudget ?: TransportMemoryBudget(memoryCapBytes)
    private val owned = HashMap<ByteArray, Long>()
    private var ownedBytes = 0L
    // Guarded by mutex. Additions and LRU hits cannot orphan an owned array.
    private var ownershipDirty = false
    private var ownershipSweeps = 0L
    private var recoveryRevisionSeq = 0L
    private var completedMultiBlockFetches = 0L
    @Volatile private var recentTtfbMillis = 1_100L
    private val durableWrites = Channel<PersistTask>(1)
    private val owners = LinkedHashMap<TransferDemand, OwnerState>()
    private val openTickets = ArrayList<TicketHandle>()

    private val reclaim: () -> Unit = {
        scope.launch {
            locked {
                val protected = HashSet<Int>()
                for (i in 0 until openTickets.size) {
                    val t = openTickets[i]
                    val waiterSlot = t.activeWaiter?.slot
                    val contSlot = t.continuationSlot.takeIf {
                        !t.ended && !t.owner.isClosed && it >= 0 && t.position < t.effectiveEnd && slotOf(t.position) == it
                    }
                    if (waiterSlot != null) protected.add(waiterSlot)
                    if (contSlot != null) protected.add(contSlot)
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

    private val activeFetches = ArrayList<Fetch>()
    private var pendingPersistBytes = 0L
    private val registration by lazy { scheduler?.register(concurrency, blockSize, ::bump) }

    private val wanted = Wanted()

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

    private val storeWrites = store?.let { Channel<StoreWrite>(STORE_QUEUE_BLOCKS) }

    private val offered = HashMap<Int, ByteArray>()
    private val optionalWrites = HashMap<Int, StoreWrite>()

    private val failed = HashMap<Int, Throwable>()

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

    private val revision = RevisionSignal()
    private val readSignal = RevisionSignal()
    private val idleSignal = RevisionSignal()

    @Volatile
    var closed = false
        private set

    private val counted = MutableStateFlow(false)

    @Volatile
    var wastedBytes: Long = 0
        private set

    @Volatile
    var deliveredBytes: Long = 0
        private set

    @Volatile
    var receivedBytes: Long = 0
        private set

    internal class SlotBits {
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

    internal class Want(
        val basePriority: Int,
        val order: Long,
        val durable: Boolean,
        val slots: SlotRanges,
        val role: StreamRole,
        val demand: TransferDemand? = null,
    ) {
        val priority: Int
            get() = demand?.priority?.let {
                if (it == PikPakStreamReader.WARM_PRIORITY && basePriority < it) basePriority else it
            } ?: basePriority
        val completed = SlotBits()
        var next = 0
    }

    private fun wantsSlot(want: Want, slot: Int): Boolean =
        (want.demand == null || !want.demand.isClosed) &&
            slot !in want.completed &&
            (!want.durable || slot !in durableDone) &&
            slot in want.slots

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

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, got $concurrency" }
        require(readAheadConcurrency >= 1) { "readAheadConcurrency must be >= 1, got $readAheadConcurrency" }
        require(size >= 0) { "size must be >= 0, got $size" }
        require(blockSize in 1..Int.MAX_VALUE.toLong())
        require(size == 0L || (size - 1) / blockSize < Int.MAX_VALUE)
        require(mediaBytesPerSecond == null || mediaBytesPerSecond!! > 0)
        val headroom = (2L * concurrency + 1) * blockSize
        require(memoryCapBytes >= readAheadBytes + headroom) {
            "memoryCapBytes ($memoryCapBytes) must clear readAheadBytes ($readAheadBytes) by $headroom"
        }
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
                storeWrites?.cancel()
                locked {
                    ownershipDirty = true
                    for (state in owners.values) state.dispose()
                    owners.clear()
                    openTickets.clear()
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
        if (adaptiveSource != null || scheduler != null || foregroundStreams != null) {
            scope.launch {
                revision.flow.collect {
                    var withdrawn: List<Fetch>? = null
                    var preempted: List<Fetch>? = null
                    locked {
                        val actions = reconcileClaimsLocked()
                        withdrawn = actions.withdrawn
                        preempted = actions.preempted
                    }
                    cancelFetches(withdrawn, preempted)
                }
            }
        }
        if (adaptiveSource != null) {
            scope.launch {
                adaptiveSource.connectionLimit.collect { workerLimitChanged() }
            }
        }
        if (foregroundStreams != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                foregroundStreams.active.collect { activeCount ->
                    if (activeCount > 0 && !closed) {
                        var withdrawn: List<Fetch>? = null
                        var preempted: List<Fetch>? = null
                        locked {
                            val actions = reconcileClaimsLocked()
                            withdrawn = actions.withdrawn
                            preempted = actions.preempted
                        }
                        cancelFetches(withdrawn, preempted)
                    }
                    bump()
                }
            }
            scope.launch { watchForegroundIdle() }
        }
        repeat(concurrency) { index -> scope.launch { runWorker(index) } }
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
    // tickets & owner demand
    ///////////////////////////////////////////////////////////////////////////

    fun beginRead(
        owner: TransferDemand,
        startOffset: Long,
        endExclusive: Long = size,
    ): ReadTicket = beginReadInternal(owner, startOffset, endExclusive, initialRoleOverride = null)

    internal fun beginReadInternal(
        owner: TransferDemand,
        startOffset: Long,
        endExclusive: Long = size,
        initialRoleOverride: StreamRole? = null,
        eagerRegister: Boolean = false,
    ): TicketHandle {
        checkOpen()
        require(startOffset >= 0L) { "startOffset must be >= 0, got $startOffset" }
        require(endExclusive >= startOffset) { "endExclusive ($endExclusive) must be >= startOffset ($startOffset)" }
        val ticket = TicketHandle(
            owner = owner,
            epoch = owner.intent.epoch,
            startOffset = startOffset,
            endExclusive = endExclusive,
            initialRoleOverride = initialRoleOverride,
        )
        if (eagerRegister) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var withdrawn: List<Fetch>? = null
                var preempted: List<Fetch>? = null
                locked {
                    if (!closed && !ticket.ended) {
                        ensureTicketRegisteredLocked(ticket)
                        if (ticket.effectiveRole == StreamRole.FOREGROUND &&
                            !ticket.owner.isClosed &&
                            ticket.epoch == ticket.owner.intent.epoch
                        ) {
                            owners[ticket.owner]?.lastForegroundActiveAt = timeSource.markNow()
                        }
                    }
                    val actions = reconcileClaimsLocked()
                    withdrawn = actions.withdrawn
                    preempted = actions.preempted
                }
                cancelFetches(withdrawn, preempted)
                syncForegroundCount()
                idleSignal.bump()
                bump()
            }
        }
        return ticket
    }

    private fun ensureTicketRegisteredLocked(ticket: TicketHandle): Boolean {
        if (ticket.registered || ticket.ended || closed) return false
        val state = getOrCreateOwnerLocked(ticket.owner)
        syncOwnerEpochLocked(state)
        if (ticket.owner.isClosed) {
            pruneOwnerIfIdleLocked(state)
            return false
        }
        if (ticket.epoch == state.observedEpoch) {
            if (ticket.epoch > state.latestNativeTicketEpoch) {
                state.latestNativeTicketEpoch = ticket.epoch
            }
            val clampedStart = ticket.startOffset.coerceAtMost(size)
            ticket.lane = attachOrCreateLaneLocked(state, ticket.epoch, clampedStart, ticket.order)
            ticket.registered = true
            openTickets.add(ticket)
            return detachSupersededEpochLanesLocked(state)
        } else {
            ticket.lane = null
        }
        ticket.registered = true
        openTickets.add(ticket)
        return false
    }

    private fun isSupersededByCurrentEpochTicketLocked(
        state: OwnerState?,
        ticket: TicketHandle,
        curEpoch: Long,
    ): Boolean = ticket.epoch < curEpoch && state != null && state.latestNativeTicketEpoch >= curEpoch

    private fun detachSupersededEpochLanesLocked(state: OwnerState): Boolean {
        val curEpoch = state.observedEpoch
        if (state.latestNativeTicketEpoch < curEpoch) return false
        val owner = state.owner
        var detachedAny = false
        for (i in 0 until openTickets.size) {
            val t = openTickets[i]
            if (t.owner === owner && t.epoch < curEpoch && t.lane != null) {
                t.lane = null
                detachedAny = true
            }
        }
        if (detachedAny && state.lanes.isNotEmpty()) {
            val iter = state.lanes.iterator()
            while (iter.hasNext()) {
                val lane = iter.next()
                val referencedByCurrentEpochTicket = openTickets.any {
                    !it.ended && it.owner === owner && it.epoch >= curEpoch && it.lane === lane
                }
                if (!referencedByCurrentEpochTicket) {
                    iter.remove()
                }
            }
        }
        return detachedAny
    }

    internal fun markForegroundOwnerActive(owner: TransferDemand) {
        if (closed || owner.isClosed) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            locked {
                if (!closed && !owner.isClosed &&
                    owner.intent.service != ServiceIntent.PAUSED &&
                    owner.intent.service != ServiceIntent.PREFETCH
                ) {
                    val state = getOrCreateOwnerLocked(owner)
                    state.lastForegroundActiveAt = timeSource.markNow()
                }
            }
            syncForegroundCount()
            idleSignal.bump()
            bump()
        }
    }

    internal fun setTicketRole(ticket: TicketHandle, role: StreamRole) {
        if (ticket.roleOverride == role) return
        ticket.roleOverride = role
        if (!closed) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var withdrawn: List<Fetch>? = null
                var preempted: List<Fetch>? = null
                locked {
                    ensureTicketRegisteredLocked(ticket)
                    if (role == StreamRole.FOREGROUND && ticket.epoch == ticket.owner.intent.epoch && !ticket.owner.isClosed) {
                        owners[ticket.owner]?.lastForegroundActiveAt = timeSource.markNow()
                    }
                    val actions = reconcileClaimsLocked()
                    withdrawn = actions.withdrawn
                    preempted = actions.preempted
                }
                cancelFetches(withdrawn, preempted)
                syncForegroundCount()
                idleSignal.bump()
                bump()
            }
        } else {
            syncForegroundCount()
            idleSignal.bump()
            bump()
        }
    }

    private fun onOwnerChanged() {
        if (closed) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var withdrawn: List<Fetch>? = null
            var preempted: List<Fetch>? = null
            locked {
                for (state in owners.values.toList()) {
                    syncOwnerEpochLocked(state)
                    pruneOwnerIfIdleLocked(state)
                }
                val actions = reconcileClaimsLocked()
                withdrawn = actions.withdrawn
                preempted = actions.preempted
            }
            cancelFetches(withdrawn, preempted)
            syncForegroundCount()
            idleSignal.bump()
            bump()
        }
    }

    private fun getOrCreateOwnerLocked(owner: TransferDemand): OwnerState =
        owners.getOrPut(owner) { OwnerState(owner) }

    private fun syncOwnerEpochLocked(state: OwnerState) {
        val owner = state.owner
        if (owner.isClosed) {
            state.lanes.clear()
            state.finishInFlightBySlot.clear()
            state.lastForegroundActiveAt = null
            for (i in 0 until openTickets.size) {
                val t = openTickets[i]
                if (t.owner === owner && !t.ended) {
                    t.activeWaiter?.waitingWithin = -1
                    t.continuationSlot = -1
                    t.closing.complete(Unit)
                }
            }
            return
        }
        val intent = owner.intent
        if (intent.epoch != state.observedEpoch) {
            state.observedEpoch = intent.epoch
            state.lanes.clear()
            state.finishInFlightBySlot.clear()
            for (i in 0 until openTickets.size) {
                val t = openTickets[i]
                if (t.owner === owner) {
                    t.lane = null
                }
            }
        }
        if (intent.service == ServiceIntent.PAUSED || intent.service == ServiceIntent.PREFETCH) {
            state.lastForegroundActiveAt = null
        }
        if (intent.service == ServiceIntent.PAUSED) {
            state.finishInFlightBySlot.clear()
        } else if (state.finishInFlightBySlot.isNotEmpty()) {
            val iter = state.finishInFlightBySlot.values.iterator()
            while (iter.hasNext()) {
                val fif = iter.next()
                if (fif.epoch != intent.epoch ||
                    fif.speculationStamp != owner.speculationRevision ||
                    !isFinishInFlightEligibleLocked(fif.slot, fif.fetch)
                ) {
                    iter.remove()
                }
            }
        }
    }

    private fun upsertFinishInFlightLocked(state: OwnerState, entry: FinishInFlightEntry) {
        state.finishInFlightBySlot.remove(entry.slot)
        state.finishInFlightBySlot[entry.slot] = entry
        while (state.finishInFlightBySlot.size > MAX_LANES_PER_OWNER) {
            val oldestSlot = state.finishInFlightBySlot.keys.first()
            state.finishInFlightBySlot.remove(oldestSlot)
        }
    }

    private fun isFinishInFlightEligibleLocked(slot: Int, fetch: Fetch?): Boolean {
        if (fetch == null) return false
        val liveEx = fetch.claimControl.liveExchangeId
        return slot !in cache &&
            inFlight[slot] === fetch &&
            !fetch.cancelled &&
            !fetch.released &&
            fetch.nextSlot == slot &&
            fetch.networkStarted &&
            fetch.buffered > 0 &&
            liveEx > 0L &&
            fetch.lastByteExchangeId == liveEx
    }

    private fun isContinuationEligibleLocked(slot: Int, fetch: Fetch?): Boolean {
        if (fetch == null) return false
        val liveEx = fetch.claimControl.liveExchangeId
        return slot !in cache &&
            inFlight[slot] === fetch &&
            !fetch.cancelled &&
            !fetch.released &&
            fetch.nextSlot == slot &&
            fetch.networkStarted &&
            liveEx > 0L
    }

    private fun pruneOwnerIfIdleLocked(state: OwnerState) {
        val owner = state.owner
        val hasOpenTicket = openTickets.any { it.owner === owner && !it.ended }
        if (hasOpenTicket || state.finishInFlightBySlot.isNotEmpty()) return
        val recentFg = state.lastForegroundActiveAt?.let { it.elapsedNow() < foregroundIdle } == true
        if (owner.isClosed || (!recentFg && state.lanes.isEmpty())) {
            owners.remove(owner)?.dispose()
        }
    }

    private fun attachOrCreateLaneLocked(
        state: OwnerState,
        epoch: Long,
        clampedStart: Long,
        order: Long,
    ): ReadLane {
        val currentStamp = state.owner.speculationRevision
        var best: ReadLane? = null
        var bestDist = Long.MAX_VALUE
        for (i in 0 until state.lanes.size) {
            val candidate = state.lanes[i]
            if (candidate.epoch == epoch && clampedStart >= candidate.anchorOffset) {
                val dist = abs(clampedStart - candidate.frontierOffset)
                if (dist <= blockSize && dist < bestDist) {
                    best = candidate
                    bestDist = dist
                }
            }
        }
        if (best != null) {
            best.lastOrder = order
            best.speculationStamp = currentStamp
            if (clampedStart > best.frontierOffset) {
                best.frontierOffset = clampedStart
            }
            return best
        }
        if (state.lanes.size >= MAX_LANES_PER_OWNER) {
            val evictCandidate = state.lanes
                .filter { lane -> openTickets.none { !it.ended && it.lane === lane } }
                .minByOrNull { it.lastOrder }
                ?: state.lanes.minByOrNull { it.lastOrder }
            if (evictCandidate != null) state.lanes.remove(evictCandidate)
        }
        return ReadLane(
            epoch = epoch,
            speculationStamp = currentStamp,
            anchorOffset = clampedStart,
            frontierOffset = clampedStart,
            lastOrder = order,
        ).also { state.lanes.add(it) }
    }

    fun updateMediaBytesPerSecond(bytes: Long?) {
        require(bytes == null || bytes > 0) { "mediaBytesPerSecond must be positive" }
        mediaBytesPerSecond = bytes
        bump()
    }

    private suspend fun readTicketSome(
        ticket: TicketHandle,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (!ticket.activeReadGuard.compareAndSet(false, true)) {
            throw IllegalStateException("Concurrent readSome on the same ReadTicket is not supported")
        }
        try {
            checkTicketOpen(ticket)
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                "invalid buffer slice offset=$offset length=$length size=${buffer.size}"
            }
            val pos = ticket.position
            if (pos >= ticket.effectiveEnd) return -1
            if (length == 0) return 0
            val clampedLength = minOf(length.toLong(), ticket.effectiveEnd - pos).toInt()
            val slot = slotOf(pos)
            val endByte = minOf(ticket.effectiveEnd, (slot + 1L) * blockSize)
            lateinit var waiter: ReadWaiter
            var entryWithdrawn: List<Fetch>? = null
            var entryPreempted: List<Fetch>? = null
            locked {
                checkTicketOpen(ticket)
                val detachedSuperseded = ensureTicketRegisteredLocked(ticket)
                val state = owners[ticket.owner]
                if (state != null) {
                    syncOwnerEpochLocked(state)
                    val curEpoch = state.observedEpoch
                    if (isSupersededByCurrentEpochTicketLocked(state, ticket, curEpoch)) {
                        ticket.lane = null
                    } else if (ticket.epoch < curEpoch && ticket.lane == null) {
                        if (ticket.postEpochObserved != curEpoch) {
                            ticket.postEpochObserved = curEpoch
                            ticket.postEpochAnchorOffset = pos
                            ticket.postEpochFrontierOffset = pos
                            ticket.postEpochContiguousBytes = 0L
                        }
                    }
                    val lane = ticket.lane
                    if (lane != null &&
                        lane.epoch == curEpoch &&
                        lane.speculationStamp == ticket.owner.speculationRevision &&
                        lane.contiguousBytesRead > 0L &&
                        pos <= lane.frontierOffset &&
                        slot > slotOf(lane.anchorOffset)
                    ) {
                        lane.crossedBlockBoundary = true
                    }
                    if (ticket.effectiveRole == StreamRole.FOREGROUND &&
                        !ticket.owner.isClosed &&
                        (ticket.epoch == curEpoch || ticket.lane?.epoch == curEpoch) &&
                        ticket.owner.intent.service != ServiceIntent.PAUSED &&
                        ticket.owner.intent.service != ServiceIntent.PREFETCH
                    ) {
                        state.lastForegroundActiveAt = timeSource.markNow()
                    }
                    pruneOwnerIfIdleLocked(state)
                }
                waiter = ReadWaiter(
                    slot = slot,
                    endByteExclusive = endByte,
                    readEpoch = ticket.owner.intent.epoch,
                )
                ticket.activeWaiter = waiter
                if (detachedSuperseded) {
                    val actions = reconcileClaimsLocked()
                    entryWithdrawn = actions.withdrawn
                    entryPreempted = actions.preempted
                }
            }
            cancelFetches(entryWithdrawn, entryPreempted)
            syncForegroundCount()
            idleSignal.bump()
            bump()
            var available = 0
            var shouldBump = false
            var withdrawn: List<Fetch>? = null
            var preempted: List<Fetch>? = null
            try {
                available = awaitTicketSlot(
                    ticket = ticket,
                    waiter = waiter,
                    slot = slot,
                    within = (pos - slot.toLong() * blockSize).toInt(),
                    destination = buffer,
                    offset = offset,
                    length = clampedLength,
                )
            } finally {
                locked {
                    if (ticket.activeWaiter === waiter) {
                        ticket.activeWaiter = null
                    }
                    val state = owners[ticket.owner]
                    if (state != null) {
                        syncOwnerEpochLocked(state)
                    }
                    if (available > 0) {
                        val nextPos = pos + available
                        ticket.position = nextPos
                        ticket.bytesDelivered += available
                        val curEpoch = ticket.owner.intent.epoch
                        ticket.lastReadEpoch = curEpoch
                        ticket.lastReadSpeculationStamp = ticket.owner.speculationRevision

                        if (nextPos < ticket.effectiveEnd && slotOf(nextPos) == slot) {
                            ticket.continuationSlot = slot
                        } else {
                            ticket.continuationSlot = -1
                        }

                        if (state != null && !ticket.owner.isClosed) {
                            if (isSupersededByCurrentEpochTicketLocked(state, ticket, curEpoch)) {
                                ticket.lane = null
                            } else if (ticket.epoch < curEpoch && ticket.lane == null) {
                                if (ticket.postEpochObserved != curEpoch) {
                                    ticket.postEpochObserved = curEpoch
                                    ticket.postEpochAnchorOffset = pos
                                    ticket.postEpochFrontierOffset = pos
                                    ticket.postEpochContiguousBytes = 0L
                                }
                                if (nextPos > ticket.postEpochFrontierOffset) {
                                    val delta = nextPos - maxOf(pos, ticket.postEpochFrontierOffset)
                                    ticket.postEpochContiguousBytes += delta
                                    ticket.postEpochFrontierOffset = nextPos
                                }
                                val crossed = slotOf(nextPos - 1L) > slotOf(ticket.postEpochAnchorOffset)
                                if (crossed || ticket.postEpochContiguousBytes >= blockSize) {
                                    val reattached = attachOrCreateLaneLocked(state, curEpoch, ticket.postEpochAnchorOffset, ticket.order)
                                    reattached.frontierOffset = maxOf(reattached.frontierOffset, ticket.postEpochFrontierOffset)
                                    reattached.contiguousBytesRead = maxOf(reattached.contiguousBytesRead, ticket.postEpochContiguousBytes)
                                    if (crossed) reattached.crossedBlockBoundary = true
                                    ticket.lane = reattached
                                }
                            } else {
                                val lane = ticket.lane
                                if (lane != null && lane.epoch == curEpoch) {
                                    lane.speculationStamp = ticket.owner.speculationRevision
                                    if (nextPos > lane.frontierOffset) {
                                        val delta = nextPos - maxOf(pos, lane.frontierOffset)
                                        lane.contiguousBytesRead += delta
                                        lane.frontierOffset = nextPos
                                    }
                                    if (slotOf(nextPos - 1L) > slotOf(lane.anchorOffset)) {
                                        lane.crossedBlockBoundary = true
                                    }
                                }
                            }

                            val canSeedFif = ticket.epoch == curEpoch || ticket.lane?.epoch == curEpoch
                            if (canSeedFif &&
                                nextPos >= ticket.effectiveEnd &&
                                nextPos > 0L &&
                                (nextPos % blockSize) != 0L &&
                                ticket.owner.intent.service != ServiceIntent.PAUSED
                            ) {
                                val lastConsumedSlot = slotOf(nextPos - 1L)
                                val fetch = inFlight[lastConsumedSlot]
                                if (isFinishInFlightEligibleLocked(lastConsumedSlot, fetch)) {
                                    upsertFinishInFlightLocked(
                                        state,
                                        FinishInFlightEntry(
                                            owner = ticket.owner,
                                            epoch = curEpoch,
                                            speculationStamp = ticket.owner.speculationRevision,
                                            slot = lastConsumedSlot,
                                            fetch = fetch!!,
                                            order = ticket.order,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    if (state != null) {
                        if (ticket.effectiveRole == StreamRole.FOREGROUND &&
                            !ticket.owner.isClosed &&
                            (ticket.epoch == ticket.owner.intent.epoch || ticket.lane?.epoch == ticket.owner.intent.epoch) &&
                            ticket.owner.intent.service != ServiceIntent.PAUSED &&
                            ticket.owner.intent.service != ServiceIntent.PREFETCH
                        ) {
                            state.lastForegroundActiveAt = timeSource.markNow()
                        }
                        pruneOwnerIfIdleLocked(state)
                    }
                    val actions = reconcileClaimsLocked()
                    withdrawn = actions.withdrawn
                    preempted = actions.preempted
                    if (available > 0) {
                        val nextPos = ticket.position
                        val lane = ticket.lane
                        shouldBump = slotOf(nextPos.coerceAtMost((size - 1).coerceAtLeast(0L))) != slot ||
                            (lane != null && (lane.crossedBlockBoundary || lane.contiguousBytesRead >= blockSize)) ||
                            actions.withdrawn != null ||
                            actions.preempted != null ||
                            planHasUncachedLookAheadLocked(ticket.owner)
                    }
                }
                cancelFetches(withdrawn, preempted)
                syncForegroundCount()
                idleSignal.bump()
                if (shouldBump) bump()
            }
            return available
        } finally {
            ticket.activeReadGuard.value = false
        }
    }

    private fun planHasUncachedLookAheadLocked(owner: TransferDemand): Boolean {
        val plan = planLocked()
        for (i in 0 until plan.lookAhead.size) {
            val la = plan.lookAhead[i]
            if (la.owner === owner) {
                for (slot in la.firstSlot..la.lastSlotInclusive) {
                    if (slot !in cache && slot !in inFlight && slot !in offered && slot !in persisting && slot !in failed) {
                        if (isFarTailDeferredLocked(la, slot)) break
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun ticketClaimPriorityLocked(ticket: TicketHandle, intent: ConsumerIntent): Int {
        if (isSupersededByCurrentEpochTicketLocked(owners[ticket.owner], ticket, intent.epoch)) {
            return PikPakStreamReader.BACKGROUND_BLOCKING_PRIORITY
        }
        val currentEpoch = ticket.epoch == intent.epoch || ticket.lane?.epoch == intent.epoch
        val basePriority = when (ticket.effectiveRole) {
            StreamRole.BACKGROUND -> PikPakStreamReader.BACKGROUND_BLOCKING_PRIORITY
            StreamRole.FOREGROUND -> when (intent.service) {
                ServiceIntent.WAITING -> PikPakStreamReader.BLOCKING_PRIORITY
                ServiceIntent.PLAYING -> PikPakStreamReader.STREAMING_PRIORITY
                ServiceIntent.PREFETCH -> PikPakStreamReader.MICRO_PROBE_PRIORITY
                ServiceIntent.PAUSED -> PikPakStreamReader.BACKGROUND_BLOCKING_PRIORITY
            }
        }
        return if (currentEpoch) basePriority else minOf(basePriority, PikPakStreamReader.STREAMING_PRIORITY)
    }

    ///////////////////////////////////////////////////////////////////////////
    // single planner: planLocked()
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Pure single-source-of-truth planner projecting all active byte claims under [mutex].
     */
    internal fun planLocked(): PlanSnapshot {
        if (closed || size <= 0L) {
            return PlanSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        for (state in owners.values) {
            syncOwnerEpochLocked(state)
        }

        val requiredClaims = ArrayList<ByteClaim.Required>(openTickets.size)
        val continuationClaims = ArrayList<ByteClaim.Continuation>(openTickets.size)
        val lookAheadClaims = ArrayList<ByteClaim.LookAhead>(owners.size)
        val finishClaims = ArrayList<ByteClaim.FinishInFlight>(owners.size)

        // 1. Required claims (active ReadWaiter) & Continuation claims (open mid-block reader between readSome calls).
        for (i in 0 until openTickets.size) {
            val ticket = openTickets[i]
            if (ticket.ended || ticket.owner.isClosed || ticket.position >= ticket.effectiveEnd) continue
            val intent = ticket.owner.intent
            val fg = ticket.effectiveRole == StreamRole.FOREGROUND
            val priority = ticketClaimPriorityLocked(ticket, intent)
            val waiter = ticket.activeWaiter
            if (waiter != null) {
                requiredClaims.add(
                    ByteClaim.Required(
                        owner = ticket.owner,
                        epoch = waiter.readEpoch,
                        slot = waiter.slot,
                        endByteExclusive = waiter.endByteExclusive,
                        foreground = fg,
                        priority = priority,
                        order = ticket.order,
                    ),
                )
            } else {
                val curSlot = slotOf(ticket.position)
                if (ticket.continuationSlot == curSlot) {
                    val fetch = inFlight[curSlot]
                    if (isContinuationEligibleLocked(curSlot, fetch)) {
                        continuationClaims.add(
                            ByteClaim.Continuation(
                                owner = ticket.owner,
                                epoch = intent.epoch,
                                slot = curSlot,
                                fetch = fetch!!,
                                exchangeId = fetch.claimControl.liveExchangeId,
                                foreground = fg,
                                priority = priority,
                                order = ticket.order,
                            ),
                        )
                    }
                }
            }
        }

        // 2. Owner-aggregated LookAhead claims & ended-reader single-block FinishInFlight claims.
        for (state in owners.values) {
            val owner = state.owner
            if (owner.isClosed) continue
            val intent = owner.intent
            val epoch = intent.epoch

            val currentTickets = openTickets.filter {
                !it.ended && it.lane?.epoch == epoch && it.owner === owner && it.position < it.effectiveEnd
            }
            if (currentTickets.isNotEmpty() && intent.service != ServiceIntent.PAUSED) {
                val ownerByteCeiling = effectiveOwnerBudgetBytesLocked(intent)
                if (ownerByteCeiling > 0L) {
                    val primaryLane = state.lanes.firstOrNull { it.epoch == epoch }
                    data class QualifyingLane(
                        val lane: ReadLane,
                        val activePos: Long,
                        val maxEndExclusive: Long,
                        val foreground: Boolean,
                        val order: Long,
                    )
                    val qualifying = ArrayList<QualifyingLane>(state.lanes.size)
                    val ticketsByLane = currentTickets.groupBy { it.lane!! }
                    for ((lane, laneTickets) in ticketsByLane) {
                        if (lane.epoch != epoch || lane.speculationStamp != owner.speculationRevision) continue
                        val provenSequential = lane.crossedBlockBoundary || lane.contiguousBytesRead >= blockSize
                        val qualifies = when (intent.service) {
                            ServiceIntent.WAITING, ServiceIntent.PREFETCH -> provenSequential
                            ServiceIntent.PLAYING -> provenSequential || lane === primaryLane
                            ServiceIntent.PAUSED -> false
                        }
                        if (!qualifies) continue
                        val activePos = laneTickets.minOf { it.position }
                        val maxEnd = laneTickets.maxOf { it.effectiveEnd }
                        val fg = laneTickets.any { it.effectiveRole == StreamRole.FOREGROUND }
                        val earliestOrder = laneTickets.minOf { it.order }
                        qualifying.add(QualifyingLane(lane, activePos, maxEnd, fg, earliestOrder))
                    }

                    if (qualifying.isNotEmpty()) {
                        qualifying.sortWith(
                            compareByDescending<QualifyingLane> { it.lane === primaryLane }.thenBy { it.order },
                        )
                        // Aggregate across the owner: N tickets / lanes divide the owner's budget, never multiply it!
                        val multiLane = qualifying.size > 1
                        val totalOwnerBlocks = ((ownerByteCeiling + blockSize - 1L) / blockSize).toInt().coerceAtLeast(1)
                        for (idx in 0 until qualifying.size) {
                            val q = qualifying[idx]
                            val laneMaxBlocks = if (multiLane) {
                                (totalOwnerBlocks / qualifying.size) + if (idx < totalOwnerBlocks % qualifying.size) 1 else 0
                            } else {
                                Int.MAX_VALUE
                            }
                            if (laneMaxBlocks <= 0) continue
                            val perLaneCeiling = if (multiLane) {
                                minOf(ownerByteCeiling, laneMaxBlocks.toLong() * blockSize)
                            } else {
                                ownerByteCeiling
                            }
                            val laneBudget = when (intent.service) {
                                ServiceIntent.WAITING -> {
                                    val streamRate = ownerMediaRateLocked(intent)
                                    val baseWaitingCeiling = if (q.lane.contiguousBytesRead >= 2L * blockSize) {
                                        maxOf(
                                            8L * blockSize,
                                            minOf(perLaneCeiling, effectiveReadAheadConcurrency().toLong() * 4L * blockSize),
                                        )
                                    } else {
                                        8L * blockSize
                                    }
                                    val waitingCeiling = if (
                                        streamRate != null &&
                                        streamRate >= HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC
                                    ) {
                                        maxOf(baseWaitingCeiling, streamRate)
                                    } else {
                                        baseWaitingCeiling
                                    }
                                    minOf(
                                        perLaneCeiling,
                                        (q.lane.contiguousBytesRead * 2L).coerceIn(2L * blockSize, waitingCeiling),
                                    )
                                }
                                ServiceIntent.PREFETCH -> minOf(perLaneCeiling, 4L * blockSize)
                                ServiceIntent.PLAYING -> perLaneCeiling
                                ServiceIntent.PAUSED -> 0L
                            }
                            val endByteExclusive = minOf(q.activePos + laneBudget, q.maxEndExclusive, size)
                            if (endByteExclusive <= q.activePos) continue
                            val headSlot = slotOf(q.activePos)
                            val hasActiveAtHead = requiredClaims.any { it.owner === owner && it.slot == headSlot } ||
                                continuationClaims.any { it.owner === owner && it.slot == headSlot }
                            val firstLaSlot = if (hasActiveAtHead) headSlot + 1 else headSlot
                            val endSlotInclusive = if (multiLane) {
                                minOf(slotOf(endByteExclusive - 1L), firstLaSlot + laneMaxBlocks - 1)
                            } else {
                                slotOf(endByteExclusive - 1L)
                            }
                            if (firstLaSlot > endSlotInclusive) continue
                            val nearBoundarySlot = ((q.activePos + wideBlockThresholdBytes + blockSize - 1L) / blockSize).toInt()
                            val nearReady = isNearWindowCommittedLocked(q.activePos)
                            val nearPrio = if (q.foreground) {
                                PikPakStreamReader.READ_AHEAD_PRIORITY
                            } else {
                                PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
                            }
                            val farPrio = if (q.foreground) {
                                PikPakStreamReader.FAR_READ_AHEAD_PRIORITY
                            } else {
                                PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
                            }
                            lookAheadClaims.add(
                                ByteClaim.LookAhead(
                                    owner = owner,
                                    epoch = epoch,
                                    activeHeadOffset = q.activePos,
                                    firstSlot = firstLaSlot,
                                    lastSlotInclusive = endSlotInclusive,
                                    nearBoundarySlot = nearBoundarySlot,
                                    nearReady = nearReady,
                                    foreground = q.foreground,
                                    priority = nearPrio,
                                    farPriority = farPrio,
                                    order = q.order,
                                ),
                            )
                        }
                    }
                }
            }

            // Retain single-block FinishInFlight strictly for ended/completed range tickets.
            if (intent.service != ServiceIntent.PAUSED) {
                if (state.finishInFlightBySlot.isNotEmpty()) {
                    val iter = state.finishInFlightBySlot.values.iterator()
                    while (iter.hasNext()) {
                        val fif = iter.next()
                        if (fif.epoch != epoch ||
                            fif.speculationStamp != owner.speculationRevision ||
                            !isFinishInFlightEligibleLocked(fif.slot, fif.fetch)
                        ) {
                            iter.remove()
                        } else if (requiredClaims.none { it.slot == fif.slot } &&
                            continuationClaims.none { it.slot == fif.slot } &&
                            lookAheadClaims.none { it.containsSlot(fif.slot) }
                        ) {
                            finishClaims.add(
                                ByteClaim.FinishInFlight(
                                    owner = owner,
                                    epoch = fif.epoch,
                                    slot = fif.slot,
                                    order = fif.order,
                                ),
                            )
                        }
                    }
                }
            } else {
                state.finishInFlightBySlot.clear()
            }
        }

        val activeStoreWants = wants.filter { it.demand == null || !it.demand.isClosed }
        return PlanSnapshot(
            required = requiredClaims,
            continuation = continuationClaims,
            lookAhead = lookAheadClaims,
            finishInFlight = finishClaims,
            storeWants = activeStoreWants,
        )
    }

    private fun ownerMediaRateLocked(intent: ConsumerIntent): Long? =
        intent.playbackSample
            ?.takeIf { it.generation == intent.epoch }
            ?.mediaBytesPerSecond
            ?.takeIf { it > 0L }
            ?: mediaBytesPerSecond

    private fun effectiveOwnerBudgetBytesLocked(intent: ConsumerIntent): Long {
        if (intent.service == ServiceIntent.PAUSED) return 0L
        val budget = intent.bufferBudget
        val byteCap = minOf(readAheadBytes, budget.maxReadAheadBytes.coerceAtLeast(0L))
        if (byteCap <= 0L) return 0L
        val targetDuration = budget.targetBuffer
        if (targetDuration != null) {
            if (targetDuration <= Duration.ZERO) return 0L
            val rate = ownerMediaRateLocked(intent)
            if (rate != null && rate > 0L) {
                val targetBytes = ((targetDuration.inWholeMilliseconds * rate) / 1000L).coerceAtLeast(blockSize)
                return minOf(byteCap, targetBytes)
            }
        }
        return byteCap
    }

    private fun isNearWindowCommittedLocked(position: Long): Boolean {
        if (wideBlockThresholdBytes <= 0L || size - position < wideBlockThresholdBytes) return false
        val end = position + wideBlockThresholdBytes
        for (slot in slotOf(position)..slotOf(end - 1L)) {
            val bytes = cache[slot] ?: return false
            if (slot.toLong() * blockSize + bytes.size < minOf(end, (slot + 1L) * blockSize)) return false
        }
        return true
    }

    private fun isNearWindowCoveredLocked(position: Long): Boolean {
        val end = (position + wideBlockThresholdBytes).coerceAtMost(size)
        if (end <= position) return false
        for (slot in slotOf(position)..slotOf(end - 1L)) {
            if (slot !in cache && slot !in inFlight) return false
        }
        return true
    }

    private fun isNearPrefixCoveredLocked(position: Long, blocks: Int): Boolean {
        if (size <= position || blocks <= 0) return false
        val thresholdBlocks = ((wideBlockThresholdBytes.coerceAtLeast(blockSize) + blockSize - 1L) / blockSize).toInt()
        val count = minOf(blocks, thresholdBlocks).coerceAtLeast(1)
        val first = slotOf(position)
        val last = minOf(slotOf(size - 1L), first + count - 1)
        for (slot in first..last) {
            if (slot !in cache && slot !in inFlight) return false
        }
        return true
    }

    private fun isNearPrefixCommittedLocked(position: Long, blocks: Int): Boolean {
        if (size <= position || blocks <= 0) return false
        val thresholdBlocks = ((wideBlockThresholdBytes.coerceAtLeast(blockSize) + blockSize - 1L) / blockSize).toInt()
        val count = minOf(blocks, thresholdBlocks).coerceAtLeast(1)
        val first = slotOf(position)
        val last = minOf(slotOf(size - 1L), first + count - 1)
        for (slot in first..last) {
            val bytes = cache[slot] ?: return false
            val expectedEnd = minOf(size, (slot + 1L) * blockSize)
            if (slot.toLong() * blockSize + bytes.size < expectedEnd) return false
        }
        return true
    }

    private fun isEarlierMark(a: TimeMark, b: TimeMark): Boolean = a.elapsedNow() > b.elapsedNow()

    private fun PlanSnapshot.hasAnyClaim(slot: Int): Boolean {
        for (i in 0 until required.size) if (required[i].slot == slot) return true
        for (i in 0 until continuation.size) if (continuation[i].slot == slot) return true
        for (i in 0 until lookAhead.size) if (lookAhead[i].containsSlot(slot)) return true
        for (i in 0 until finishInFlight.size) if (finishInFlight[i].slot == slot) return true
        if (slot in wanted) return true
        return false
    }

    private fun PlanSnapshot.hasRequiredInRange(firstSlotInclusive: Int, endSlotExclusive: Int): Boolean {
        for (i in 0 until required.size) {
            val s = required[i].slot
            if (s in firstSlotInclusive until endSlotExclusive) return true
        }
        return false
    }

    private fun PlanSnapshot.hasNonDurableClaim(slot: Int): Boolean {
        for (i in 0 until required.size) if (required[i].slot == slot) return true
        for (i in 0 until continuation.size) if (continuation[i].slot == slot) return true
        for (i in 0 until lookAhead.size) if (lookAhead[i].containsSlot(slot)) return true
        for (i in 0 until finishInFlight.size) if (finishInFlight[i].slot == slot) return true
        for (i in 0 until storeWants.size) {
            val w = storeWants[i]
            if (!w.durable && wantsSlot(w, slot)) return true
        }
        return false
    }

    private fun PlanSnapshot.isProtectedWindowSlot(slot: Int): Boolean {
        for (i in 0 until required.size) if (required[i].slot == slot) return true
        for (i in 0 until continuation.size) if (continuation[i].slot == slot) return true
        for (i in 0 until lookAhead.size) if (lookAhead[i].containsSlot(slot)) return true
        for (i in 0 until finishInFlight.size) if (finishInFlight[i].slot == slot) return true
        return false
    }

    private fun PlanSnapshot.isStoppingHead(slot: Int): Boolean {
        for (i in 0 until required.size) if (required[i].slot == slot) return true
        for (i in 0 until continuation.size) if (continuation[i].slot == slot) return true
        for (i in 0 until openTickets.size) {
            val t = openTickets[i]
            if (!t.ended && !t.owner.isClosed &&
                (t.epoch == t.owner.intent.epoch || t.lane?.epoch == t.owner.intent.epoch) &&
                t.position < t.effectiveEnd &&
                slotOf(t.position) == slot
            ) return true
        }
        return false
    }

    private fun PlanSnapshot.strongestClaimPriority(slot: Int): Int {
        var best = Int.MIN_VALUE
        for (i in 0 until required.size) {
            val c = required[i]
            if (c.slot == slot && c.priority > best) best = c.priority
        }
        for (i in 0 until continuation.size) {
            val c = continuation[i]
            if (c.slot == slot && c.priority > best) best = c.priority
        }
        for (i in 0 until lookAhead.size) {
            val c = lookAhead[i]
            if (c.containsSlot(slot)) {
                val p = c.priorityForSlot(slot)
                if (p > best) best = p
            }
        }
        val wantPrio = wanted.maxPriority(slot)
        if (wantPrio > best) best = wantPrio
        if (best == Int.MIN_VALUE) {
            for (i in 0 until finishInFlight.size) {
                val c = finishInFlight[i]
                if (c.slot == slot && c.priority > best) best = c.priority
            }
        }
        return best
    }

    private fun PlanSnapshot.isSingleExchangeOnly(slot: Int): Boolean {
        var hasSingleEx = false
        for (i in 0 until continuation.size) {
            if (continuation[i].slot == slot) {
                hasSingleEx = true
                break
            }
        }
        if (!hasSingleEx) {
            for (i in 0 until finishInFlight.size) {
                if (finishInFlight[i].slot == slot) {
                    hasSingleEx = true
                    break
                }
            }
        }
        if (!hasSingleEx) return false
        for (i in 0 until required.size) if (required[i].slot == slot) return false
        for (i in 0 until lookAhead.size) if (lookAhead[i].containsSlot(slot)) return false
        if (slot in wanted) return false
        return true
    }

    private fun PlanSnapshot.allowedProductionEndOffsetFor(fetch: Fetch): Long {
        val limitSlot = fetch.endSlot
        var endSlotExclusive = fetch.nextSlot
        while (endSlotExclusive < limitSlot) {
            val slot = endSlotExclusive
            val claimed = hasAnyClaim(slot) || hasRequiredInRange(slot, limitSlot)
            if (!claimed) break
            if (slot > fetch.nextSlot) {
                val bgOnly = !hasNonDurableClaim(slot) &&
                    !hasRequiredInRange(slot, limitSlot) &&
                    (fetch.durable || fetch.issuedPriority == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY)
                if (fetch.durable && fetch.servedForegroundHead && !hasNonDurableClaim(slot) && !hasRequiredInRange(slot, limitSlot)) break
                if (bgOnly && (downloadsThrottled() || countBackgroundActiveLocked(exclude = fetch) >= durableWorkerLimitLocked())) break
            }
            endSlotExclusive++
        }
        return (endSlotExclusive.toLong() * blockSize).coerceAtMost(size)
    }

    private fun PlanSnapshot.hasPendingUncachedWork(): Boolean {
        for (i in 0 until required.size) {
            val slot = required[i].slot
            if (slot !in cache && slot !in offered && slot !in persisting && slot !in failed) return true
        }
        for (i in 0 until continuation.size) {
            val slot = continuation[i].slot
            if (slot !in cache && slot in inFlight) return true
        }
        for (i in 0 until lookAhead.size) {
            val la = lookAhead[i]
            for (slot in la.firstSlot..la.lastSlotInclusive) {
                if (slot !in cache && slot !in offered && slot !in persisting && slot !in failed) return true
            }
        }
        for (i in 0 until finishInFlight.size) {
            val slot = finishInFlight[i].slot
            if (slot !in cache && slot in inFlight) return true
        }
        for (i in 0 until storeWants.size) {
            if (nextClaimable(storeWants[i]) != null) return true
        }
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            if (!f.cancelled && !f.released && wanted.overlapsRange(f.nextSlot, f.endSlot)) return true
        }
        return false
    }

    private fun reconcileClaimsLocked(): ReconcileActions {
        val plan = planLocked()
        val hasDemand = !closed && plan.hasPendingUncachedWork()
        adaptiveSource?.updateConnectionAllowance(
            if (hasDemand) {
                registration?.let { scheduler!!.connectionAllowanceFor(it) } ?: concurrency
            } else {
                0
            },
        )

        var withdrawn: ArrayList<Fetch>? = null
        var preempted: ArrayList<Fetch>? = null

        for (i in 0 until activeFetches.size) {
            val fetch = activeFetches[i]
            if (fetch.cancelled || fetch.released) continue

            // Check if a Required claim targets a future slot (slot > fetch.nextSlot) of this fetch.
            var tailRequiredSlot = Int.MAX_VALUE
            var tailRequiredPrio = Int.MIN_VALUE
            var tailRequiredMaxEndSlot = 0
            for (j in 0 until plan.required.size) {
                val req = plan.required[j]
                if (req.slot > fetch.nextSlot && req.slot < fetch.endSlot) {
                    if (req.slot < tailRequiredSlot) tailRequiredSlot = req.slot
                    if (req.priority > tailRequiredPrio) tailRequiredPrio = req.priority
                    if (req.slot + 1 > tailRequiredMaxEndSlot) tailRequiredMaxEndSlot = req.slot + 1
                }
            }

            val headPrio = plan.strongestClaimPriority(fetch.nextSlot)
            if (headPrio == Int.MIN_VALUE || (!fetch.networkStarted && !fetch.durable && !plan.hasNonDurableClaim(fetch.nextSlot))) {
                val alreadyStoppingAtBoundary = fetch.networkStarted &&
                    fetch.nextSlot > fetch.firstSlot &&
                    fetch.buffered == 0 &&
                    fetch.claimControl.allowedProductionEndOffset <= fetch.nextSlot.toLong() * blockSize
                if (alreadyStoppingAtBoundary) {
                    // RangeReader already hit allowedProductionEndOffset at the block boundary and is unwinding
                    // BoundaryStop cleanly; do not overwrite BoundaryStop with DemandWithdrawnException.
                    fetch.claimControl.inBlockQuantum = false
                    fetch.claimControl.recoveryGoalEndOffset = 0L
                    fetch.updateLivePriority(FINISH_IN_FLIGHT_PRIORITY)
                    continue
                }
                // Current nextSlot is no longer claimed (or lost all non-durable claims before network start):
                // cancel immediately so durable downloads re-claim cleanly under durableLimit & single-block throttling.
                fetch.cancelled = true
                fetch.updateLivePriority(Int.MIN_VALUE)
                fetch.claimControl.inBlockQuantum = false
                fetch.claimControl.recoveryGoalEndOffset = 0L
                (withdrawn ?: ArrayList<Fetch>(4).also { withdrawn = it }).add(fetch)
                continue
            }

            var effectivePrio = headPrio
            if (tailRequiredSlot != Int.MAX_VALUE && tailRequiredPrio > headPrio) {
                if (!fetch.networkStarted) {
                    fetch.plannedEndSlot = tailRequiredSlot
                } else if (fetch.networkBytesOnCurrentRequest <= 0L) {
                    // Predecessor has not delivered a single byte yet: never hold a higher-priority
                    // Required reader behind a stalled or unproven lower-priority predecessor.
                    fetch.cancelled = true
                    fetch.updateLivePriority(Int.MIN_VALUE)
                    fetch.claimControl.inBlockQuantum = false
                    fetch.claimControl.recoveryGoalEndOffset = 0L
                    (preempted ?: ArrayList<Fetch>(4).also { preempted = it }).add(fetch)
                    continue
                } else {
                    val currentStreamPos = fetch.nextSlot.toLong() * blockSize + fetch.buffered
                    val interveningBytes = (tailRequiredSlot.toLong() * blockSize - currentStreamPos).coerceAtLeast(0L)
                    val urgentStart = tailRequiredSlot.toLong() * blockSize
                    val urgentEnd = (tailRequiredMaxEndSlot.toLong() * blockSize).coerceAtMost(size)
                    val urgentRequiredBytes = (urgentEnd - urgentStart).coerceAtLeast(blockSize.coerceAtMost(size))
                    val inFlightRate = fetch.observedBodyRateBytesPerSecLocked()
                    val decision = RecoveryPolicy.evaluateInFlightContinuationOrReposition(
                        interveningBytes = interveningBytes,
                        urgentRequiredBytes = urgentRequiredBytes,
                        inFlightBytesPerSecond = inFlightRate,
                        repositionTtfbMillis = recentTtfbMillis,
                    )
                    if (decision.shouldReposition) {
                        fetch.cancelled = true
                        fetch.updateLivePriority(Int.MIN_VALUE)
                        fetch.claimControl.inBlockQuantum = false
                        fetch.claimControl.recoveryGoalEndOffset = 0L
                        (preempted ?: ArrayList<Fetch>(4).also { preempted = it }).add(fetch)
                        continue
                    } else {
                        effectivePrio = maxOf(effectivePrio, tailRequiredPrio)
                    }
                }
            }

            val hasReaderQuantum = plan.required.any { it.slot == fetch.nextSlot } ||
                plan.continuation.any { it.slot == fetch.nextSlot }
            fetch.claimControl.inBlockQuantum = hasReaderQuantum
            fetch.claimControl.finishInFlightOnly = plan.isSingleExchangeOnly(fetch.nextSlot)
            fetch.claimControl.allowedProductionEndOffset = plan.allowedProductionEndOffsetFor(fetch)
            fetch.updateLivePriority(effectivePrio)
            if (plan.required.any { it.slot == fetch.nextSlot && it.foreground } ||
                plan.continuation.any { it.slot == fetch.nextSlot && it.foreground }
            ) {
                fetch.servedForegroundHead = true
            }

            // Populate finite recovery goal and monotonic revision per (slot, maxEpoch) for RangeReader RecoveryPolicy.
            var hasUrgentRequired = false
            var maxUrgentEpoch = Long.MIN_VALUE
            for (j in 0 until plan.required.size) {
                val req = plan.required[j]
                if (req.slot == fetch.nextSlot && req.priority >= PikPakStreamReader.STREAMING_PRIORITY) {
                    hasUrgentRequired = true
                    if (req.epoch > maxUrgentEpoch) maxUrgentEpoch = req.epoch
                }
            }
            var hasUrgentContinuation = false
            if (!hasUrgentRequired && fetch.lastRecoveryGoalSlot == fetch.nextSlot) {
                for (j in 0 until plan.continuation.size) {
                    val cont = plan.continuation[j]
                    if (cont.slot == fetch.nextSlot && cont.priority >= PikPakStreamReader.STREAMING_PRIORITY) {
                        hasUrgentContinuation = true
                        break
                    }
                }
            }
            if (hasUrgentRequired) {
                if (fetch.lastRecoveryGoalSlot != fetch.nextSlot || maxUrgentEpoch > fetch.lastRecoveryGoalMaxEpoch) {
                    fetch.lastRecoveryGoalSlot = fetch.nextSlot
                    fetch.lastRecoveryGoalMaxEpoch = maxUrgentEpoch
                    fetch.lastRecoveryGoalRevision = ++recoveryRevisionSeq
                    fetch.claimControl.recoveryGoalRevision = fetch.lastRecoveryGoalRevision
                }
                fetch.claimControl.recoveryGoalEndOffset = minOf(size, (fetch.nextSlot + 1L) * blockSize)
            } else if (hasUrgentContinuation) {
                fetch.claimControl.recoveryGoalEndOffset = minOf(size, (fetch.nextSlot + 1L) * blockSize)
            } else {
                fetch.claimControl.recoveryGoalEndOffset = 0L
            }
        }

        // Immediately collapse excess background-only workers down to durableWorkerLimitLocked()
        // when foreground playback activates on this cache or any sibling cache on the account.
        if (downloadsThrottled()) {
            val bgLimit = durableWorkerLimitLocked()
            var bgCandidates: ArrayList<Fetch>? = null
            for (i in 0 until activeFetches.size) {
                val f = activeFetches[i]
                if (f.cancelled || f.released) continue
                val isBgOnly = !plan.hasNonDurableClaim(f.nextSlot) &&
                    (f.durable ||
                        f.livePriorityValue == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY ||
                        f.issuedPriority == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY)
                if (isBgOnly) {
                    if (!f.networkStarted && f.plannedEndSlot > f.nextSlot + 1) {
                        f.plannedEndSlot = f.nextSlot + 1
                    }
                    (bgCandidates ?: ArrayList<Fetch>(activeFetches.size).also { bgCandidates = it }).add(f)
                }
            }
            val candidates = bgCandidates
            if (candidates != null && candidates.size > bgLimit) {
                candidates.sortWith(
                    compareBy<Fetch> { it.networkStarted }
                        .thenBy { it.buffered }
                        .thenByDescending { it.order },
                )
                val excess = candidates.size - bgLimit
                for (i in 0 until excess) {
                    val victim = candidates[i]
                    victim.cancelled = true
                    victim.updateLivePriority(Int.MIN_VALUE)
                    victim.claimControl.inBlockQuantum = false
                    victim.claimControl.recoveryGoalEndOffset = 0L
                    (preempted ?: ArrayList<Fetch>(excess).also { preempted = it }).add(victim)
                }
            }
        }

        // Worker-pool priority preemption (0ms grace for strictly higher tiers & FinishInFlight;
        // same-tier in-block reader quantum finishes its current 256 KiB block without ping-pong):
        val maxWorkers = activeWorkers()
        if (maxWorkers > 0) {
            var activeCount = 0
            for (i in 0 until activeFetches.size) {
                val f = activeFetches[i]
                if (!f.cancelled && !f.released) activeCount++
            }
            if (activeCount >= maxWorkers) {
                val bestUnclaimedPrio = highestUnclaimedPriorityLocked(plan)
                if (bestUnclaimedPrio >= 0) {
                    val maxVictimPrio = when {
                        bestUnclaimedPrio >= PikPakStreamReader.BLOCKING_PRIORITY -> PikPakStreamReader.BLOCKING_PRIORITY - 1
                        bestUnclaimedPrio >= PikPakStreamReader.STREAMING_PRIORITY -> PikPakStreamReader.STREAMING_PRIORITY - 1
                        else -> FINISH_IN_FLIGHT_PRIORITY
                    }
                    var victim: Fetch? = null
                    for (i in 0 until activeFetches.size) {
                        val f = activeFetches[i]
                        if (f.cancelled || f.released || f.livePriorityValue > maxVictimPrio) continue
                        if (victim == null ||
                            f.livePriorityValue < victim.livePriorityValue ||
                            (f.livePriorityValue == victim.livePriorityValue &&
                                !f.claimControl.inBlockQuantum && victim.claimControl.inBlockQuantum) ||
                            (f.livePriorityValue == victim.livePriorityValue &&
                                f.claimControl.inBlockQuantum == victim.claimControl.inBlockQuantum &&
                                f.order > victim.order)
                        ) {
                            victim = f
                        }
                    }
                    if (victim != null) {
                        victim.cancelled = true
                        victim.updateLivePriority(Int.MIN_VALUE)
                        victim.claimControl.inBlockQuantum = false
                        victim.claimControl.recoveryGoalEndOffset = 0L
                        (preempted ?: ArrayList<Fetch>(2).also { preempted = it }).add(victim)
                    }
                }
            }
        }

        return ReconcileActions(withdrawn = withdrawn, preempted = preempted)
    }

    private fun highestUnclaimedPriorityLocked(plan: PlanSnapshot): Int {
        var best = Int.MIN_VALUE
        for (i in 0 until plan.required.size) {
            val req = plan.required[i]
            val slot = req.slot
            if (slot !in cache && slot !in offered && slot !in inFlight && slot !in failed && slot !in persisting) {
                if (req.priority > best) best = req.priority
            }
        }
        for (i in 0 until plan.lookAhead.size) {
            val la = plan.lookAhead[i]
            for (slot in la.firstSlot..la.lastSlotInclusive) {
                if (slot !in cache && slot !in offered && slot !in inFlight && slot !in failed && slot !in persisting) {
                    if (isFarTailDeferredLocked(la, slot)) break
                    val p = la.priorityForSlot(slot)
                    if (p > best) best = p
                    break
                }
            }
        }
        for (i in 0 until plan.storeWants.size) {
            val want = plan.storeWants[i]
            val slot = nextClaimable(want) ?: continue
            val strongest = wanted.strongestOrNull(slot) ?: continue
            if (strongest.priority > best) best = strongest.priority
        }
        return best
    }

    private fun isFarTailDeferredLocked(la: ByteClaim.LookAhead, slot: Int): Boolean {
        if (registration == null || throttled() || scheduler!!.concurrencyMode == ConcurrencyMode.Serial) return false
        if (!la.foreground || !la.nearReady || slot < la.nearBoundarySlot) return false
        if (size <= 0L || la.lastSlotInclusive >= slotOf(size - 1L)) return false
        val farZoneSlots = la.lastSlotInclusive - la.nearBoundarySlot + 1
        if (farZoneSlots <= 1) return false
        val minBatch = minOf(farZoneSlots, scheduler.spanFor(registration!!, 1, durable = false).coerceAtMost(4))
        return la.lastSlotInclusive - slot + 1 < minBatch
    }

    private fun cancelFetches(withdrawn: List<Fetch>?, preempted: List<Fetch>?) {
        if (withdrawn != null) {
            for (i in 0 until withdrawn.size) {
                withdrawn[i].job?.cancel(DemandWithdrawnException())
            }
        }
        if (preempted != null) {
            for (i in 0 until preempted.size) {
                preempted[i].job?.cancel(TransferPreempted())
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // warms & durable downloads
    ///////////////////////////////////////////////////////////////////////////

    fun warm(
        ranges: List<LongRange>,
        role: StreamRole,
        priority: Int?,
        durable: Boolean = false,
        demand: TransferDemand? = null,
    ): Deferred<Unit> = scope.async {
        if (demand?.isClosed == true) throw CancellationException("demand closed")
        val store = if (durable) durableStore() else null
        val clamped = ranges.mapNotNull { range ->
            val first = range.first.coerceAtLeast(0)
            val last = range.last.coerceAtMost(size - 1)
            if (first > last) null else first..last
        }
        val requested = store?.missing(storeKey, clamped) ?: clamped
        val slots = SlotRanges(requested.map { slotOf(it.first)..slotOf(it.last) })
        val want = Want(priority ?: warmPriorityFor(role), RequestOrder.Sequence.next(), durable, slots, role, demand)
        val foreground = role == StreamRole.FOREGROUND && !durable
        val fromMemory = mutableListOf<PersistTask>()
        val fromOffers = mutableListOf<Int>()
        var preemptedOnStart: List<Fetch>? = null
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
            preemptedOnStart = reconcileClaimsLocked().preempted
        }
        cancelFetches(null, preemptedOnStart)
        syncForegroundCount()
        bump()
        var warmed = false
        val warmJob = currentCoroutineContext()[Job]!!
        val demandListener = demand?.addListener {
            if (demand.isClosed) {
                warmJob.cancel(DemandWithdrawnException("demand closed"))
            } else {
                bump()
            }
        }
        if (demand?.isClosed == true) warmJob.cancel(DemandWithdrawnException("demand closed"))
        try {
            for (task in fromMemory) persist(store!!, task)
            for (slot in fromOffers) {
                val task = admitOffer(slot) ?: continue
                withContext(NonCancellable) { persist(store!!, task) }
            }
            for (slot in slots) if (durable) awaitWarmed(slot, durable = true)
            for (slot in want.slots) awaitWarmed(slot, durable)
            warmed = true
        } finally {
            demandListener?.close()
            withContext(NonCancellable) {
                if (!warmed) {
                    withdraw(want)
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
        var withdrawn: List<Fetch>? = null
        var preempted: List<Fetch>? = null
        locked {
            wants = wants - want
            val actions = reconcileClaimsLocked()
            withdrawn = actions.withdrawn
            preempted = actions.preempted
        }
        cancelFetches(withdrawn, preempted)
    }

    private fun durableStore(): DurableBlockStore =
        store as? DurableBlockStore
            ?: throw IllegalStateException("a download needs a DurableBlockStore; this cache has ${store ?: "none"}")

    private suspend fun persist(store: DurableBlockStore, task: PersistTask) {
        if (!task.started.compareAndSet(false, true)) {
            withContext(NonCancellable) { task.done.await() }
            return
        }
        val bytes = task.bytes ?: return
        var failure: Throwable? = null
        try {
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
                    val plan = planLocked()
                    val victims = cache.keys.filter { it !in persisting && !plan.isProtectedWindowSlot(it) }
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

    @Volatile
    private var foregroundReadingNow: Boolean = false

    @Volatile
    private var foregroundIdleDeadline: TimeMark? = null

    fun close() {
        if (closed) return
        closed = true
        foregroundReadingNow = false
        foregroundIdleDeadline = null
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

    private fun refreshForegroundStreamActiveLocked() {
        if (closed) {
            foregroundReadingNow = false
            foregroundIdleDeadline = null
            return
        }
        var readingFg = false
        var latestDeadline: TimeMark? = null
        for (i in 0 until openTickets.size) {
            val t = openTickets[i]
            if (!t.ended && !t.owner.isClosed && t.effectiveRole == StreamRole.FOREGROUND) {
                if (t.activeWaiter != null) {
                    readingFg = true
                }
                val state = owners[t.owner]
                val budget = effectiveOwnerBudgetBytesLocked(t.owner.intent)
                val activeAt = state?.lastForegroundActiveAt
                if (budget > 0L && activeAt != null) {
                    val d = activeAt + foregroundIdle
                    if (!d.hasPassedNow() && (latestDeadline == null || isEarlierMark(latestDeadline, d))) {
                        latestDeadline = d
                    }
                }
            }
        }
        for (state in owners.values) {
            val owner = state.owner
            val activeAt = state.lastForegroundActiveAt ?: continue
            if (!owner.isClosed &&
                owner.priority >= PikPakStreamReader.STREAMING_PRIORITY &&
                effectiveOwnerBudgetBytesLocked(owner.intent) > 0L
            ) {
                val d = activeAt + foregroundIdle
                if (!d.hasPassedNow() && (latestDeadline == null || isEarlierMark(latestDeadline, d))) {
                    latestDeadline = d
                }
            }
        }
        foregroundReadingNow = readingFg
        foregroundIdleDeadline = latestDeadline
    }

    private fun hasForegroundStreamDemand(): Boolean {
        if (closed) return false
        if (foregroundReadingNow) return true
        val deadline = foregroundIdleDeadline ?: return false
        return !deadline.hasPassedNow()
    }

    private fun hasForegroundDemand(): Boolean {
        if (hasForegroundStreamDemand()) return true
        val allWants = wants
        for (i in 0 until allWants.size) {
            val w = allWants[i]
            if (!w.durable && (w.demand == null || !w.demand.isClosed) && w.priority >= PikPakStreamReader.STREAMING_PRIORITY) {
                return true
            }
        }
        return false
    }

    private suspend fun watchForegroundIdle() {
        while (currentCoroutineContext().isActive) {
            val seen = idleSignal.value
            val nextIdle: Duration? = locked {
                var earliest: Duration? = null
                val anyReadingFg = openTickets.any {
                    !it.ended && !it.owner.isClosed && it.activeWaiter != null && it.effectiveRole == StreamRole.FOREGROUND
                }
                if (!anyReadingFg) {
                    for (state in owners.values) {
                        val last = state.lastForegroundActiveAt ?: continue
                        if (!state.owner.isClosed && state.owner.priority >= PikPakStreamReader.STREAMING_PRIORITY) {
                            val remaining = foregroundIdle - last.elapsedNow()
                            if (remaining.isPositive() && (earliest == null || remaining < earliest)) {
                                earliest = remaining
                            }
                        }
                    }
                }
                earliest
            }
            if (nextIdle == null) {
                syncForegroundCount()
                idleSignal.awaitChange(seen)
                continue
            }
            delay(nextIdle)
            locked {
                for (state in owners.values.toList()) pruneOwnerIfIdleLocked(state)
            }
            syncForegroundCount()
            bump()
        }
    }

    private fun syncForegroundCount() {
        while (true) {
            val want = !closed && hasForegroundDemand()
            if (counted.value == want) return
            if (counted.compareAndSet(!want, want)) {
                if (want) foregroundStreams?.enter() else foregroundStreams?.leave()
            }
        }
    }

    private fun throttled(): Boolean =
        !hasForegroundDemand() && (foregroundStreams?.active?.value ?: 0) > 0

    private fun downloadsThrottled(): Boolean =
        hasForegroundDemand() || (foregroundStreams?.active?.value ?: 0) > 0

    fun workerLimitChanged() = bump()

    private fun activeWorkers(): Int {
        val scheduled = registration?.let { scheduler!!.connectionAllowanceFor(it) }
            ?: if (throttled()) PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND else concurrency
        val base = if (registration != null && throttled()) {
            minOf(scheduled, PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND)
        } else {
            scheduled
        }
        val capacity = minOf(
            concurrency,
            workerLimit().coerceAtLeast(0),
            base,
            adaptiveSource?.connectionLimit?.value?.coerceAtLeast(0) ?: concurrency,
        )
        return if (!hasForegroundStreamDemand() && foregroundWarms > 0) {
            minOf(capacity, 2)
        } else {
            capacity
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // waiting
    ///////////////////////////////////////////////////////////////////////////

    private suspend fun awaitTicketSlot(
        ticket: TicketHandle,
        waiter: ReadWaiter,
        slot: Int,
        within: Int,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var firstPass = true
        var retried = false
        try {
            while (true) {
                checkTicketOpen(ticket)
                var seen = 0L
                var failure: Throwable? = null
                var withdrawn: List<Fetch>? = null
                var preempted: List<Fetch>? = null
                val copied = locked {
                    val isFirstPass = firstPass
                    firstPass = false
                    seen = readSignal.value
                    checkTicketOpen(ticket)

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

                    val actions = reconcileClaimsLocked()
                    withdrawn = actions.withdrawn
                    preempted = actions.preempted
                    if (actions.withdrawn != null || actions.preempted != null) {
                        revision.bump()
                    }

                    val complete = hit(slot) ?: persisting[slot]?.bytes ?: offered[slot]
                    val fetch = inFlight[slot]?.takeIf { it.nextSlot == slot }
                    val partial = partials[slot]
                    val bytes = complete ?: fetch?.buffer ?: partial?.bytes
                    val end = complete?.size ?: fetch?.buffered ?: partial?.length ?: 0
                    if (bytes != null && end > within) {
                        waiter.waitingWithin = -1
                        val count = minOf(end - within, length)
                        bytes.copyInto(destination, offset, within, within + count)
                        if (complete == null) fetch?.prefixServedToReader = true
                        count
                    } else {
                        waiter.waitingWithin = if (failure == null) within else -1
                        null
                    }
                }
                cancelFetches(withdrawn, preempted)
                if (copied != null) return copied
                failure?.let { throw PikPakException(-1, "stream failed at offset ${slot.toLong() * blockSize}", cause = it) }
                readSignal.awaitChange(seen)
            }
        } finally {
            waiter.waitingWithin = -1
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
                    lost = failure == null && slot !in durableDone && slot !in wanted && slot !in persisting
                    slot !in durableDone
                }
            }
            failure?.let {
                val what = if (durable) "download" else "prefetch"
                throw PikPakException(-1, "$what failed at offset ${slot.toLong() * blockSize}", cause = it)
            }
            if (lost) throw PikPakException(-1, "download lost track of offset ${slot.toLong() * blockSize}")
            if (!pending) return
            revision.awaitChange(seen)
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // fetching
    ///////////////////////////////////////////////////////////////////////////

    internal inner class Fetch(
        val firstSlot: Int,
        initialSlotCount: Int,
        val initialPriority: Int,
        val order: Long,
        val durable: Boolean = false,
    ) {
        private val mutableLivePriority = MutableStateFlow(initialPriority)
        val livePriorityFlow: StateFlow<Int> get() = mutableLivePriority
        var livePriorityValue: Int = initialPriority
            private set

        val claimControl = FetchClaimControl(priorityFlow = mutableLivePriority)

        val done = CompletableDeferred<Unit>(scope.coroutineContext[Job])

        @Volatile var job: Job? = null
        @Volatile var cancelled = false
        var released = false
        var servedForegroundHead = false
        var lastProgressAt = timeSource.markNow()
        @Volatile var nextSlot = firstSlot
        @Volatile var plannedEndSlot = firstSlot + initialSlotCount
        @Volatile var issuedEndSlot = firstSlot + initialSlotCount
        @Volatile var waitingForLimiter = false
        @Volatile var networkStarted = false
        var issuedPriority = Int.MIN_VALUE
        var lastByteExchangeId = 0L
        var firstNetworkByteAt: TimeMark? = null
        var networkBytesOnCurrentRequest = 0L
        var lastRecoveryGoalSlot = -1
        var lastRecoveryGoalMaxEpoch = Long.MIN_VALUE
        var lastRecoveryGoalRevision = 0L
        var buffer: ByteArray? = null
        var buffered = 0
        var prefixServedToReader = false
        var reserved = 0L
        var transportReserved = 0L
        val endSlot: Int get() = if (networkStarted) issuedEndSlot else plannedEndSlot
        val slotCount: Int get() = endSlot - firstSlot
        val startOffset: Long get() = firstSlot.toLong() * blockSize
        val endOffset: Long get() = (endSlot.toLong() * blockSize).coerceAtMost(size)
        val slots: IntRange get() = nextSlot until endSlot
        fun containsSlot(slot: Int): Boolean = slot >= nextSlot && slot < endSlot

        fun observedBodyRateBytesPerSecLocked(): Long? {
            if (claimControl.liveExchangeId <= 0L) return null
            val firstMark = firstNetworkByteAt ?: return null
            if (networkBytesOnCurrentRequest < 16L * 1024L) return null
            val elapsedNs = firstMark.elapsedNow().inWholeNanoseconds
            if (elapsedNs < 50_000_000L) return null
            return (networkBytesOnCurrentRequest * 1_000_000_000L) / elapsedNs
        }

        fun updateLivePriority(priority: Int) {
            if (livePriorityValue != priority) {
                livePriorityValue = priority
                mutableLivePriority.value = priority
            }
        }
    }

    private suspend fun runWorker(index: Int) {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value
            if (index >= activeWorkers()) {
                revision.awaitChange(seen)
                continue
            }
            var withdrawn: List<Fetch>? = null
            var preempted: List<Fetch>? = null
            val fetch = locked {
                val actions = reconcileClaimsLocked()
                withdrawn = actions.withdrawn
                preempted = actions.preempted
                claimNext(enforceWorkerLimit = true)
            }
            cancelFetches(withdrawn, preempted)
            if (fetch == null) {
                revision.awaitChange(seen)
                continue
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(fetch) }
            val startIt = mutex.withLock {
                if (fetch.cancelled) false else { fetch.job = job; true }
            }
            if (!startIt) {
                job.cancel()
                abandon(fetch)
                continue
            }
            try {
                job.start()
                job.join()
            } finally {
                withContext(NonCancellable) {
                    job.cancelAndJoin()
                    abandon(fetch)
                }
            }
        }
    }

    /**
     * Claims the highest-priority unclaimed slot from [planLocked].
     * Note: [ByteClaim.Continuation] and [ByteClaim.FinishInFlight] NEVER create a new [Fetch]
     * (`0` new HTTP requests).
     *
     * Must be called under [mutex].
     */
    private fun claimNext(enforceWorkerLimit: Boolean = true): Fetch? {
        if (closed) return null
        val plan = planLocked()
        val maxWorkers = activeWorkers()
        var nonCancelledActive = 0
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            if (!f.cancelled && !f.released) nonCancelledActive++
        }
        if (enforceWorkerLimit && nonCancelledActive >= maxWorkers) return null

        var target = -1
        var targetPriority = Int.MIN_VALUE
        var targetOrder = Long.MAX_VALUE
        var targetRequired: ByteClaim.Required? = null
        var targetLookAhead: ByteClaim.LookAhead? = null
        var targetWant: Want? = null

        fun consider(
            slot: Int,
            priority: Int,
            order: Long,
            req: ByteClaim.Required?,
            la: ByteClaim.LookAhead?,
            want: Want?,
        ) {
            val targetIsReq = targetRequired != null
            val candIsReq = req != null
            val beats = when {
                priority != targetPriority -> priority > targetPriority
                candIsReq != targetIsReq -> candIsReq
                else -> order < targetOrder
            }
            if (beats) {
                target = slot
                targetPriority = priority
                targetOrder = order
                targetRequired = req
                targetLookAhead = la
                targetWant = want
            }
        }

        val effectiveReadAhead = effectiveReadAheadConcurrency()
        var readAheadActive = 0
        val durableActive = countBackgroundActiveLocked()
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            if (f.cancelled || f.released || f.durable) continue
            if (!wanted.overlapsRange(f.nextSlot, f.endSlot) &&
                plan.required.none { it.slot == f.nextSlot } &&
                plan.continuation.none { it.slot == f.nextSlot }
            ) {
                readAheadActive++
            }
        }
        val readAheadHeld = readAheadActive >= effectiveReadAhead

        // 1. Required claims
        for (i in 0 until plan.required.size) {
            val req = plan.required[i]
            val slot = req.slot
            if (slot in cache || slot in offered || slot in inFlight || slot in failed || slot in persisting) continue
            consider(slot, req.priority, req.order, req, null, null)
        }

        // 2. LookAhead claims
        if (!readAheadHeld) {
            for (i in 0 until plan.lookAhead.size) {
                val la = plan.lookAhead[i]
                for (slot in la.firstSlot..la.lastSlotInclusive) {
                    if (slot in cache || slot in offered || slot in inFlight || slot in failed || slot in persisting) continue
                    if (isFarTailDeferredLocked(la, slot)) break
                    val prio = la.priorityForSlot(slot)
                    consider(slot, prio, la.order, null, la, null)
                    break
                }
            }
        }

        // 3. StoreRange claims (warms & durable downloads)
        val durableLimit = durableWorkerLimitLocked()
        val downloadsHeld = durableActive >= durableLimit
        for (i in 0 until plan.storeWants.size) {
            val want = plan.storeWants[i]
            if (want.durable && downloadsHeld) continue
            val slot = nextClaimable(want) ?: continue
            val strongest = wanted.strongestOrNull(slot) ?: continue
            consider(slot, strongest.priority, strongest.order, null, null, strongest)
        }

        // Idle capacity can split an unstarted wide durable plan before networkStarted == true.
        var tailDonor: Fetch? = null
        if (target == -1 && !downloadsHeld) {
            var donor: Fetch? = null
            var donorSpan = 1
            for (i in 0 until activeFetches.size) {
                val f = activeFetches[i]
                val span = f.plannedEndSlot - f.nextSlot
                if (f.durable && !f.released && !f.cancelled && !f.networkStarted && span > donorSpan &&
                    f.lastProgressAt.elapsedNow() > Duration.ZERO
                ) {
                    val split = f.plannedEndSlot - span / 2
                    if (wanted.hasDurable(split)) {
                        donor = f
                        donorSpan = span
                    }
                }
            }
            if (donor != null) {
                val split = donor.plannedEndSlot - (donor.plannedEndSlot - donor.nextSlot) / 2
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

        val chosenWant = targetWant
        val chosenRequired = targetRequired
        val streamLa = targetLookAhead
        val blocking = chosenRequired != null
        val matchingOwnerLa = if (blocking) {
            plan.lookAhead.firstOrNull { it.owner === chosenRequired.owner && it.epoch == chosenRequired.epoch }
        } else {
            streamLa
        }
        val downloadWant = chosenWant?.takeIf { !blocking && streamLa == null && it.durable }
        val streamWarm = chosenWant?.takeIf { !blocking && streamLa == null && !it.durable }

        val spanWorkers = when {
            downloadWant != null -> durableLimit
            blocking || streamLa != null -> minOf(maxWorkers.coerceAtLeast(1), effectiveReadAhead)
            else -> maxWorkers.coerceAtLeast(1)
        }.coerceAtLeast(1)

        val windowLastSlot = when {
            matchingOwnerLa != null -> maxOf(target, matchingOwnerLa.lastSlotInclusive)
            blocking -> target
            else -> target
        }

        val fairShare = when {
            downloadWant != null -> {
                val remaining = (downloadWant.slots.size - downloadWant.next).coerceAtLeast(1).toLong()
                ((remaining + spanWorkers - 1) / spanWorkers).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
            blocking || streamLa != null -> {
                var unreservedSlots = 0L
                for (s in target..windowLastSlot) {
                    if (s.toLong() * blockSize >= size) break
                    if (s !in cache && s !in inFlight && s !in offered && s !in persisting && s !in failed) {
                        unreservedSlots++
                    }
                }
                val activePoolWorkers = nonCancelledActive.coerceAtMost((spanWorkers - 1).coerceAtLeast(0))
                val remainingWorkers = minOf(
                    (maxWorkers - nonCancelledActive).coerceAtLeast(1),
                    (spanWorkers - activePoolWorkers).coerceAtLeast(1),
                )
                ((unreservedSlots.coerceAtLeast(1L) + remainingWorkers - 1L) / remainingWorkers)
                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                    .toInt()
            }
            else -> 2
        }

        val highBitrateSpan = if (!blocking) highBitratePlaybackSpan() else 1
        val activeHeadOffset = streamLa?.activeHeadOffset ?: (target.toLong() * blockSize)
        val maxSpan = when {
            tailDonor != null -> tailDonor.plannedEndSlot - target
            throttled() -> 1
            downloadWant != null && downloadsThrottled() -> 1
            blocking -> {
                if (registration != null || matchingOwnerLa == null) {
                    1
                } else {
                    val baseSpan = if (isNearWindowCoveredLocked(activeHeadOffset)) 4 else 2
                    minOf(fairShare, baseSpan)
                }
            }
            streamLa != null -> {
                if (registration == null) {
                    if (targetPriority == PikPakStreamReader.READ_AHEAD_PRIORITY &&
                        isNearWindowCommittedLocked(streamLa.activeHeadOffset)
                    ) {
                        1
                    } else {
                        val baseSpan = if (isNearWindowCoveredLocked(streamLa.activeHeadOffset)) 4 else 2
                        minOf(fairShare, maxOf(baseSpan, highBitrateSpan))
                    }
                } else {
                    val schedulerCeiling = scheduler!!.spanFor(registration!!, spanWorkers, durable = false)
                    val waveBlocks = minOf(4, spanWorkers).coerceAtLeast(1)
                    val ownerBudgetBytes = effectiveOwnerBudgetBytesLocked(streamLa.owner.intent)
                    val budgetSupports1MiB = ownerBudgetBytes >= waveBlocks.toLong() * 4L * blockSize
                    val progressiveSpan = when {
                        !isNearPrefixCoveredLocked(streamLa.activeHeadOffset, waveBlocks) -> 1
                        !isNearPrefixCommittedLocked(streamLa.activeHeadOffset, waveBlocks) ||
                            (completedMultiBlockFetches == 0L && highBitrateSpan < 4) ||
                            !budgetSupports1MiB -> 2
                        else -> 4
                    }
                    minOf(schedulerCeiling, progressiveSpan)
                }
            }
            streamWarm != null -> 2
            downloadWant != null -> {
                val span = registration?.let { scheduler!!.spanFor(it, spanWorkers, durable = true) } ?: 2
                minOf(fairShare, span)
            }
            else -> 1
        }

        val cap = minOf(memoryCapBytes, memoryBudget.budgetBytes)
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
            if (!plan.isProtectedWindowSlot(cachedSlot)) {
                outsideBytes += cachedArr.size.toLong()
            } else if (blocking && !plan.isStoppingHead(cachedSlot)) {
                insideBlockingBytes += cachedArr.size.toLong()
            }
        }
        for ((partialSlot, partial) in partials) {
            if (partialSlot != target && partialSlot !in inFlight && !plan.isProtectedWindowSlot(partialSlot)) {
                outsideBytes += partial.bytes.size.toLong()
            }
        }

        var slotCount = 0
        var bytes = 0L
        for (k in 1..maxSpan) {
            if (target.toLong() + k - 1 >= Int.MAX_VALUE) break
            val slot = target + k - 1
            if (streamLa != null && targetPriority == PikPakStreamReader.READ_AHEAD_PRIORITY) {
                if (target < streamLa.nearBoundarySlot && slot >= streamLa.nearBoundarySlot) break
            }
            if (slot.toLong() * blockSize >= size ||
                slot in cache ||
                (k > 1 && slot in partials) ||
                (slot in inFlight && inFlight[slot] !== tailDonor) ||
                slot in failed ||
                slot in persisting
            ) {
                break
            }
            if (blocking || streamLa != null) {
                if (slot > windowLastSlot) break
            } else {
                if (chosenWant == null || !wanted.claims(chosenWant, slot)) break
            }
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
            val partialVictim = partials.keys.firstOrNull { slot ->
                slot != target && slot !in inFlight && !plan.isProtectedWindowSlot(slot)
            }
            if (partialVictim != null) {
                partials.remove(partialVictim)
                ownershipDirty = true
                releaseUnowned()
                continue
            }
            val victim = cache.keys.firstOrNull { slot ->
                slot !in persisting && slot !in offered && !plan.isProtectedWindowSlot(slot)
            }
                ?: (if (slotCount == 1 && blocking) neededLast(plan) else null)
                ?: return null
            evict(victim)
            releaseUnowned()
        }

        val fetch = Fetch(target, slotCount, targetPriority, targetOrder, durable = downloadWant != null)
        if (blocking && targetRequired!!.foreground) fetch.servedForegroundHead = true
        tailDonor?.let { it.plannedEndSlot = target }
        activeFetches.add(fetch)
        return fetch
    }

    private fun nextClaimable(want: Want): Int? {
        if (want.demand?.isClosed == true) return null
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

    private fun neededLast(plan: PlanSnapshot): Int? {
        var bestSlot = -1
        var bestDistance = Int.MIN_VALUE
        for (slot in cache.keys) {
            if (slot in persisting || slot in offered) continue
            if (plan.isStoppingHead(slot)) continue
            var minDistance = Int.MAX_VALUE
            for (i in 0 until plan.lookAhead.size) {
                val la = plan.lookAhead[i]
                if (la.containsSlot(slot)) {
                    val head = slotOf(la.activeHeadOffset)
                    val d = slot - head
                    if (d < minDistance) minDistance = d
                }
            }
            if (minDistance > bestDistance) {
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

    private fun durableWorkerLimitLocked(): Int {
        val scheduled = registration?.let { scheduler!!.connectionAllowanceFor(it) } ?: concurrency
        return if (downloadsThrottled()) {
            minOf(PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND, scheduled, concurrency)
        } else {
            minOf(scheduled, concurrency)
        }
    }

    private fun countBackgroundActiveLocked(exclude: Fetch? = null): Int {
        var count = 0
        for (i in 0 until activeFetches.size) {
            val f = activeFetches[i]
            if (f === exclude || f.released) continue
            if (f.durable ||
                f.issuedPriority == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY ||
                (!f.cancelled && f.livePriorityValue == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY)
            ) {
                count++
            }
        }
        return count
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
                val bytes = try {
                    store?.read(storeKey, offset, length)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
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
                    var preStartWithdrawn: List<Fetch>? = null
                    var preStartPreempted: List<Fetch>? = null
                    val (offset, length, callPriority) = locked {
                        if (fetch.cancelled || fetch.released || closed) {
                            Triple(0L, 0L, Int.MIN_VALUE)
                        } else {
                            val plan = planLocked()
                            val headPrio = plan.strongestClaimPriority(fetch.nextSlot)
                            val nonDurableGone = !fetch.durable && !plan.hasNonDurableClaim(fetch.nextSlot)
                            val backgroundOverCap = headPrio == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY &&
                                countBackgroundActiveLocked(exclude = fetch) >= durableWorkerLimitLocked()
                            if (headPrio == Int.MIN_VALUE || nonDurableGone || backgroundOverCap) {
                                fetch.cancelled = true
                                fetch.updateLivePriority(Int.MIN_VALUE)
                                fetch.claimControl.inBlockQuantum = false
                                fetch.claimControl.recoveryGoalEndOffset = 0L
                                Triple(0L, 0L, Int.MIN_VALUE)
                            } else {
                                if (fetch.durable && downloadsThrottled() && fetch.plannedEndSlot > fetch.nextSlot + 1) {
                                    fetch.plannedEndSlot = fetch.nextSlot + 1
                                }
                                for (j in 0 until plan.required.size) {
                                    val req = plan.required[j]
                                    if (req.slot > fetch.nextSlot && req.slot < fetch.plannedEndSlot && req.priority > headPrio) {
                                        fetch.plannedEndSlot = req.slot
                                    }
                                }
                                fetch.issuedEndSlot = fetch.plannedEndSlot
                                fetch.networkStarted = true
                                fetch.firstNetworkByteAt = null
                                fetch.networkBytesOnCurrentRequest = 0L
                                fetch.issuedPriority = headPrio
                                val actions = reconcileClaimsLocked()
                                preStartWithdrawn = actions.withdrawn
                                preStartPreempted = actions.preempted
                                if (fetch.cancelled) {
                                    Triple(0L, 0L, Int.MIN_VALUE)
                                } else {
                                    val startOff = fetch.nextSlot.toLong() * blockSize + fetch.buffered
                                    Triple(startOff, fetch.endOffset - startOff, fetch.livePriorityValue)
                                }
                            }
                        }
                    }
                    cancelFetches(preStartWithdrawn, preStartPreempted)
                    if (length <= 0L || callPriority == Int.MIN_VALUE) return
                    val tracksOwnExchange = source is RangeReader || source is PikPakFileHandle
                    val localExchangeId = if (!tracksOwnExchange) fetch.claimControl.beginExchange() else 0L
                    try {
                        withContext(
                            RequestOrder(fetch.order) +
                                telemetry +
                                LiveSignedPriority(fetch.livePriorityFlow) +
                                fetch.claimControl,
                        ) {
                            source.read(offset, length, callPriority) { channel ->
                                while (fetch.nextSlot < fetch.endSlot) {
                                    val slot = fetch.nextSlot
                                    if (slot != firstLimitedSlot) {
                                        val stopAtBoundary = locked {
                                            if (fetch.cancelled || fetch.released || closed) {
                                                true
                                            } else {
                                                val snapshot = planLocked()
                                                val hasTailReq = snapshot.hasRequiredInRange(slot, fetch.endSlot)
                                                val bgOnly = !snapshot.hasNonDurableClaim(slot) &&
                                                    !hasTailReq &&
                                                    (fetch.durable || fetch.issuedPriority == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY)
                                                (!snapshot.hasAnyClaim(slot) && !hasTailReq) ||
                                                    (fetch.durable && fetch.servedForegroundHead && !snapshot.hasNonDurableClaim(slot) && !hasTailReq) ||
                                                    (bgOnly && (downloadsThrottled() || countBackgroundActiveLocked(exclude = fetch) >= durableWorkerLimitLocked()))
                                            }
                                        }
                                        if (stopAtBoundary) {
                                            retired = true
                                            return@read
                                        }
                                        telemetry.beginLimiterWait()
                                        try {
                                            limitBlock(fetch, slot)
                                        } finally {
                                            telemetry.endLimiterWait()
                                        }
                                    }
                                    val expected = minOf(blockSize, size - slot.toLong() * blockSize).toInt()
                                    if (!reserveBuffer(fetch, expected, allowPartialOffset = slot == firstLimitedSlot)) {
                                        retired = true
                                        return@read
                                    }
                                    val bytes = fetch.buffer ?: ByteArray(expected).also { adoptBuffer(fetch, it) }
                                    while (fetch.buffered < expected) {
                                        val count = channel.readAvailable(bytes, fetch.buffered, expected - fetch.buffered)
                                        if (count < 0) {
                                            if (slot > firstLimitedSlot && fetch.buffered == 0) {
                                                retired = true
                                                return@read
                                            }
                                            throw PikPakException(
                                                -1,
                                                "stream: got ${fetch.buffered} of $expected bytes at offset ${slot.toLong() * blockSize}",
                                            )
                                        }
                                        if (count == 0) continue
                                        locked {
                                            if (fetch.firstNetworkByteAt == null) {
                                                fetch.firstNetworkByteAt = timeSource.markNow()
                                            }
                                            fetch.networkBytesOnCurrentRequest += count
                                            fetch.buffered += count
                                            if (!tracksOwnExchange) {
                                                fetch.claimControl.markExchangeDelivered(localExchangeId)
                                            }
                                            val deliveredEx = fetch.claimControl.deliveredExchangeId
                                            if (deliveredEx > 0L) {
                                                fetch.lastByteExchangeId = deliveredEx
                                            }
                                            receivedBytes += count
                                            if (fetch.buffered < expected) {
                                                var wakeReaders = false
                                                for (i in 0 until openTickets.size) {
                                                    val w = openTickets[i].activeWaiter
                                                    if (w != null && w.slot == slot && w.waitingWithin >= 0 && fetch.buffered > w.waitingWithin) {
                                                        w.waitingWithin = -1
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
                    } finally {
                        if (!tracksOwnExchange) {
                            fetch.claimControl.endExchange(localExchangeId)
                        }
                        withContext(NonCancellable) {
                            locked {
                                fetch.issuedPriority = Int.MIN_VALUE
                            }
                        }
                    }
                    if (retired) return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (fetch.claimControl.finishInFlightOnly) {
                        // Continuation / FinishInFlight issues zero retries and does not poison failed[slot].
                        return
                    }
                    sourceFailures++
                    if (source is RangeReader || source is PikPakFileHandle || sourceFailures >= MAX_ATTEMPTS) {
                        fail(fetch, e)
                        return
                    }
                    // Retry only the first unpublished slot on the next fresh request.
                    locked {
                        fetch.networkStarted = false
                        fetch.lastByteExchangeId = 0L
                        fetch.firstNetworkByteAt = null
                        fetch.networkBytesOnCurrentRequest = 0L
                        fetch.plannedEndSlot = fetch.nextSlot + 1
                        fetch.issuedEndSlot = fetch.plannedEndSlot
                    }
                } finally {
                    telemetry.timeToFirstByte?.let { ttfb ->
                        recentTtfbMillis = ttfb.inWholeMilliseconds.coerceIn(20L, 10_000L)
                    }
                    telemetry.networkDuration = (telemetry.networkDuration - telemetry.uncoveredLimiterWait).coerceAtLeast(Duration.ZERO)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!fetch.claimControl.finishInFlightOnly) {
                fail(fetch, e)
            }
        } finally {
            withContext(NonCancellable) {
                abandon(fetch)
            }
        }
    }

    private suspend fun limitBlock(fetch: Fetch, slot: Int): Duration {
        val limited = locked {
            val plan = planLocked()
            val bytes = if (wanted.allDurable(slot) && !plan.hasNonDurableClaim(slot)) {
                minOf(blockSize, size - slot.toLong() * blockSize)
            } else {
                0L
            }
            fetch.waitingForLimiter = bytes > 0L && downloadLimiter != null
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
                        if (locked { planLocked().hasNonDurableClaim(slot) }) break
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
        } finally {
            withContext(NonCancellable) {
                locked { fetch.waitingForLimiter = false }
            }
        }
        return started.elapsedNow()
    }

    private suspend fun publish(fetch: Fetch, slot: Int, bytes: ByteArray, fromNetwork: Boolean) {
        var queued: PersistTask? = null
        locked {
            if (inFlight[slot] !== fetch) return@locked
            val plan = planLocked()
            val claimMask = wanted.completeSlot(slot)
            failed.remove(slot)
            for (state in owners.values) {
                state.finishInFlightBySlot.remove(slot)
            }
            val downloadOnly = claimMask == 3 && !plan.hasNonDurableClaim(slot)
            if (!downloadOnly) {
                put(slot, bytes)
                if (fetch.prefixServedToReader) unreadSlots.remove(slot)
            }
            val durable = (claimMask and 2) != 0
            if (durable && !fromNetwork) {
                durableDone += slot
            } else if (durable && slot !in persisting) {
                val task = PersistTask(slot, bytes, accounted = downloadOnly, optional = optionalWrites[slot])
                persisting[slot] = task
                offered[slot] = bytes
                if (downloadOnly) pendingPersistBytes += bytes.size
                queued = task
            }
            if (fromNetwork) {
                deliveredBytes += bytes.size
                if (slot > fetch.firstSlot) {
                    completedMultiBlockFetches++
                }
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
            fetch.lastByteExchangeId = 0L
            fetch.prefixServedToReader = false
            reconcileClaimsLocked()
            for (state in owners.values.toList()) pruneOwnerIfIdleLocked(state)
            bump()
        }
        queued?.let { task ->
            try {
                durableWrites.send(task)
            } catch (e: Throwable) {
                withContext(NonCancellable) { persist(durableStore(), task) }
                throw e
            }
        }
    }

    private fun forgetOffer(slot: Int, bytes: ByteArray) {
        if (offered[slot] === bytes) {
            offered.remove(slot)
            ownershipDirty = true
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

    private suspend fun abandon(fetch: Fetch) = withContext(NonCancellable) {
        mutex.withLock {
            releaseSlotsLocked(fetch)
            activeFetches.remove(fetch)
            for (state in owners.values.toList()) {
                if (state.finishInFlightBySlot.isNotEmpty()) {
                    val iter = state.finishInFlightBySlot.values.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().fetch === fetch) iter.remove()
                    }
                }
                pruneOwnerIfIdleLocked(state)
            }
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
            refreshForegroundStreamActiveLocked()
            fetch.done.complete(Unit)
            bump()
        }
    }

    private fun releaseSlotsLocked(fetch: Fetch) {
        fetch.released = true
        fetch.issuedPriority = Int.MIN_VALUE
        fetch.updateLivePriority(Int.MIN_VALUE)
    }

    ///////////////////////////////////////////////////////////////////////////
    // cache & memory budget
    ///////////////////////////////////////////////////////////////////////////

    private fun slotOf(offset: Long): Int = (offset / blockSize).toInt()

    private fun hit(slot: Int): ByteArray? {
        val data = cache.remove(slot) ?: return null
        cache[slot] = data
        unreadSlots.remove(slot)
        return data
    }

    private fun put(slot: Int, data: ByteArray) {
        val previous = cache.put(slot, data)
        if (previous != null && previous !== data) ownershipDirty = true
        cachedBytes += data.size - (previous?.size ?: 0)
        unreadSlots.add(slot)
    }

    private suspend fun <T> locked(body: () -> T): T = mutex.withLock {
        try {
            body()
        } finally {
            refreshForegroundStreamActiveLocked()
            if (ownershipDirty) releaseUnowned()
        }
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
        val priority = fetch.livePriorityValue.coerceAtLeast(0)
        val waiter = memoryBudget.registerWaiter(priority)
        try {
            return reserveBuffer(fetch, bytes, priority, allowPartialOffset)
        } finally {
            memoryBudget.unregisterWaiter(waiter)
        }
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
                (fetch.transportReserved > 0 || (source !is RangeReader && source !is PikPakFileHandle)))
        ) {
            return true
        }
        val transport = if ((source is RangeReader || source is PikPakFileHandle) && fetch.transportReserved == 0L) {
            RangeReader.READ_CHUNK.toLong()
        } else {
            0L
        }
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
                    val plan = planLocked()
                    val blocking = plan.required.any { it.slot == fetch.nextSlot }
                    if (!fitsMemory() && partials.isNotEmpty()) {
                        for (slot in partials.keys.toList()) {
                            if (fitsMemory()) break
                            if (slot != fetch.nextSlot && slot !in inFlight && !plan.isProtectedWindowSlot(slot)) {
                                partials.remove(slot)
                                ownershipDirty = true
                                releaseUnowned()
                            }
                        }
                    }
                    if (!fitsMemory() && cache.isNotEmpty()) {
                        for (slot in cache.keys.toList()) {
                            if (fitsMemory()) break
                            if (slot !in persisting && slot !in offered && !plan.isProtectedWindowSlot(slot)) {
                                evict(slot)
                                releaseUnowned()
                            }
                        }
                    }
                    if (blocking) {
                        while (!fitsMemory()) {
                            val victim = neededLast(plan) ?: break
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
                } else {
                    false
                }
            }
            if (acquired) return true
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
        fetch.lastByteExchangeId = 0L
        fetch.prefixServedToReader = false
    }

    private fun bump() {
        revision.bump()
        readSignal.bump()
    }

    private fun checkOpen() {
        if (closed) throw PikPakException(-1, "PikPakStreamReader is closed")
    }

    private fun checkTicketOpen(ticket: TicketHandle) {
        if (closed || ticket.ended) {
            throw PikPakException(-1, "PikPakStreamReader is closed")
        }
        if (ticket.owner.isClosed) {
            throw DemandWithdrawnException("TransferDemand is closed")
        }
    }

    internal suspend fun activeClaimControlForTest(slot: Int): FetchClaimControl? = locked {
        inFlight[slot]?.claimControl
    }

    internal suspend fun awaitClosedForTest() {
        scope.coroutineContext.job.join()
        cleanupComplete.await()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
    internal fun optionalWriteQueueEmptyForTest(): Boolean =
        storeWrites?.let { it.isClosedForReceive || it.isEmpty } ?: true

    internal suspend fun demandRangeCountForTest(): Int = locked { wants.sumOf { it.slots.ranges.size } }

    internal suspend fun cachedBytesForTest(): Long = locked { cachedBytes }

    internal suspend fun partialBytesForTest(slot: Int): Int = locked { partials[slot]?.length ?: 0 }

    internal suspend fun ownershipSweepsForTest(): Long = locked { ownershipSweeps }

    internal suspend fun inFlightSlotsForTest(): Int = locked { inFlight.size }

    internal suspend fun accountedBytesForTest(): Long = locked { ownedBytes + activeReservedBytes() }

    internal fun revisionForTest(): Long = revision.value

    internal suspend fun planForTest(): PlanSnapshot = locked { planLocked() }

    internal suspend fun laneCountForTest(owner: TransferDemand): Int = locked {
        owners[owner]?.lanes?.size ?: 0
    }

    internal suspend fun readAheadDepthForTest(owner: TransferDemand, position: Long): Long = locked {
        val budget = effectiveOwnerBudgetBytesLocked(owner.intent)
        val windowEnd = (position + budget).coerceAtMost(size)
        if (windowEnd <= position) return@locked 0L
        var covered = position
        while (covered < windowEnd) {
            val slot = slotOf(covered)
            if (slot !in cache && slot !in inFlight) break
            covered = (slot + 1).toLong() * blockSize
        }
        covered.coerceAtMost(windowEnd) - position
    }

    private fun effectiveCacheMediaRateLocked(): Long? {
        var maxSampleRate = 0L
        for (state in owners.values) {
            val owner = state.owner
            if (owner.isClosed) continue
            val intent = owner.intent
            if (intent.service == ServiceIntent.PAUSED) continue
            val sampleRate = intent.playbackSample
                ?.takeIf { it.generation == intent.epoch }
                ?.mediaBytesPerSecond
                ?: continue
            if (sampleRate > maxSampleRate) {
                maxSampleRate = sampleRate
            }
        }
        val best = maxOf(mediaBytesPerSecond ?: 0L, maxSampleRate)
        return best.takeIf { it > 0L }
    }

    private fun effectiveReadAheadConcurrency(): Int {
        if (readAheadConcurrency != DEFAULT_READ_AHEAD_CONCURRENCY) return readAheadConcurrency
        val baseConcurrency = if (
            foregroundStreams != null &&
            hasForegroundStreamDemand() &&
            foregroundStreams.active.value <= 1
        ) {
            minOf(SINGLE_STREAM_READ_AHEAD_CONCURRENCY, concurrency)
        } else {
            readAheadConcurrency
        }
        val rate = effectiveCacheMediaRateLocked() ?: return baseConcurrency
        if (rate < HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC) return baseConcurrency
        val scaled = ((rate + BYTES_PER_SEC_PER_READ_AHEAD_WORKER - 1L) / BYTES_PER_SEC_PER_READ_AHEAD_WORKER).toInt()
        return scaled.coerceIn(baseConcurrency, concurrency)
    }

    private fun highBitratePlaybackSpan(): Int {
        val rate = effectiveCacheMediaRateLocked() ?: return 1
        if (rate < HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC) return 1
        val targetSpanBytes = (rate / 4L).coerceIn(blockSize, 4L * blockSize)
        return (targetSpanBytes / blockSize).toInt().coerceIn(1, 4)
    }

    internal suspend fun claimForTest(): Fetch? = locked { claimNext(enforceWorkerLimit = false) }

    internal suspend fun abandonForTest(fetch: Fetch): Unit = abandon(fetch)

    internal companion object {
        const val FINISH_IN_FLIGHT_PRIORITY = -1
        private const val MAX_LANES_PER_OWNER = 4

        /** Default 4-way striping across 256 KiB slots = 1 MiB read-ahead window. */
        const val DEFAULT_READ_AHEAD_CONCURRENCY = 4
        const val SINGLE_STREAM_READ_AHEAD_CONCURRENCY = 6
        private const val HIGH_BITRATE_READ_AHEAD_THRESHOLD_BYTES_PER_SEC = 3L * 1024 * 1024
        private const val BYTES_PER_SEC_PER_READ_AHEAD_WORKER = 2L * 1024 * 1024

        /** Attempts per block. RangeReader already retries transport failures and refreshes expired links. */
        const val MAX_ATTEMPTS = 2

        /** Optional queue length. Its arrays retain the same ownership credits after eviction. */
        const val STORE_QUEUE_BLOCKS = 16

        /**
         * How long a foreground owner that stopped reading still counts as foreground demand.
         */
        val FOREGROUND_IDLE: Duration = 5.seconds
    }
}
