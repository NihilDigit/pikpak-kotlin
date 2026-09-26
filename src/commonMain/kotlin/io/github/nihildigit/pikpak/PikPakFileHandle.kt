package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.PriorityGate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A signed CDN link and the moment it stops being accepted. */
data class VariantLink(
    val url: String,
    /** Null when the URL carries no readable expiry; such a link is never refreshed early. */
    val expiresAt: Instant?,
)

/**
 * Keeps one file readable across everything that can invalidate its signed URL.
 *
 * The identity here is the **gcid**, not the file id. PikPak stores content by
 * hash: a gcid creates a file object in one request and no bytes ([instantCreate]),
 * and it keeps working after the object it came from is gone. A file id is one
 * account's handle on that content and can die — trashed, evicted, swept by a
 * cleanup elsewhere. So the id is treated as a cache of the gcid rather than as
 * the thing being held, and losing it costs one extra request.
 *
 * The healing ladder, cheapest first:
 *  - `Initial` / `Expired`: ask for this file's detail again and take the fresh
 *    link. One request, measured around 180 ms.
 *  - `Rejected`, no id yet, or a detail request that came back 404: the id is
 *    dead. A new file object is created from [gcid] and the ladder continues.
 *    Two requests, around 380 ms.
 *
 * Nothing in either rung needs the caller. An earlier revision took a
 * `FileRelocator` because only the caller knew how to find the content again —
 * it held the magnet and the path. With the gcid in hand the SDK can rebuild
 * the file itself, so that interface and its `onRelocated` write-back are gone.
 *
 * A caller that treats file objects as leases — creating one only to mint a
 * link, since a link outlives the object that produced it — hears about every
 * object through [onObjectMinted], the rebuilt ones included. Without it the
 * second rung of the ladder would strand an object nobody can name.
 *
 * Expiry replaces the reader rather than telling it to refresh, because
 * [RangeReader] exposes no such call. Replacing is safe: a reader holds no
 * connection between reads, and a read already in flight keeps the instance it
 * started on.
 *
 * The variant is fixed for the life of the handle. Re-running preference
 * selection mid-file would change the byte stream under offsets the caller has
 * already read past.
 */
