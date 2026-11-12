package io.github.nihildigit.pikpak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** Coarse service importance of a logical media or prefetch consumer. */
enum class ServiceIntent {
    WAITING,
    PLAYING,
    PREFETCH,
    PAUSED;

    val priority: Int
        get() = when (this) {
            WAITING -> PikPakStreamReader.BLOCKING_PRIORITY
            PLAYING -> PikPakStreamReader.STREAMING_PRIORITY
            PREFETCH -> PikPakStreamReader.WARM_PRIORITY
            PAUSED -> PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY
        }
}

/** Upper bound on speculative look-ahead owned by one [TransferDemand]. */
data class BufferBudget(
    val targetBuffer: Duration? = null,
    val maxReadAheadBytes: Long = PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES,
) {
    init {
        require(targetBuffer == null || (targetBuffer.isFinite() && targetBuffer >= Duration.ZERO)) {
            "targetBuffer must be finite and non-negative"
        }
        require(maxReadAheadBytes >= 0L) {
            "maxReadAheadBytes must be non-negative"
        }
    }

    companion object {
        val NONE = BufferBudget(targetBuffer = Duration.ZERO, maxReadAheadBytes = 0L)
        val DEFAULT = BufferBudget()
    }
}

/** Immutable consumer state snapshot published to [TransferDemand]. */
data class ConsumerIntent(
    val epoch: Long = 0L,
    val service: ServiceIntent = ServiceIntent.WAITING,
    val bufferBudget: BufferBudget = BufferBudget.DEFAULT,
    val playbackSample: PlaybackBufferSnapshot? = null,
) {
    init {
        require(epoch >= 0L) { "epoch must be non-negative" }
    }
}

/** Protocol/consumer fact describing why a [ReadTicket] ended. */
enum class ReadEndReason {
    RangeCompleted,
    ConsumerClosed,
    Repositioned,
    Cancelled,
    SourceFailed;

    val allowsFinishInFlight: Boolean
        get() = this == RangeCompleted || this == ConsumerClosed
}

internal class FetchClaimControl(
    val priorityFlow: StateFlow<Int>,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FetchClaimControl>

    private val mutableFinishInFlightOnly = MutableStateFlow(false)
    val finishInFlightOnlyFlow: StateFlow<Boolean> get() = mutableFinishInFlightOnly

    var finishInFlightOnly: Boolean
        get() = mutableFinishInFlightOnly.value
        set(value) {
            mutableFinishInFlightOnly.value = value
        }

    @Volatile
    var inBlockQuantum: Boolean = false

    @Volatile
    var allowedProductionEndOffset: Long = Long.MAX_VALUE

    @Volatile
    var recoveryGoalEndOffset: Long = 0L

    @Volatile
    var recoveryGoalRevision: Long = 0L

    @Volatile
    var consumedRecoveryRevision: Long = -1L

    private val exchangeSeq = MutableStateFlow(0L)
    private val liveExchangeState = MutableStateFlow(0L)
    private val deliveredExchangeState = MutableStateFlow(0L)

    val liveExchangeId: Long get() = liveExchangeState.value
    val deliveredExchangeId: Long get() = deliveredExchangeState.value

    fun beginExchange(): Long {
        while (true) {
            val cur = exchangeSeq.value
            val next = cur + 1L
            if (exchangeSeq.compareAndSet(cur, next)) {
                liveExchangeState.value = next
                return next
            }
        }
    }

    fun markExchangeDelivered(exchangeId: Long) {
        if (exchangeId > 0L) {
            deliveredExchangeState.value = exchangeId
        }
    }

    fun endExchange(exchangeId: Long) {
        liveExchangeState.compareAndSet(exchangeId, 0L)
    }

    fun clearExchange() {
        liveExchangeState.value = 0L
    }
}

/**
 * Logical demand owner shared by preparation and byte reads.
 *
 * Holds an immutable [ConsumerIntent] snapshot and exposes [priorityFlow] for preparation queues
 * and signed-URL admission. An idle owner with no active [ReadTicket] or prefetch claims holds
 * zero connections and issues zero network requests.
 */
