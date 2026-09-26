package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import kotlinx.coroutines.Deferred
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Whether a [PikPakStreamReader] is what the user is watching now or is only
 * getting ready for later. See [PikPakStreamReader.role].
 */
enum class StreamRole { FOREGROUND, BACKGROUND }

/**
 * Serves one remote file to a media player as a seekable byte stream.
 *
 * This is a transport and nothing else. It has no download policy: it does not
 * decide what the file is worth keeping and fetches only what the read position
 * implies and what callers explicitly [prefetch]. PikPak is an online source, so
 * the player is the one that knows what it will read next, and every playback
 * fault this design replaced came from a policy layer second-guessing it.
 *
 * A reader is one read position over a block cache. Readers opened from the
 * same [PikPakFileHandle] share that handle's cache: any number of them can
 * read at once, each keeping its own read-ahead window filled, and bytes one of
 * them fetched are there for the rest. A player that opens a second connection
 * for a seek therefore opens a second reader instead of taking the first one's
 * position. A reader built with the public constructor owns a cache of its own.
 *
 * The cache holds fixed-size blocks, evicted least-recently-used from outside
 * every reader's window, and fetches them over up to `concurrency` range
 * requests at once. One fetch covers a block from a start or a seek and two
 * once the stream has settled, so a cold open spreads over all connections
 * while a settled stream pays half the per-request latency.
 *
 * The block a reader is blocked on is requested at a higher priority than
 * read-ahead, so it wins a contended connection slot both against this file's
 * own read-ahead and against every other file on the account.
 *
 * Every file on an account shares one connection budget, so readers also carry
 * a [role]. A background reader — one warming a file the user may open next —
 * asks on a band below every foreground request. Priority only decides who
 * takes the next free slot, though, and a slot a background read already holds
 * is not taken back; so while the account has foreground demand, a file with
 * none of its own keeps at most [BACKGROUND_WORKERS_WHILE_FOREGROUND] requests
 * in flight, each a single block.
 *
 * A block that cannot be fetched fails the read waiting on it; the reader stays
 * usable and a later read of that block tries again.
 *
 * Not safe for concurrent reads through one reader — one position, one caller.
 * [close] while a read is parked is supported and is how a player aborts one;
 * it is the one call that may run concurrently with [read].
 */