class PikPakFileHandle(
    private val client: PikPakClient,
    /** Content hash, in either case. The one identifier here that cannot go stale. */
    gcid: String,
    /** Length of the original file. Needed to recreate it, and by [openStream]. */
    val size: Long,
    /** Name given to a recreated file object. Cosmetic; the gcid decides the bytes. */
    val name: String,
    /**
     * A file object already holding this content, if the caller has one. Saves
     * the first [instantCreate]. A dead id is not an error — it costs one
     * rebuild and then behaves as if none had been passed.
     */
    initialFileId: String? = null,
    /**
     * Which representation to read. Null is the original. A transcoded variant
     * only exists if someone has played this content on PikPak before; see
     * [variants].
     */
    private val mediaId: String? = null,
    /** Where a rebuilt file object lands. Empty is the root drive. */
    private val parentId: String = "",
    private val connectionBudget: Int = client.connectionBudget,
    /**
     * Called once with each file object this handle has finished with: either a
     * link has been minted from it and that link now stands on its own, or a
     * rebuild replaced it before it produced one.
     *
     * A caller that treats a file object as a lease deletes it here. Without
     * this, an object created by [rebuild] would be reachable by nobody: the
     * caller never learns its id, and this handle has already replaced it.
     * [initialFileId] is reported the same way, so one rule covers every object
     * that exists, however it came to be.
     *
     * A report can still be outstanding when a handle is abandoned — a rebuild
     * whose detail lookup then failed owes one. [closeAndReport] collects it;
     * [close] cannot, not being suspending.
     */
    private val onObjectMinted: (suspend (String) -> Unit)? = null,
    /**
     * Passed to every [RangeReader] this handle builds, the replacements a
     * refresh makes included, so an observer outlives the reader it watches.
     * See [RangeAttempt] for what it is for.
     */
    private val onRangeAttempt: ((RangeAttempt) -> Unit)? = null,
    private val clock: Clock = Clock.System,
    private val refreshMargin: Duration = DEFAULT_REFRESH_MARGIN,
    /**
     * A link the caller already holds for this variant, typically from the detail it just
     * looked up. The first read uses it instead of asking for the detail again; see
     * [PikPakClient.fileHandle], which fills this in.
     */
    initialLink: VariantLink? = null,
    /** The variant's length when the caller already knows it, which saves a transcode the probe in [streamSize]. */
    streamSize: Long? = null,
    /** Where fetched blocks are kept beyond memory, and looked for first. See [BlockStore]. */
    private val blockStore: BlockStore? = null,
    /** Where the shared cache's workers run. Closing the handle or cancelling this stops them. */
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : RangeSource, AutoCloseable {
    /** Upper case whatever the caller passed, so the [BlockStore] key and the stream-size memo agree. */
    val gcid: String = gcid.canonicalGcid()

    private val mutex = Mutex()

    /** The link [provideUrl] handed out last; reused by a new reader while it stays valid. */
    @Volatile
    private var link: VariantLink? = initialLink?.takeIf { it.url.isNotBlank() }

    private val cacheMutex = Mutex()

    /** Shared by every stream opened on this handle; see [openStream]. */
    @Volatile
    private var blockCache: BlockCache? = null

    /** Guards [unreportedObject] alone, so it is never held across a network call. */
    private val reportMutex = Mutex()

    /** The object [onObjectMinted] still owes a report for, if any. */
    @Volatile
    private var unreportedObject: String? = initialFileId

    /** Outlives any one reader; see where it is handed to [RangeReader]. */
    private val fileGate = PriorityGate(connectionBudget)

    @Volatile
    private var fileId: String? = initialFileId

    @Volatile
    private var reader: RangeReader? = null

    // Written from provideUrl, which RangeReader calls outside this class's
    // mutex, and read inside it.
    @Volatile
    private var expiresAt: Instant? = null

    @Volatile
    private var closed = false

    // Only ever written with the same probed value; the variant it describes
    // cannot change while this handle lives.
    @Volatile
    private var probedSize: Long? = streamSize

    /**
     * The file object currently backing this handle, or null before the first
     * read. Changes whenever the handle has to rebuild. A caller may cache it
     * to save this handle's successor one request, but must not treat it as the
     * file's identity — [gcid] is that.
     */
    val currentFileId: String? get() = fileId

    /**
     * Counting bytes is deliberately not done here.
     *
     * [RangeReader.stats] cannot serve a speed readout through this handle —
     * the counter belongs to one reader, and readers are retired on expiry and
     * on rebuild, so it would jump back to zero mid-file. Each consumer keeps
     * its own monotonic count instead: [PikPakStreamReader.deliveredBytes] for
     * playback. A caller showing one number for a file adds them.
     */
    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T = currentReader().read(start, length, priority, block)

    /** Resolves the link now so the first read does not pay for it. */
    suspend fun prewarm() {
        currentReader().prewarm()
    }

    /**
     * The representations PikPak holds of this content, original first.
     *
     * Empty beyond the original means nobody has played this content on PikPak
     * yet. Transcodes are generated per content and shared by every file object
     * with the same gcid — an instant upload of an already-transcoded gcid
     * arrives with all of them present — but nothing here schedules one.
     * Measured 2026-09-11: a freshly created file with no prior transcode still
     * had only the original after two minutes, including after its bytes were
     * read. So a caller that finds only the original must decide between
     * reading it and giving up; waiting does not help.
     */
    suspend fun variants(): List<MediaVariant> = detail().medias

    /**
     * Opens a seekable read position over this file, for playing it.
     *
     * Every stream opened here shares one block cache and one set of workers:
     * several can read at once, each with its own read-ahead window, and what
     * one fetched the others read from memory. A player that opens a second
     * connection to seek opens a second stream; nothing has to be cancelled
     * for it. Reads go through this handle, so a signature that expires or a
     * file object that has to be rebuilt mid-playback is invisible to them.
     *
     * The result must be closed. Closing it gives up its position and leaves
     * the cache to the handle; [close] on the handle drops the cache.
     *
     * The cache takes its size from [streamSize], its connections from the
     * handle's budget and its workers' context from the handle's
     * `coroutineContext`. None of that is a per-stream argument: it is one
     * cache, and a later caller's arguments either changed it under every
     * other stream or were silently ignored.
     *
     * @param role [StreamRole.BACKGROUND] for a file being warmed ahead of the
     *   one on screen. It can be changed later without losing the cache; see
     *   [PikPakStreamReader.role].
     */
    suspend fun openStream(role: StreamRole = StreamRole.FOREGROUND): PikPakStreamReader =
        PikPakStreamReader(sharedCache(), ownsCache = false, initialRole = role)

    /**
     * Fetches [ranges] into the shared cache without opening a stream, for a
     * file the user may play next: its head, the start of an excerpt, a
     * container index. Streams opened later read them from memory, or from the
     * [BlockStore] if they were written there.
     *
     * Ties at the connection gates go to the older demand, so files warmed in
     * the order they will be played finish in that order rather than sharing
     * the line and finishing together. [priority] defaults to the [role]'s warm
     * band, [PikPakStreamReader.WARM_PRIORITY] in the foreground.
     *
     * The job completes once every block is cached and fails when one cannot
     * be fetched. Cancelling it withdraws the request, and fetches only it
     * wanted are cancelled; closing the handle withdraws everything.
     */
    suspend fun prefetch(
        ranges: List<LongRange>,
        role: StreamRole = StreamRole.BACKGROUND,
        priority: Int? = null,
    ): Deferred<Unit> = sharedCache().warm(ranges, role, priority)

    private suspend fun sharedCache(): BlockCache = cacheMutex.withLock {
        check(!closed) { "PikPakFileHandle is closed" }
        // A cache whose context was cancelled from outside is closed without anyone having asked; build another
        blockCache?.takeUnless { it.closed }?.let { return@withLock it }
        val built = BlockCache(
            source = this,
            size = streamSize(),
            concurrency = connectionBudget,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            store = blockStore,
            storeKey = "$gcid/${mediaId ?: ORIGINAL_KEY}",
        )
        blockCache = built
        // close() does not wait for this lock, and the probe in streamSize can take long enough
        // for it to run meanwhile; it then saw no cache to close, so this one is closed here
        if (closed) {
            built.close()
            throw IllegalStateException("PikPakFileHandle is closed")
        }
        built
    }

    /**
     * Length of the representation this handle reads, which is not always
     * [size].
     *
     * [size] is the original file's, because that is what [instantCreate]
     * needs. A transcode is a different byte stream of a different length and
     * does not carry it in the metadata, so the only way to learn it is a
     * one-byte Content-Range probe. Handing the original's length to a reader
     * over a transcode would truncate a longer one and run a shorter one off
     * its end.
     *
     * Probed once and kept, for this handle and for every later handle on the
     * same content and variant in this client: the length belongs to the
     * transcode, which a refreshed link or a rebuilt file object cannot change.
     */
    suspend fun streamSize(): Long {
        val variant = mediaId ?: return size
        probedSize?.let { return it }
        client.knownStreamSize(gcid, variant)?.let { return it.also { probedSize = it } }
        return client.remoteSize(provideUrl(UrlRequest.Initial)).also {
            probedSize = it
            client.rememberStreamSize(gcid, variant, it)
        }
    }

    override fun close() {
        closed = true
        blockCache?.close()
        reader?.close()
        reader = null
    }

    /**
     * [close], plus the report this handle still owes, if any.
     *
     * [close] cannot do it: reporting suspends and `AutoCloseable.close` does
     * not. A handle whose rebuild succeeded and whose following detail lookup
     * then failed holds an id nothing else has ever seen, so a caller that
     * deletes leases gives a handle up through this rather than through
     * [close].
     */
    suspend fun closeAndReport() {
        close()
        reportMinted()
    }

    /**
     * A reader whose signature will still be valid for a while.
     *
     * Callers must not hold the result across reads; ask again each time, or
     * a read will eventually be issued on a link this class has already
     * decided to replace.
     */
    private suspend fun currentReader(): RangeReader = mutex.withLock {
        check(!closed) { "PikPakFileHandle is closed" }
        val existing = reader
        val expiry = expiresAt
        if (existing != null && (expiry == null || expiry - clock.now() > refreshMargin)) {
            return existing
        }
        // The outgoing reader is dropped, not closed. Closing only sets a flag
        // that makes its next read throw, and a caller that took this reader a
        // moment ago — the mutex is released before it starts reading — would
        // get that throw instead of the read it asked for. Nothing leaks by
        // letting it go: it holds no connection between reads, and the gate it
        // draws from belongs to the file, not to it. Reads already running
        // finish on the URL they started with, which is still inside its
        // validity; only the refresh margin has been crossed.
        // Left null on purpose: the replacement has not resolved a URL yet, and
        // keeping the old expiry would make the very next call replace it again.
        expiresAt = null
        RangeReader(
            client = client,
            urlProvider = ::provideUrl,
            connectionBudget = connectionBudget,
            // One gate for the file, not one per reader. Closing a reader does
            // not stop the reads already running on it, so a replacement with
            // its own gate would let this handle hold up to twice the budget
            // on one signed URL for as long as the old reads take to drain.
            gate = fileGate,
            onAttempt = onRangeAttempt,
        ).also { reader = it }
    }

    /**
     * The callback [RangeReader] invokes.
     *
     * Internal rather than private so a test can drive the ladder directly
     * instead of having to make the CDN answer 404 first.
     */
    internal suspend fun provideUrl(request: UrlRequest): String {
        // A reader asking for its first link gets the one already in hand while it is good: the
        // link a caller passed in from the detail it just looked up, or the one the probe in
        // streamSize minted a moment ago. Each used to cost another detail lookup.
        val reusable = link.takeIf { request == UrlRequest.Initial && it != null && isUsable(it) }
        val chosen = reusable ?: mintAvoidingBadHosts(request)
        link = chosen
        expiresAt = chosen.expiresAt
        // After the link, never before it: the object still has to answer the
        // lookups that minted it, and a caller that deletes on this signal would
        // pull it out from under them.
        reportMinted()
        return chosen.url
    }

    private fun isUsable(candidate: VariantLink): Boolean {
        val expiry = candidate.expiresAt
        if (expiry != null && expiry - clock.now() <= refreshMargin) return false
        return !client.hostHealth.isBad(candidate.url)
    }

    /**
     * A fresh link, minted again while it lands on a host another file saw fail. PikPak picks
     * the host anew for every link, so asking again is all it takes; the cap only keeps an
     * account whose every host is failing from spinning on lookups.
     */
    private suspend fun mintAvoidingBadHosts(request: UrlRequest): VariantLink {
        // A rejection means the link we held is no good, and so is the file
        // object that produced it — the CDN refuses links whose file is gone.
        // Rebuild before asking, rather than paying a 404 to learn the same thing.
        if (request is UrlRequest.Rejected) rebuild()
        var minted = mint()
        repeat(MAX_HOST_REMINTS) {
            if (!client.hostHealth.isBad(minted.url)) return minted
            minted = mint()
        }
        return minted
    }

    private suspend fun mint(): VariantLink {
        val detail = detail()
        return linkOf(detail) ?: throw PikPakException(-1, "file ${detail.id} has no readable link for variant $mediaId")
    }

    private suspend fun reportMinted() {
        val report = onObjectMinted ?: return
        val id = reportMutex.withLock { unreportedObject.also { unreportedObject = null } } ?: return
        report(id)
    }

    /**
     * This file's detail, rebuilding the file object if its id has died.
     *
     * Every path that reads metadata goes through here, not just the one that
     * resolves a URL: a caller inspecting [variants] before starting playback
     * hits the same dead id, and letting that one 404 while the read path
     * heals would make the file id look like the identity again.
     */
    private suspend fun detail(): FileDetail = try {
        client.getFile(ensureFileId())
    } catch (e: PikPakException) {
        // The id died between calls. A reader cannot report this as a
        // rejection because it never got a URL to have rejected, so without
        // this branch the same failing lookup repeats every time.
        if (!isFileGone(e)) throw e
        rebuild()
        client.getFile(ensureFileId())
    }

    private fun linkOf(detail: FileDetail): VariantLink? {
        val variant = detail.variant(mediaId) ?: return null
        val url = variant.link.url.takeIf { it.isNotBlank() } ?: return null
        return VariantLink(url, variant.link.expiresAt)
    }

    /** The reader a read would use right now, creating it if needed. */
    internal suspend fun readerForTest(): RangeReader = currentReader()

    /** Marks the current signature as due for replacement, without waiting for a clock. */
    internal fun expireForTest() {
        expiresAt = Instant.DISTANT_PAST
    }

    /** The current file id, creating a file object from [gcid] if there is none. */
    private suspend fun ensureFileId(): String = fileId ?: rebuild()

    /**
     * Creates a fresh file object for [gcid] and adopts it.
     *
     * Not synchronised against concurrent readers on purpose: two rebuilds race
     * to create two file objects and one of them goes unused. Serialising them
     * would mean holding a lock across two network round trips on the path that
     * is already the slow one. The unused object is reported rather than
     * dropped, so a caller deleting leases still collects it.
     */
    private suspend fun rebuild(): String {
        val created = client.instantCreate(
            ResolvedFile(path = name, size = size, gcid = gcid),
            parentId = parentId,
            name = name,
        )
        fileId = created
        expiresAt = null
        // Hand the replaced object over instead of overwriting the slot. Nothing looks it up
        // again, fileId having moved on, and a caller that deletes leases is the only thing
        // left that can name it. Two rebuilds racing used to strand the loser's object here:
        // the slot kept whichever wrote last, and the other id was gone for good.
        val replaced = reportMutex.withLock { unreportedObject.also { unreportedObject = created } }
        replaced?.let { onObjectMinted?.invoke(it) }
        return created
    }

    private fun isFileGone(e: PikPakException): Boolean =
        e.httpStatus == 404 || e.errorMessage.contains("not_found")

    companion object {
        /**
         * How long before a link's stated expiry the handle stops handing it
         * out. A read that starts inside this window would otherwise have its
         * signature expire mid-body.
         */
        val DEFAULT_REFRESH_MARGIN: Duration = 5.minutes

        /** Extra links minted when one lands on a host known to be failing; see [mintAvoidingBadHosts]. */
        internal const val MAX_HOST_REMINTS = 2

        /** What the original is called in a [BlockStore], where transcodes go by their media id. */
        internal const val ORIGINAL_KEY = "origin"
    }
}

/**
 * A handle over [detail]'s content, reading the variant [mediaId] (null for the original).
 *
 * Everything the handle would otherwise look up is taken from the detail: the gcid, the file
 * object, the name and parent a rebuild would use, and the first link, so the first read
 * needs no detail request of its own. The original's length comes with it; a transcode's does
 * not, and is probed unless [streamSize] is given or this client has probed it before.
 */
fun PikPakClient.fileHandle(
    detail: FileDetail,
    mediaId: String? = null,
    parentId: String = detail.parentId,
    streamSize: Long? = null,
    blockStore: BlockStore? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    onObjectMinted: (suspend (String) -> Unit)? = null,
    onRangeAttempt: ((RangeAttempt) -> Unit)? = null,
): PikPakFileHandle {
    // Read off the detail directly, not through variant(): that throws for an original with no
    // link, and a missing link here only means the handle mints the first one itself
    val link = if (mediaId == null) detail.octetStream else detail.medias.firstOrNull { it.mediaId == mediaId }?.link
    return PikPakFileHandle(
        client = this,
        gcid = detail.hash,
        size = detail.sizeBytes,
        name = detail.name,
        initialFileId = detail.id,
        mediaId = mediaId,
        parentId = parentId,
        onObjectMinted = onObjectMinted,
        onRangeAttempt = onRangeAttempt,
        initialLink = link?.url?.takeIf { it.isNotBlank() }?.let { VariantLink(it, link.expiresAt) },
        streamSize = streamSize ?: detail.sizeBytes.takeIf { mediaId == null },
        blockStore = blockStore,
        coroutineContext = coroutineContext,
    )
}