class TransferDemand(
    initialIntent: ConsumerIntent = ConsumerIntent(),
) : AbstractCoroutineContextElement(Key), AutoCloseable {
    companion object Key : CoroutineContext.Key<TransferDemand>

    private data class Snapshot(
        val intent: ConsumerIntent,
        val closed: Boolean,
        val speculationRevision: Long,
    ) {
        val effectivePriority: Int
            get() = if (closed) Int.MIN_VALUE else intent.service.priority
    }

    private val sanitizedInitial = initialIntent.let { init ->
        if (init.playbackSample != null && init.playbackSample.generation != init.epoch) {
            init.copy(playbackSample = null)
        } else {
            init
        }
    }

    private val state = MutableStateFlow(
        Snapshot(
            intent = sanitizedInitial,
            closed = false,
            speculationRevision = 0L,
        ),
    )
    private val mutableIntent = MutableStateFlow(sanitizedInitial)
    private val mutablePriority = MutableStateFlow(sanitizedInitial.service.priority)
    // 0 = IDLE, 1 = DRAINING, 2 = DIRTY
    private val drainState = MutableStateFlow(0)
    private val listeners = MutableStateFlow<List<() -> Unit>>(emptyList())

    val intentFlow: StateFlow<ConsumerIntent> = mutableIntent.asStateFlow()
    val intent: ConsumerIntent get() = state.value.intent

    val priorityFlow: StateFlow<Int> = mutablePriority.asStateFlow()
    val priority: Int get() = state.value.effectivePriority

    val isClosed: Boolean get() = state.value.closed

    internal val speculationRevision: Long get() = state.value.speculationRevision

    /** Atomically publishes [next] if `!isClosed && next.epoch >= intent.epoch`; drops stale epochs/generations. */
    fun submitIntent(next: ConsumerIntent): Boolean {
        var changed = false
        while (true) {
            val current = state.value
            if (current.closed || next.epoch < current.intent.epoch) return false
            val sanitizedSample = next.playbackSample?.takeIf { it.generation == next.epoch }
            val resolved = if (sanitizedSample === next.playbackSample) next else next.copy(playbackSample = sanitizedSample)
            val withdrawSpec = resolved.epoch > current.intent.epoch ||
                resolved.service == ServiceIntent.PAUSED
            val nextRevision = if (withdrawSpec) current.speculationRevision + 1L else current.speculationRevision
            val updated = Snapshot(
                intent = resolved,
                closed = false,
                speculationRevision = nextRevision,
            )
            changed = updated != current
            if (state.compareAndSet(current, updated)) {
                break
            }
        }
        publishDerivedFlows()
        if (changed) notifyListeners()
        return true
    }

    fun updateIntent(transform: (ConsumerIntent) -> ConsumerIntent): Boolean {
        while (true) {
            val current = state.value
            if (current.closed) return false
            val candidate = transform(current.intent)
            if (candidate.epoch < current.intent.epoch) return false
            val sanitizedSample = candidate.playbackSample?.takeIf { it.generation == candidate.epoch }
            val resolved = if (sanitizedSample === candidate.playbackSample) candidate else candidate.copy(playbackSample = sanitizedSample)
            val withdrawSpec = resolved.epoch > current.intent.epoch ||
                resolved.service == ServiceIntent.PAUSED
            val nextRevision = if (withdrawSpec) current.speculationRevision + 1L else current.speculationRevision
            val updated = Snapshot(
                intent = resolved,
                closed = false,
                speculationRevision = nextRevision,
            )
            if (state.compareAndSet(current, updated)) {
                publishDerivedFlows()
                if (updated != current) notifyListeners()
                return true
            }
        }
    }

    /** Attaches [sample] iff `!isClosed && sample.generation == intent.epoch`; drops stale generations. */
    fun reportPlaybackBuffer(sample: PlaybackBufferSnapshot): Boolean {
        while (true) {
            val current = state.value
            if (current.closed || sample.generation != current.intent.epoch) return false
            val resolved = current.intent.copy(playbackSample = sample)
            val updated = current.copy(intent = resolved)
            if (state.compareAndSet(current, updated)) {
                publishDerivedFlows()
                if (updated != current) notifyListeners()
                return true
            }
        }
    }

    /** Cancels all speculative claims (`LookAhead` + `FinishInFlight`) immediately (`0ms` grace) without closing owner. */
    fun withdrawSpeculation() {
        while (true) {
            val current = state.value
            if (current.closed) return
            val updated = current.copy(speculationRevision = current.speculationRevision + 1L)
            if (state.compareAndSet(current, updated)) {
                notifyListeners()
                return
            }
        }
    }

    /** Permanently closes owner and cancels all `Required`, `LookAhead`, `FinishInFlight`, and owner-bound `StoreRange` claims (`0ms` grace). */
    override fun close() {
        while (true) {
            val current = state.value
            if (current.closed) return
            val updated = current.copy(
                closed = true,
                speculationRevision = current.speculationRevision + 1L,
            )
            if (state.compareAndSet(current, updated)) {
                publishDerivedFlows()
                notifyListeners()
                return
            }
        }
    }

    private fun publishDerivedFlows() {
        while (true) {
            when (drainState.value) {
                0 -> if (drainState.compareAndSet(0, 1)) break
                1 -> if (drainState.compareAndSet(1, 2)) return
                else -> return
            }
        }
        while (true) {
            drainState.value = 1
            val snap = state.value
            if (mutableIntent.value != snap.intent) {
                mutableIntent.value = snap.intent
            }
            val prio = snap.effectivePriority
            if (mutablePriority.value != prio) {
                mutablePriority.value = prio
            }
            if (drainState.compareAndSet(1, 0)) {
                if (state.value === snap) return
                if (!drainState.compareAndSet(0, 1)) return
            }
        }
    }

    internal fun addListener(listener: () -> Unit): AutoCloseable {
        while (true) {
            val current = listeners.value
            if (listeners.compareAndSet(current, current + listener)) break
        }
        return AutoCloseable {
            while (true) {
                val current = listeners.value
                val next = current.filterNot { it === listener }
                if (listeners.compareAndSet(current, next)) break
            }
        }
    }

    private fun notifyListeners() {
        val snapshot = listeners.value
        for (i in 0 until snapshot.size) {
            snapshot[i].invoke()
        }
    }
}

