package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.PriorityGate
import io.ktor.utils.io.ByteReadChannel
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
    /** Content hash. The one identifier here that cannot go stale. */
    val gcid: String,
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
    private val clock: Clock = Clock.System,
    private val refreshMargin: Duration = DEFAULT_REFRESH_MARGIN,
) : RangeSource, AutoCloseable {
    private val mutex = Mutex()

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
    private var probedSize: Long? = null

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

    /** [read] with the length left open, which reads to the end of the file. */
    suspend fun <T> readToEnd(
        start: Long,
        priority: Int = 0,
        block: suspend (ByteReadChannel) -> T,
    ): T = currentReader().read(start, null, priority, block)

    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray =
        currentReader().readBytes(start, length, priority)

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
     * Opens a seekable, cached reader over this file, for playing it.
     *
     * Reads go through this handle, so a signature that expires or a file
     * object that has to be rebuilt mid-playback is invisible to the player.
     * Constructing a [PikPakStreamReader] on a bare [RangeReader] instead would
     * pin it to the instance this handle is about to retire.
     *
     * The result owns its own read-ahead and cache and must be closed; closing
     * it does not close this handle, which several readers may share.
     */
    /**
     * @param size length of what is being read. Null asks [streamSize], which
     *   costs a probe for a transcode and nothing for the original. Pass it
     *   explicitly when the caller already knows it.
     */
    suspend fun openStream(
        size: Long? = null,
        concurrency: Int = connectionBudget,
        parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): PikPakStreamReader =
        PikPakStreamReader(this, size ?: streamSize(), concurrency, parentCoroutineContext)

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
     * Probed once and kept: the length belongs to the variant, which is fixed
     * for the life of the handle, so a refreshed link cannot change it.
     */
    suspend fun streamSize(): Long {
        if (mediaId == null) return size
        probedSize?.let { return it }
        return client.remoteSize(provideUrl(UrlRequest.Initial)).also { probedSize = it }
    }

    override fun close() {
        closed = true
        reader?.close()
        reader = null
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
        ).also { reader = it }
    }

    /**
     * The callback [RangeReader] invokes.
     *
     * Internal rather than private so a test can drive the ladder directly
     * instead of having to make the CDN answer 404 first.
     */
    internal suspend fun provideUrl(request: UrlRequest): String {
        // A rejection means the link we held is no good, and so is the file
        // object that produced it — the CDN refuses links whose file is gone.
        // Rebuild before asking, rather than paying a 404 to learn the same thing.
        if (request is UrlRequest.Rejected) rebuild()

        val detail = detail()
        val link = linkOf(detail)
            ?: throw PikPakException(-1, "file ${detail.id} has no readable link for variant $mediaId")
        expiresAt = link.expiresAt
        return link.url
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
     * to create two file objects, and the loser's is simply orphaned. Serialising
     * them would mean holding a lock across two network round trips on the path
     * that is already the slow one.
     */
    private suspend fun rebuild(): String {
        val created = client.instantCreate(
            ResolvedFile(path = name, size = size, gcid = gcid),
            parentId = parentId,
            name = name,
        )
        fileId = created
        expiresAt = null
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
    }
}
