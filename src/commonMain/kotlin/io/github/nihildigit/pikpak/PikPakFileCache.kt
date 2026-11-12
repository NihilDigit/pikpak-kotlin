package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AdaptiveRangeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The bytes of one file, shared by everything reading it: tickets, streams, prefetches and downloads.
 *
 * One per file. Every [ReadTicket] or [PikPakStreamReader] opened here keeps its own position over
 * one set of 256 KiB blocks and one set of workers, while speculative look-ahead is aggregated per
 * logical [TransferDemand] owner rather than multiplied per connection. Bytes fetched by any ticket,
 * prefetch or download are immediately reusable by all others on the same cache.
 *
 * Blocks live in memory, capped at 64 MiB and evicted least-recently-used from outside every active
 * owner's protected window. A [BlockStore], given at construction, is asked for every block before
 * the network and offered every block the network delivers; a [DurableBlockStore] also takes
 * [download]s.
 *
 * Built by [PikPakClient.fileCache], because the account's connection budget and its count of
 * what is on screen are shared by every cache on the client.
 */
class PikPakFileCache internal constructor(
    internal val cache: BlockCache,
    /** Whether the remote file is waiting for its cold archive to be reactivated. */
    val isReactivating: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
) : AutoCloseable {

    /** Length of the file, in bytes. */
    val size: Long get() = cache.size

    /** Whether [close] was called, or the context the cache was given was cancelled. */
    val isClosed: Boolean get() = cache.closed

    /**
     * Bytes the CDN has handed over to this cache, monotonic, whoever asked for them: every
     * ticket, stream, prefetch and download on it. Blocks read back from the store are not counted.
     */
    val deliveredBytes: Long get() = cache.deliveredBytes

    /**
     * Chunk-level bytes received from the network on this cache, monotonic, including bytes
     * buffered inside in-flight blocks before block completion.
     */
    val receivedBytes: Long get() = cache.receivedBytes

    /**
     * Bytes fetched into memory and evicted before anyone read them.
     */
    val wastedBytes: Long get() = cache.wastedBytes

    /** Updates the expected media consumption rate; null clears the external estimate. */
    fun updateMediaBytesPerSecond(bytesPerSecond: Long?) = cache.updateMediaBytesPerSecond(bytesPerSecond)

    /**
     * Opens a per-read / per-HTTP-Range [ReadTicket] for `[startOffset, endExclusive)` under [owner].
     *
     * Multiple tickets may be open concurrently on the same [owner] without multiplying [owner]'s
     * speculative [BufferBudget]. Releasing one ticket via [ReadTicket.endRead] never closes [owner].
     */
    fun beginRead(
        owner: TransferDemand,
        startOffset: Long,
        endExclusive: Long = size,
    ): ReadTicket = cache.beginRead(owner, startOffset, endExclusive)

    /**
     * Opens a sequential seekable [PikPakStreamReader] over the file with an internal [TransferDemand].
     */
    fun openStream(role: StreamRole = StreamRole.FOREGROUND): PikPakStreamReader =
        PikPakStreamReader(cache, ownsCache = false, initialRole = role)

    /**
     * Opens a sequential seekable [PikPakStreamReader] bound to [owner]. Closing the stream releases
     * only its read ticket without closing [owner].
     */
    fun openStream(owner: TransferDemand, role: StreamRole = StreamRole.FOREGROUND): PikPakStreamReader =
        PikPakStreamReader(cache, ownsCache = false, initialRole = role, externalOwner = owner)

    /** Preparation bytes share [owner]'s live priority and cancel automatically if [owner] closes. */
    fun prefetch(
        ranges: List<LongRange>,
        owner: TransferDemand,
        priority: Int? = null,
    ): Deferred<Unit> = cache.warm(ranges, StreamRole.FOREGROUND, priority, demand = owner)

    /**
     * Fetches [ranges] into memory without opening a stream.
     *
     * Ties at the connection gates go to the older demand. [priority] defaults to [role]'s warm band
     * ([PikPakStreamReader.WARM_PRIORITY] in the foreground). Cancelling the returned job withdraws
     * any fetches nothing else claims.
     */
    fun prefetch(
        ranges: List<LongRange>,
        role: StreamRole = StreamRole.BACKGROUND,
        priority: Int? = null,
    ): Deferred<Unit> = cache.warm(ranges, role, priority)

    /**
     * Fetches [ranges] into the cache's [DurableBlockStore], for keeping the file rather than
     * playing it.
     *
     * @throws IllegalStateException through the job when the cache has no [DurableBlockStore].
     */
    fun download(
        ranges: List<LongRange> = listOf(0L until size),
        role: StreamRole = StreamRole.BACKGROUND,
        priority: Int? = null,
    ): Deferred<Unit> = cache.warm(ranges, role, priority, durable = true)

    /** Drops every block in memory and cancels every fetch, ticket, stream, prefetch and download. */
    override fun close() {
        cache.close()
    }
}

/**
 * A cache over [source], the file of [size] bytes that [storeKey] names in [blockStore].
 */
fun PikPakClient.fileCache(
    source: RangeSource,
    size: Long,
    storeKey: String = "",
    blockStore: BlockStore? = null,
    connectionBudget: Int = this.connectionBudget,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    mediaBytesPerSecond: Long? = null,
): PikPakFileCache = PikPakFileCache(
    BlockCache(
        source = source,
        size = size,
        concurrency = if (connectionBudget >= this.connectionBudget)
            (source as? AdaptiveRangeSource)?.maxConnections ?: connectionBudget
        else connectionBudget,
        parentCoroutineContext = coroutineContext,
        foregroundStreams = foregroundStreams,
        store = blockStore,
        storeKey = storeKey,
        mediaBytesPerSecond = mediaBytesPerSecond,
        scheduler = scheduler,
        downloadLimiter = downloadLimiter,
        memoryBudget = transportMemoryBudget,
        timeSource = timeSource,
    ),
    isReactivating = (source as? PikPakFileHandle)?.isReactivating
        ?: MutableStateFlow(false).asStateFlow(),
)
