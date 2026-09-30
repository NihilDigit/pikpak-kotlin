package io.github.nihildigit.pikpak

import kotlinx.coroutines.Deferred
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The bytes of one file, shared by everything reading it: streams, prefetches and downloads.
 *
 * One per file. Every stream opened here keeps its own position and read-ahead window over one
 * set of 256 KiB blocks and one set of workers, so a player's second connection, a prefetch of
 * the container index and a download of the whole file each read what the others fetched
 * instead of fetching it again. A second cache for the same file starts from nothing.
 *
 * The cache owns no link and knows no file object. It reads through a [RangeSource], normally
 * the file's [PikPakFileHandle], which is what keeps the reads going across expiring
 * signatures and file objects that have to be rebuilt; see [PikPakFileHandle.openCache]. A
 * handle used to own its cache, which tied the bytes to the object minting links for them and
 * made the handle carry three jobs at once.
 *
 * Blocks live in memory, capped at 64 MiB and evicted least-recently-used from outside every
 * stream's window. A [BlockStore], given at construction, is asked for every block before the
 * network and offered every block the network delivers; a [DurableBlockStore] also takes
 * [download]s.
 *
 * Built by [PikPakClient.fileCache], because the account's connection budget and its count of
 * what is on screen are shared by every cache on the client.
 */
class PikPakFileCache internal constructor(
    private val cache: BlockCache,
) : AutoCloseable {

    /** Length of the file, in bytes. */
    val size: Long get() = cache.size

    /** Whether [close] was called, or the context the cache was given was cancelled. */
    val isClosed: Boolean get() = cache.closed

    /**
     * Bytes the CDN has handed over to this cache, monotonic, whoever asked for them: every
     * stream, prefetch and download on it. The basis of a speed readout. Blocks read back from
     * the store are not counted.
     */
    val deliveredBytes: Long get() = cache.deliveredBytes

    /**
     * Bytes fetched into memory and evicted before anyone read them. A read that waits says
     * read-ahead was too shallow; this is the only signal that it was too deep.
     */
    val wastedBytes: Long get() = cache.wastedBytes

    /**
     * Opens a read position over the file, for playing it.
     *
     * Any number can be open at once. A player that opens a second connection to seek opens
     * a second stream; nothing has to be cancelled for it. The result must be closed; closing
     * it gives up its position and leaves the cache to everyone else.
     *
     * @param role [StreamRole.BACKGROUND] for a file being warmed ahead of the one on screen.
     *   It can be changed later without losing the cache; see [PikPakStreamReader.role].
     */
    fun openStream(role: StreamRole = StreamRole.FOREGROUND): PikPakStreamReader =
        PikPakStreamReader(cache, ownsCache = false, initialRole = role)

    /**
     * Fetches [ranges] into memory without opening a stream, for bytes a stream will ask for
     * next and cannot predict: the head of the file the user will play next, a container index
     * at the far end.
     *
     * Ties at the connection gates go to the older demand, so files prefetched in the order
     * they will be played finish in that order. [priority] defaults to the [role]'s warm band,
     * [PikPakStreamReader.WARM_PRIORITY] in the foreground.
     *
     * The job completes once every block is cached and fails when one cannot be fetched.
     * Cancelling it withdraws the request, and fetches only it wanted are cancelled.
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
     * What the store already holds is skipped, so calling this again after a failure, a
     * cancellation or a restart continues where the store left off. The rest is fetched in the
     * order [ranges] gives it — a player's header and index first, say, then the middle — and
     * written as it arrives, the writes paced by the store: a disk that cannot keep up slows
     * the download rather than filling memory. Blocks a stream already has in memory are
     * written from there, and blocks written here are what a stream reads next, so a file
     * downloaded while it plays is fetched once.
     *
     * The job completes once every block is held by the store. It fails when a block cannot be
     * fetched (after the retries every read carries) or cannot be written, and retrying is the
     * caller's: call again. Cancelling it withdraws what it had not fetched yet.
     *
     * By default a background download: priority [PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY],
     * and at most [PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND] requests while the
     * account plays something else.
     *
     * @throws IllegalStateException through the job when the cache has no [DurableBlockStore].
     */
    fun download(
        ranges: List<LongRange> = listOf(0L until size),
        role: StreamRole = StreamRole.BACKGROUND,
        priority: Int? = null,
    ): Deferred<Unit> = cache.warm(ranges, role, priority, durable = true)

    /** Drops every block in memory and cancels every fetch, stream, prefetch and download. */
    override fun close() {
        cache.close()
    }
}

/**
 * A cache over [source], the file of [size] bytes that [storeKey] names in [blockStore].
 *
 * [source] is normally a [PikPakFileHandle] and [storeKey] its [PikPakFileHandle.contentKey];
 * [PikPakFileHandle.openCache] fills in both. Any [RangeSource] works, a bare [RangeReader]
 * over a link that will not expire included.
 *
 * @param connectionBudget requests in flight at once for this file, taken from the account's
 *   budget as well. The default is the per-link cap PikPak enforces.
 * @param coroutineContext where the workers run. Cancelling it closes the cache.
 */
fun PikPakClient.fileCache(
    source: RangeSource,
    size: Long,
    storeKey: String = "",
    blockStore: BlockStore? = null,
    connectionBudget: Int = this.connectionBudget,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): PikPakFileCache = PikPakFileCache(
    BlockCache(
        source = source,
        size = size,
        concurrency = connectionBudget,
        parentCoroutineContext = coroutineContext,
        foregroundStreams = foregroundStreams,
        store = blockStore,
        storeKey = storeKey,
    ),
)