class PikPakStreamReader internal constructor(
    private val cache: BlockCache,
    /** Whether closing this reader closes [cache] too: true when nothing else can reach it. */
    private val ownsCache: Boolean,
    initialRole: StreamRole,
) : AutoCloseable {

    /**
     * @param source where the bytes come from. Pass a [PikPakFileHandle] when
     *   the file id may move under the reader; a plain [RangeReader] adapted
     *   with [asRangeSource] is enough for a file that will not. Prefer
     *   [PikPakFileHandle.openStream], which shares one cache between readers.
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
        ),
        ownsCache = true,
        initialRole = initialRole,
    )

    private val cursor = cache.openCursor(initialRole)

    /** Length of the remote file. */
    val size: Long get() = cache.size

    /**
     * Bytes fetched and then evicted without ever being served, across every
     * reader sharing this cache.
     *
     * The other half of a read-ahead measurement. A read that waits says the
     * window was too small; nothing said it was too big, so the depth could
     * only be set by guessing -- and a guess in the narrow direction made a
     * cold open two and a half times slower before this existed.
     */
    val wastedBytes: Long get() = cache.wastedBytes

    /**
     * Bytes the CDN has handed over to this reader's cache, monotonic. The
     * basis of a speed readout. Shared by every reader of a handle, so one
     * number describes the file.
     */
    val deliveredBytes: Long get() = cache.deliveredBytes

    // Set by seekTo, consumed by the read that first serves bytes at the new
    // position. What a viewer feels is the interval up to the byte, not up to
    // the fetch, so the measurement ends in read and not in the worker.
    @Volatile
    private var seekTarget = -1L

    @Volatile
    private var seekMark: TimeMark? = null

    /** How long the last [seekTo] took to produce its first byte. Null until one has. For the link probes. */
    @Volatile
    internal var lastSeekLatency: Duration? = null
        private set

    /**
     * What the user is watching now, or a file warmed for later. See the class
     * documentation for what a background reader gives up.
     *
     * Changing it keeps the cache and takes effect from the next request:
     * requests already queued keep the priority they were issued at.
     */
    var role: StreamRole
        get() = cursor.role
        set(value) = cache.setRole(cursor, value)

    /**
     * How far past the read position this reader keeps the stream filled, at
     * most the depth the cache was built with.
     *
     * Lower it when the consumer will not read far: a player showing a short
     * excerpt of a long file needs seconds ahead, not the tens of megabytes a
     * feature-length playback wants, and every block beyond what it reads is
     * taken from the budget other files on the account share.
     */
    var readAheadLimit: Long
        get() = cursor.readAhead
        set(value) = cache.setReadAhead(cursor, value)

    /** Current read position, in bytes from the start of the file. */
    val position: Long get() = cursor.position

    val bytesRemaining: Long get() = (size - position).coerceAtLeast(0)

    /**
     * Moves the read position. Cheap: nothing is fetched here. Fetches no
     * reader and no [prefetch] wants any more are cancelled rather than left
     * to finish — what the reader needs back is the connection.
     */
    suspend fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        if (position == cursor.position) return
        seekTarget = position
        seekMark = TimeSource.Monotonic.markNow()
        cache.seek(cursor, position)
    }

    /**
     * Fills [buffer] from the current position and advances it.
     *
     * Returns the number of bytes written, which is at most one block's worth
     * however large [length] is, or -1 at end of file. Suspends until the block
     * under the read position is in hand, and throws if it cannot be fetched.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val pos = cursor.position
        val read = cache.read(cursor, buffer, offset, length)
        if (read > 0 && seekTarget == pos) {
            seekMark?.let { lastSeekLatency = it.elapsedNow() }
            seekTarget = -1
            seekMark = null
        }
        return read
    }

    /**
     * Fetches [ranges] into the cache without moving the read position.
     *
     * For bytes a caller knows the consumer will ask for next and the read
     * position cannot predict: a container index at the far end of the file,
     * the start of an excerpt in a file nobody is playing yet. The position
     * stays put and a later [seekTo] does not cancel it.
     *
     * The blocks go out at [priority], by default [WARM_PRIORITY] for a
     * foreground reader and the background read-ahead band for a background
     * one. Ranges are clamped to the file.
     *
     * The returned job completes once every block is cached, fails when one
     * cannot be fetched, and is cancelled by [close]. Nothing waits on it
     * unless the caller does.
     */
    fun prefetch(ranges: List<LongRange>, priority: Int? = null): Deferred<Unit> =
        cache.warm(ranges, cursor.role, priority)

    /**
     * Gives the read position up and fails a read parked on it.
     *
     * Deliberately not suspending, unlike the rest of the surface. A player
     * releases its input from a lifecycle callback that has no coroutine, and
     * aborting a parked read from another thread is a supported way to stop
     * playback. The shared cache stays with its handle; a reader that owns its
     * cache drops it too.
     */
    override fun close() {
        cache.closeCursor(cursor)
        if (ownsCache) cache.close()
    }

    /** Cached bytes held right now. For tests asserting the memory cap. */
    internal suspend fun cachedBytesForTest(): Long = cache.cachedBytesForTest()

    /** Slots currently claimed by a fetch. A leaked claim shows up here as a count that never falls. */
    internal suspend fun inFlightSlotsForTest(): Int = cache.inFlightSlotsForTest()

    /** See [BlockCache.readAheadDepthForTest]. */
    internal suspend fun readAheadDepthForTest(): Long = cache.readAheadDepthForTest(cursor)

    internal suspend fun claimForTest(): BlockCache.Fetch? = cache.claimForTest()

    internal suspend fun abandonForTest(fetch: BlockCache.Fetch): Unit = cache.abandonForTest(fetch)

    // Public: every member here is a documented default or a point on the priority scale, and a
    // caller placing its own reads among these needs to name them.
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
         * whole cache and nothing outside it can be evicted, so read-ahead stops
         * a few blocks short and from then on only advances one block per block
         * the reader consumes. The other half holds what has already been read,
         * which is what a backward seek reads from.
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

        /**
         * What a container index the demuxer needs before it can play at all
         * is fetched at: the Cues of a Matroska file, the moov of an MP4 that
         * keeps it at the end.
         *
         * Above [BLOCKING_PRIORITY] on purpose. Until the index arrives there is
         * no first frame, so no playback position for anything to be blocked at;
         * what this outranks is the read for a frame that cannot be shown yet.
         */
        const val INDEX_PRIORITY = 101

        /** What a reader asks for the block a caller is waiting on. */
        const val BLOCKING_PRIORITY = 100

        /**
         * What a reader asks for blocks ahead of the read position.
         *
         * Anything a caller wants served ahead of its own read-ahead but behind
         * the block playback is stopped on belongs between this and
         * [BLOCKING_PRIORITY].
         */
        const val READ_AHEAD_PRIORITY = 10

        /**
         * What a foreground [prefetch] asks at by default: behind everything
         * that is playing, ahead of everything in the background. Warming the
         * file the user will see next must not starve the one they are seeing.
         */
        const val WARM_PRIORITY = 8

        /** What a [StreamRole.BACKGROUND] reader asks for the block it is blocked on: below all foreground work. */
        const val BACKGROUND_BLOCKING_PRIORITY = 5

        /** What a [StreamRole.BACKGROUND] reader asks for blocks ahead of its read position. */
        const val BACKGROUND_READ_AHEAD_PRIORITY = 1

        /**
         * Requests a file with no foreground demand keeps in flight while the
         * account has some.
         *
         * Priority cannot take a slot back, and a foreground reader whose
         * read-ahead window is full holds none; left alone the background files
         * take the whole budget and the next playback read queues behind all of
         * them. Two keeps warming moving while leaving most of the budget free,
         * the same figure Animeko settled on for its cache workers.
         */
        const val BACKGROUND_WORKERS_WHILE_FOREGROUND = 2
    }
}
