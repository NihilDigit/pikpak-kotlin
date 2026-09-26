package io.github.nihildigit.pikpak

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Default block one request of [RangeSource.downloadTo] fetches. */
public const val DIRECT_DOWNLOAD_BLOCK_SIZE: Long = 512L * 1024

/** Default number of connections [RangeSource.downloadTo] keeps busy. */
public const val DIRECT_DOWNLOAD_CONCURRENCY: Int = 4

/**
 * Downloads this whole file into [dest] front to back over [concurrency]
 * connections, so that [dest] is at every instant a valid prefix of the file
 * and its length is the download's progress.
 *
 * This is the way to put a PikPak file on disk. One connection to this CDN is
 * bounded by the round trip rather than by the link — measured at 0.94 MB/s on
 * a distant route against 4.7 MB/s on a short one, the distant case scaling
 * nearly linearly to eight connections — so there is no single-connection
 * variant; pass `concurrency = 1` for one.
 *
 * Bytes are written strictly in order, which is what makes the file's length
 * the progress: resuming means continuing from that length, and there is no
 * bitmap, no preallocation and no hole to reason about. Only the writes are
 * ordered, though — the fetches slide: [concurrency] requests stay in flight,
 * and the block at the head of the window is appended and replaced the moment
 * it lands, so a slow block delays the write rather than the next request.
 *
 * The rejected alternative was to let each connection write at its own offset
 * the moment its bytes land. It costs the property above: the file then has
 * holes and needs its own record of which ranges are real. kotlinx-io settles
 * the question anyway — its `FileSystem` opens a file for append or for
 * truncate-and-write, and offers no seekable handle, so writing at an
 * arbitrary offset is not expressible without a new platform actual.
 *
 * Cancellation is the pause. Each block is written and flushed before the next
 * is awaited, so a cancelled call leaves the file ending on a block boundary
 * that was written in full. Calling this again with the same [dest] continues
 * from there.
 *
 * A failure is not retried forever: a window that keeps failing throws, and the
 * prefix on disk stays valid. Whether a background cache keeps trying is the
 * caller's policy, not the SDK's. A failure coming from [dest] rather than from
 * the network is never retried at all — the bytes that reached the file are
 * then unknown, and replaying a block against the same append stream would
 * duplicate them.
 *
 * URL expiry, 503 throttling and mid-body truncation are the source's business.
 * Use a [PikPakFileHandle] when the download can outlive a signature or the
 * file object can be swept away; a source over a fixed URL fails when the
 * signature does.
 *
 * It reads through [RangeSource] and nothing else, so it shares no bytes with
 * the handle's block cache or [BlockStore]: a file downloaded while it plays is
 * fetched twice, the two competing only through priority. Folding it into the
 * cache as a sequential background cursor would fix that, and has not been done.
 *
 * @param dest        output path. An existing file is treated as a partial
 *                    download and continued; one longer than [totalSize] is
 *                    discarded, because a too-long file means the recorded
 *                    length is wrong and the tail belongs to something else.
 * @param totalSize   the remote file's size. Use [PikPakClient.remoteSize] if
 *                    the metadata does not carry it.
 * @param concurrency requests kept in flight. Must be `>= 1`. This source's own
 *                    connection budget, and the account budget behind it, cap
 *                    the real fan-out regardless of what is asked for here.
 * @param priority    passed to every read. The default of 1 puts a background
 *                    download above nothing in particular but below a playing
 *                    stream, which should read at a higher number on the same
 *                    source.
 * @param progress    if given, set to the bytes on disk before the first fetch
 *                    and after every block, so a caller can observe the run
 *                    while it happens. Ends at [totalSize] on success.
 * @param maxRoundFailures failures in a row tolerated before the call gives up.
 *                    Any progress resets the count. Each failure already had
 *                    [RangeReader]'s own retries behind it.
 * @param roundRetryDelay waited before resuming a run that is otherwise
 *                    healthy. Not the client's retry backoff: that curve has
 *                    already been walked inside the failed read.
 * @return [totalSize].
 */
