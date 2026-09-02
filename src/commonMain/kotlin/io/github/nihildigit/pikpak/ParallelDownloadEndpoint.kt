package io.github.nihildigit.pikpak

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

/**
 * Downloads [url] to [dest] using [partCount] concurrent byte-range connections.
 *
 * Use this when single-connection throughput is bottlenecked by the server's
 * per-connection cap rather than the client's egress bandwidth. PikPak's CDN
 * holds one connection to roughly 0.8 MB/s no matter how many are open, and
 * the aggregate scales linearly with connection count up to the client's link.
 *
 * [partCount] is capped at 8 in effect, because that is what one signed PikPak
 * URL accepts before answering 503 — the excess is queued by the underlying
 * [RangeReader] rather than failing, so a larger value costs latency and buys
 * nothing. Two *different* URLs get 8 each, so genuinely wider fan-out means
 * more links, not more parts.
 *
 * Behaviour:
 *  - [partCount] == 1 degenerates to [downloadFromUrl] (sequential path with
 *    resume + retry). No tmp files are created.
 *  - [partCount] >= 2 splits the file into equal-sized contiguous parts (the
 *    last part absorbs any remainder), fetches each into a `dest.name.part-N`
 *    tmp file next to [dest] in parallel, then concatenates them in order.
 *  - Each part is fetched through a shared [RangeReader], so a part that dies
 *    mid-body resumes from its own offset instead of restarting, and a 503 is
 *    a wait rather than a failure. This is the difference from the previous
 *    revision, where one failed part discarded every other part's work.
 *  - If [expectedSize] >= 0 the caller's value is used verbatim. If < 0 the
 *    function issues one 1-byte probe range request to derive the total size
 *    from the response's `Content-Range` header.
 *
 * Failure modes — throws [PikPakException]:
 *  - [partCount] < 1 (caller bug — fail fast).
 *  - [expectedSize] < 0 and the probe can't return a parseable Content-Range
 *    total ("*" or missing).
 *  - A part exhausts its retries. All in-flight parts are cancelled, every
 *    `.part-N` tmp file next to [dest] is removed, and the cause is rethrown.
 *    [dest] itself is not touched until every part has succeeded.
 *  - The concatenation phase fails (disk full mid-write, etc.). Tmp files are
 *    still cleaned up; [dest] may be left partially written and is the caller's
 *    to remove. By that point the network phase has succeeded and the failure
 *    is purely local I/O, which the caller can recover from better than we can.
 *
 * Resume across calls: NOT supported. Interrupted mid-download, the next call
 * starts from byte 0. Use [downloadFromUrl] when resume matters more than
 * throughput.
 *
 * URL refresh: NOT handled, because the caller passed a URL rather than a file
 * id and there is nothing to refresh it from. A signature that expires
 * mid-download fails the whole call. Build a [RangeReader] with a `getFile`
 * backed provider (see [rangeReader]) when downloads outlive a signature.
 *
 * @param url          a signed PikPak CDN URL (typically [FileDetail.downloadUrl]
 *                     or [MediaVariant.url]).
 * @param dest         output path. Will be overwritten if it exists.
 * @param partCount    number of concurrent connections. Must be `>= 1`.
 * @param expectedSize total file size in bytes if the caller already knows it
 *                     (e.g. from [FileDetail.sizeBytes]); -1 to probe.
 * @return number of bytes written to [dest] (equals the resolved totalSize).
 */
public suspend fun PikPakClient.parallelDownloadFromUrl(
    url: String,
    dest: Path,
    partCount: Int,
    expectedSize: Long = -1L,
): Long {
    if (partCount < 1) {
        throw PikPakException(-1, "parallelDownloadFromUrl: partCount must be >= 1, got $partCount")
    }

    if (partCount == 1) {
        return downloadFromUrl(url, dest, expectedSize)
    }

    val totalSize: Long = if (expectedSize >= 0L) {
        expectedSize
    } else {
        val probed = streamRangeFromUrl(url, start = 0L, length = 1L) { it.totalSize } ?: -1L
        if (probed <= 0L) {
            throw PikPakException(
                -1,
                "parallelDownloadFromUrl: probe did not return a parseable Content-Range total " +
                    "(got totalSize=$probed); server may have returned \"*\" or omitted Content-Range",
            )
        }
        probed
    }

    if (totalSize == 0L) {
        SystemFileSystem.delete(dest, mustExist = false)
        SystemFileSystem.sink(dest).buffered().use { }
        return 0L
    }

    // One part per byte is the ceiling: any more and partSize floors to 0,
    // which used to make the length check throw on a file smaller than
    // partCount bytes.
    val effectiveParts = if (partCount.toLong() > totalSize) totalSize.toInt() else partCount
    val partSize = totalSize / effectiveParts
    val parentDir = dest.parent ?: Path(".")
    val destName = dest.name
    val tmpPaths = List(effectiveParts) { i -> Path(parentDir, "$destName.part-$i") }

    val reader = RangeReader(
        client = this,
        urlProvider = { url },
        connectionBudget = minOf(effectiveParts, connectionBudget),
    )

    try {
        coroutineScope {
            val jobs = List(effectiveParts) { i ->
                val start = i.toLong() * partSize
                val end = if (i == effectiveParts - 1) totalSize - 1L else (i.toLong() + 1L) * partSize - 1L
                val length = end - start + 1L
                async { fetchPartToTmp(reader, start, length, tmpPaths[i]) }
            }
            jobs.awaitAll()
        }
    } catch (t: Throwable) {
        for (tmp in tmpPaths) {
            SystemFileSystem.delete(tmp, mustExist = false)
        }
        throw t
    } finally {
        reader.close()
    }

    SystemFileSystem.delete(dest, mustExist = false)
    try {
        SystemFileSystem.sink(dest).buffered().use { sink ->
            val buf = ByteArray(128 * 1024)
            for (tmp in tmpPaths) {
                SystemFileSystem.source(tmp).buffered().use { source ->
                    while (true) {
                        val n = source.readAtMostTo(buf, 0, buf.size)
                        if (n == -1) break
                        if (n > 0) sink.write(buf, 0, n)
                    }
                }
            }
        }
    } finally {
        for (tmp in tmpPaths) {
            SystemFileSystem.delete(tmp, mustExist = false)
        }
    }

    return totalSize
}

/**
 * Fetches a single byte range and writes it to [tmpPath].
 * Throws if bytes written != [length].
 */
private suspend fun fetchPartToTmp(
    reader: RangeReader,
    start: Long,
    length: Long,
    tmpPath: Path,
) {
    reader.read(start, length) { channel ->
        val written = writeChannelToPath(channel, tmpPath)
        if (written != length) {
            throw PikPakException(
                -1,
                "parallelDownloadFromUrl: part at offset $start got $written bytes, expected $length (truncated response)",
            )
        }
    }
}

/**
 * Drains [channel] into a new file at [dest], returning total bytes written.
 */
private suspend fun writeChannelToPath(
    channel: ByteReadChannel,
    dest: Path,
): Long {
    return SystemFileSystem.sink(dest).buffered().use { sink ->
        val buf = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n == -1) break
            if (n > 0) {
                sink.write(buf, 0, n)
                total += n
            }
        }
        total
    }
}
