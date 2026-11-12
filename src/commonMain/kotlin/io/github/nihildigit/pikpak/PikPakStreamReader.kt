package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import kotlinx.coroutines.Deferred
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark

/**
 * Whether a [PikPakStreamReader] or [PikPakFileCache.prefetch] is serving foreground playback or
 * background warming.
 */
enum class StreamRole { FOREGROUND, BACKGROUND }

/**
 * Sequential seekable byte stream facade over [BlockCache] backed by a [TransferDemand] and
 * [ReadTicket].
 *
 * Callers serving concurrent HTTP Range requests for a media player should open per-request
 * [ReadTicket]s via [PikPakFileCache.beginRead] over a shared [TransferDemand]. [PikPakStreamReader]
 * remains the simple single-cursor stream API for sequential callers and direct byte consumers.
 *
 * Not safe for concurrent [read] calls on the same instance; [close] may run concurrently from
 * another thread to abort a parked [read].
 */
class PikPakStreamReader internal constructor(
    private val cache: BlockCache,
    /** Whether closing this reader closes [cache] too: true when nothing else can reach it. */
    private val ownsCache: Boolean,
    initialRole: StreamRole,
    externalOwner: TransferDemand? = null,
) : AutoCloseable {

    internal constructor(
        source: RangeSource,
        size: Long,
        concurrency: Int,
        parentCoroutineContext: CoroutineContext,
        blockSize: Long,
        readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
        memoryCapBytes: Long = DEFAULT_MEMORY_CAP_BYTES,
        wideBlockThresholdBytes: Long = DEFAULT_WIDE_BLOCK_THRESHOLD_BYTES,
        initialRole: StreamRole = StreamRole.FOREGROUND,
        foregroundStreams: ForegroundStreams? = null,
    ) : this(
        BlockCache(
            source = source,
            size = size,
            concurrency = concurrency,
            parentCoroutineContext = parentCoroutineContext,
            blockSize = blockSize,
            readAheadBytes = readAheadBytes,
            memoryCapBytes = memoryCapBytes,
            wideBlockThresholdBytes = wideBlockThresholdBytes,
            foregroundStreams = foregroundStreams,
            readAheadConcurrency = maxOf(BlockCache.DEFAULT_READ_AHEAD_CONCURRENCY, concurrency),
        ),
        ownsCache = true,
        initialRole = initialRole,
    )

    private val ownsOwner: Boolean = externalOwner == null

    internal val owner: TransferDemand = externalOwner ?: TransferDemand(
        ConsumerIntent(
            epoch = 0L,
            service = ServiceIntent.PLAYING,
            bufferBudget = BufferBudget(maxReadAheadBytes = cache.readAheadBytes),
        ),
    )

    @Volatile
    private var currentPosition: Long = 0L

    @Volatile
    private var startedReading: Boolean = false

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var currentTicket: BlockCache.TicketHandle? = null

    /** Length of the remote file. */
    val size: Long get() = cache.size

    /** Bytes fetched and then evicted without ever being served, across every consumer sharing [cache]. */
    val wastedBytes: Long get() = cache.wastedBytes

    /** Bytes the CDN has handed over to this reader's cache, monotonic. */
    val deliveredBytes: Long get() = cache.deliveredBytes

    @Volatile
    private var seekTarget = -1L

    @Volatile
    private var seekMark: TimeMark? = null

    /** How long the last [seekTo] took to produce its first byte. Null until one has. */
    @Volatile
    internal var lastSeekLatency: Duration? = null
        private set

    /** Foreground vs background band for this reader's ticket and [prefetch]es. */
    @Volatile
    var role: StreamRole = initialRole
        set(value) {
            if (field == value) return
            field = value
            val ticket = currentTicket
            if (ticket != null) {
                cache.setTicketRole(ticket, value)
            } else if (value == StreamRole.FOREGROUND) {
                cache.markForegroundOwnerActive(owner)
            }
        }

    init {
        if (initialRole == StreamRole.FOREGROUND) {
            cache.markForegroundOwnerActive(owner)
        }
    }

    /** Current read position, in bytes from the start of the file. */
    val position: Long get() = currentTicket?.position ?: currentPosition

    val bytesRemaining: Long get() = (size - position).coerceAtLeast(0L)

    /**
     * Moves the read position and withdraws speculative work left behind by an internal owner.
     */
    suspend fun seekTo(position: Long) {
        require(position >= 0L) { "position must be >= 0, got $position" }
        checkNotClosed()
        if (position == this.position) return
        seekTarget = position
        seekMark = cache.timeSource.markNow()
        val old = currentTicket
        currentTicket = null
        currentPosition = position
        old?.endRead(ReadEndReason.Repositioned)
        if (ownsOwner) {
            owner.updateIntent { it.copy(epoch = it.epoch + 1L) }
        }
        if (startedReading && !closed && position < size) {
            val next = cache.beginReadInternal(owner, position, size, initialRoleOverride = role, eagerRegister = true)
            if (closed) {
                next.endRead(ReadEndReason.Cancelled)
            } else {
                currentTicket = next
            }
        }
    }

    /**
     * Fills [buffer] from the current position and advances it, returning `-1` at end of file.
     * May return an arrived prefix smaller than a block without waiting for the rest of the block.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkNotClosed()
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "invalid buffer slice offset=$offset length=$length size=${buffer.size}"
        }
        val pos = this.position
        if (pos >= size) return -1
        if (length == 0) return 0
        startedReading = true
        var ticket = currentTicket
        if (ticket == null || ticket.ended || ticket.position != pos) {
            ticket?.endRead(ReadEndReason.Repositioned)
            ticket = cache.beginReadInternal(owner, pos, size, initialRoleOverride = role)
            if (closed) {
                ticket.endRead(ReadEndReason.Cancelled)
                throw PikPakException(-1, "PikPakStreamReader is closed")
            }
            currentTicket = ticket
        }
        val read = ticket.readSome(buffer, offset, length)
        if (read > 0) {
            currentPosition = ticket.position
            if (seekTarget == pos) {
                seekMark?.let { lastSeekLatency = it.elapsedNow() }
                seekTarget = -1L
                seekMark = null
            }
        }
        return read
    }

    /**
     * Fetches [ranges] into the cache without moving the read position.
     */
    fun prefetch(ranges: List<LongRange>, priority: Int? = null): Deferred<Unit> =
        cache.warm(ranges, role, priority)

    /**
     * Releases this reader's ticket and aborts any parked [read]. Never closes an externally
     * supplied [TransferDemand] owner.
     */
    override fun close() {
        closed = true
        val ticket = currentTicket
        currentTicket = null
        ticket?.endRead(ReadEndReason.ConsumerClosed)
        if (ownsOwner) owner.close()
        if (ownsCache) cache.close()
    }

    private fun checkNotClosed() {
        if (closed || cache.closed || owner.isClosed) {
            throw PikPakException(-1, "PikPakStreamReader is closed")
        }
    }

    internal suspend fun cachedBytesForTest(): Long = cache.cachedBytesForTest()

    internal suspend fun inFlightSlotsForTest(): Int = cache.inFlightSlotsForTest()

    internal suspend fun readAheadDepthForTest(): Long = cache.readAheadDepthForTest(owner, position)

    internal suspend fun claimForTest(): BlockCache.Fetch? = cache.claimForTest()

    internal suspend fun abandonForTest(fetch: BlockCache.Fetch): Unit = cache.abandonForTest(fetch)

    companion object {
        const val DEFAULT_BLOCK_SIZE: Long = 256L * 1024
        const val DEFAULT_READ_AHEAD_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_MEMORY_CAP_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_WIDE_BLOCK_THRESHOLD_BYTES: Long = 8L * 1024 * 1024

        const val INDEX_PRIORITY = 101
        const val BLOCKING_PRIORITY = 100
        const val STREAMING_PRIORITY = 50
        const val READ_AHEAD_PRIORITY = 10
        const val MICRO_PROBE_PRIORITY = 12
        const val FAR_READ_AHEAD_PRIORITY = 6
        const val WARM_PRIORITY = 8
        const val BACKGROUND_BLOCKING_PRIORITY = 5
        const val BACKGROUND_READ_AHEAD_PRIORITY = 1

        const val BACKGROUND_WORKERS_WHILE_FOREGROUND = 2
    }
}