public suspend fun RangeSource.downloadTo(
    dest: Path,
    totalSize: Long,
    concurrency: Int = DIRECT_DOWNLOAD_CONCURRENCY,
    priority: Int = 1,
    blockSize: Long = DIRECT_DOWNLOAD_BLOCK_SIZE,
    progress: MutableStateFlow<Long>? = null,
    maxRoundFailures: Int = 3,
    roundRetryDelay: Duration = 3.seconds,
): Long {
    if (concurrency < 1) {
        throw PikPakException(-1, "downloadTo: concurrency must be >= 1, got $concurrency")
    }
    if (blockSize < 1) {
        throw PikPakException(-1, "downloadTo: blockSize must be >= 1, got $blockSize")
    }
    if (totalSize < 0) {
        throw PikPakException(-1, "downloadTo: totalSize must be >= 0, got $totalSize")
    }

    dest.parent?.let { if (!SystemFileSystem.exists(it)) SystemFileSystem.createDirectories(it) }

    var written = existingLength(dest)
    if (written > totalSize) {
        SystemFileSystem.delete(dest, mustExist = false)
        written = 0L
    }
    progress?.value = written
    if (written == totalSize) {
        // Still create the file when the remote one is empty: a caller that
        // checks existence must not be told a zero-byte file is missing.
        if (totalSize == 0L && !SystemFileSystem.exists(dest)) {
            SystemFileSystem.sink(dest).buffered().use { }
        }
        return totalSize
    }

    // Append, not truncate: the bytes already on disk are the resume point.
    SystemFileSystem.sink(dest, append = true).buffered().use { sink ->
        var failures = 0
        while (written < totalSize) {
            val startedAt = written
            try {
                slideWindow(
                    source = this@downloadTo,
                    sink = sink,
                    from = written,
                    totalSize = totalSize,
                    blockSize = blockSize,
                    concurrency = concurrency,
                    priority = priority,
                    onWritten = {
                        written = it
                        progress?.value = it
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SinkFailure) {
                // Writing to the destination is not a transient network fault
                // and retrying it is not safe; see where it is thrown.
                throw e.cause
            } catch (e: Throwable) {
                // Any progress at all resets the budget: the count is there to
                // stop a download that cannot move, not to cap the failures a
                // long transfer over a bad link is allowed to survive.
                if (written > startedAt) failures = 0
                failures++
                if (failures >= maxRoundFailures) throw e
                delay(roundRetryDelay)
            }
        }
    }
    return totalSize
}

/**
 * Streams from [from] to [totalSize], keeping [concurrency] requests in flight.
 *
 * Blocks are written in order — that is what keeps the file a valid prefix and
 * its length the progress, which is what makes resume free — but only the
 * writing is ordered. As soon as the block at the head of the window lands it
 * is appended and a new request takes its place, so a slow block delays the
 * write and nothing else.
 *
 * The earlier version fetched `concurrency` blocks, waited for all of them,
 * appended the batch, and only then issued the next batch. Every round ended
 * with the connections idle, waiting on the slowest block of that round. That
 * costs the most on exactly the links this library exists for: block latency
 * varies most on a weak route, so the slowest block of a round is furthest
 * from the mean, and fan-out degrades from N times to N over the variance.
 *
 * Peak memory is unchanged. A window holds at most [concurrency] blocks —
 * finished ones waiting for their turn plus outstanding requests — which is
 * what a round held too.
 *
 * A failure anywhere cancels the whole window through [coroutineScope], so the
 * blocks that had already landed behind the head are dropped rather than
 * written out of order. They cost a refetch on the retry; the alternative is
 * tracking holes, and a hole is what makes length stop meaning progress.
 */
private suspend fun slideWindow(
    source: RangeSource,
    sink: Sink,
    from: Long,
    totalSize: Long,
    blockSize: Long,
    concurrency: Int,
    priority: Int,
    onWritten: (Long) -> Unit,
): Unit = coroutineScope {
    val window = ArrayDeque<Deferred<ByteArray>>(concurrency)
    var nextOffset = from
    var written = from

    fun issueNext() {
        if (nextOffset >= totalSize) return
        val start = nextOffset
        val length = minOf(blockSize, totalSize - start)
        nextOffset = start + length
        window += async { fetchBlock(source, start, length, priority) }
    }

    repeat(concurrency) { issueNext() }

    while (window.isNotEmpty()) {
        val bytes = window.removeFirst().await()
        // Flushed per block, not per window: the caller is told the new length
        // immediately below, and progress must never claim bytes the file does
        // not have yet. A cancellation landing here leaves a whole number of
        // blocks on disk, which the next call resumes from.
        try {
            sink.write(bytes, 0, bytes.size)
            sink.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Marked so the retry loop lets it through. A failed write may have
            // put an unknown number of bytes on disk, and the caller's resume
            // point is the file's length — replaying the block against the same
            // append sink would duplicate whatever did land.
            throw SinkFailure(e)
        }
        written += bytes.size
        onWritten(written)
        issueNext()
    }
}

/**
 * Marks a failure that came from the destination rather than the network, so
 * the retry loop can tell the two apart. Never escapes [RangeSource.downloadTo] —
 * the original is rethrown in its place.
 */
private class SinkFailure(override val cause: Throwable) : Exception(cause)

/**
 * One block, insisting it came back whole.
 *
 * [RangeSource.readBytes] short-reads at EOF rather than failing, which is the
 * right behaviour for a range read and a trap here: a short block appended as
 * if it were complete puts every later byte at the wrong offset, silently. A
 * short block at the end means [totalSize] is larger than the resource, and
 * without this the window would spin on empty blocks forever.
 */
private suspend fun fetchBlock(
    source: RangeSource,
    start: Long,
    length: Long,
    priority: Int,
): ByteArray {
    val bytes = source.readBytes(start, length, priority)
    if (bytes.size.toLong() != length) {
        throw PikPakException(
            errorCode = -1,
            errorMessage = "short read at $start: wanted $length bytes, got ${bytes.size}",
        )
    }
    return bytes
}

private fun existingLength(dest: Path): Long =
    runCatching { SystemFileSystem.metadataOrNull(dest)?.size }.getOrNull() ?: 0L