/**
 * Per-read / per-HTTP-Range handle over a [PikPakFileCache].
 *
 * Multiple tickets may be open concurrently on the same [owner] and cache. Releasing one ticket
 * via [endRead] never closes [owner] or disturbs sibling tickets.
 */
interface ReadTicket : AutoCloseable {
    val owner: TransferDemand
    val epoch: Long
    val startOffset: Long
    val position: Long
    val endExclusive: Long

    /** Returns `> 0` arrived prefix immediately without waiting for a full 256 KiB block, or `-1` at `minOf(endExclusive, size)`. */
    suspend fun readSome(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): Int

    /**
     * Non-suspending release of ONLY this ticket (never closes [owner]).
     *
     * If [reason] satisfies [ReadEndReason.allowsFinishInFlight] and the single currently-issued
     * block (`<= 256 KiB`) has started on network and buffered `> 0` bytes while [owner] is open,
     * at current speculation epoch, and `service != PAUSED`, transitions that 1 block to
     * `FinishInFlight` (`0` new HTTP requests, `0` retries, `0` next-block extension, `0ms` preemption).
     */
    fun endRead(reason: ReadEndReason = ReadEndReason.RangeCompleted)

    fun endRead(allowFinishInFlight: Boolean) = endRead(
        if (allowFinishInFlight) ReadEndReason.RangeCompleted else ReadEndReason.Cancelled,
    )

    override fun close() = endRead(ReadEndReason.ConsumerClosed)
}
