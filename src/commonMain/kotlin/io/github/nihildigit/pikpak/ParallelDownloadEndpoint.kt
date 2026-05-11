package io.github.nihildigit.pikpak

import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
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
 * (and most cloud-storage CDNs) hard-caps a single TCP connection regardless
 * of account tier — opening N concurrent range requests against the same URL
 * multiplies effective throughput close to N×, up to the user's link capacity.
 *
 * The caller chooses [partCount]. There is no SDK-side default because the
 * optimal value depends on the caller's egress link, not on the server: a
 * 50 Mbps consumer link saturates at ~32 connections; a gigabit host can keep
 * going past 128. Empirical guidance: 8 is a safe minimum, 16–32 covers most
 * residential broadband, beyond 64 typically hits diminishing returns.
 *
 * Behaviour:
 *  - [partCount] == 1 degenerates to [downloadFromUrl] (sequential path with
 *    resume + retry). No tmp files are created.
 *  - [partCount] >= 2 splits the file into equal-sized contiguous parts (the
 *    last part absorbs any remainder), fetches each into a `dest.name.part-N`
 *    tmp file next to [dest] via [streamRangeFromUrl] in parallel, then
 *    concatenates them into [dest] in order.
 *  - If [expectedSize] >= 0 the caller's value is used verbatim. If < 0 the
 *    function issues one 1-byte probe range request to derive the total size
 *    from the response's `Content-Range` header.
 *
 * Failure modes — throws [PikPakException]:
 *  - [partCount] < 1 (caller bug — fail fast).
 *  - [expectedSize] < 0 and the probe can't return a parseable Content-Range
 *    total ("*" or missing).
 *  - Any single part's [streamRangeFromUrl] call throws. All in-flight parts
 *    are cancelled, every `.part-N` tmp file next to [dest] is removed, and the
 *    underlying cause is rethrown. [dest] itself is not touched until every
 *    part has succeeded — partial results never leak.
 *  - The concatenation phase fails (disk full mid-write, etc.). Tmp files are
 *    still cleaned up; [dest] may be left partially written and is the caller's
 *    to remove. This split is intentional: by the time we start concatenating,
 *    the network phase has succeeded and the failure mode is purely local I/O,
 *    which the caller is better positioned to recover from.
 *
 * Resume / restart: NOT supported in this revision. If the function is
 * interrupted mid-download, the next call starts over from byte 0. Combining
 * resume with parallel parts is non-trivial (each part needs independent
 * resume state) and is deliberately deferred. Use [downloadFromUrl] when
 * resume matters more than throughput.
 *
 * URL refresh: NOT handled. Same contract as [streamRangeFromUrl] — if the
 * signed URL expires mid-fetch and a part gets 401/403, the whole task fails
 * and the caller is expected to re-fetch a fresh URL via [getFile] and retry.
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
    // Pre-flight validation
    if (partCount < 1) {
        throw PikPakException(-1, "parallelDownloadFromUrl: partCount must be >= 1, got $partCount")
    }

    // partCount == 1: delegate to sequential downloadFromUrl (resume + retry, no tmp files)
    if (partCount == 1) {
        return downloadFromUrl(url, dest, expectedSize)
    }

    // Resolve total file size
    val totalSize: Long = if (expectedSize >= 0L) {
        expectedSize
    } else {
        // 1-byte probe to read totalSize from Content-Range header
        val probe = streamRangeFromUrl(url, start = 0L, length = 1L)
        probe.channel.cancel(CancellationException("probe complete"))
        if (probe.totalSize <= 0L) {
            throw PikPakException(
                -1,
                "parallelDownloadFromUrl: probe did not return a parseable Content-Range total " +
                    "(got totalSize=${probe.totalSize}); server may have returned \"*\" or omitted Content-Range",
            )
        }
        probe.totalSize
    }

    // Build part ranges and tmp paths
    val partSize = totalSize / partCount
    val parentDir = dest.parent ?: Path(".")
    val destName = dest.name
    val tmpPaths = List(partCount) { i -> Path(parentDir, "$destName.part-$i") }

    // Fan-out: fetch each part to its tmp file, clean up all on any failure
    try {
        coroutineScope {
            val jobs = List(partCount) { i ->
                val start = i.toLong() * partSize
                val end = if (i == partCount - 1) totalSize - 1L else (i.toLong() + 1L) * partSize - 1L
                val length = end - start + 1L
                async { fetchPartToTmp(url, start, length, tmpPaths[i]) }
            }
            jobs.awaitAll()
        }
    } catch (t: Throwable) {
        // Clean up all tmp files on failure, then rethrow
        for (tmp in tmpPaths) {
            SystemFileSystem.delete(tmp, mustExist = false)
        }
        throw t
    }

    // Concat phase: delete pre-existing dest, write parts in order
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
        // Always clean up tmp files after concat (success or failure)
        for (tmp in tmpPaths) {
            SystemFileSystem.delete(tmp, mustExist = false)
        }
    }

    return totalSize
}

/**
 * Fetches a single byte range from [url] and writes it to [tmpPath].
 * Throws if bytes written != [length].
 */
private suspend fun PikPakClient.fetchPartToTmp(
    url: String,
    start: Long,
    length: Long,
    tmpPath: Path,
) {
    val stream = streamRangeFromUrl(url, start, length)
    try {
        val written = writeChannelToPath(stream.channel, tmpPath)
        if (written != length) {
            throw PikPakException(
                -1,
                "parallelDownloadFromUrl: part at offset $start got $written bytes, expected $length (truncated response)",
            )
        }
    } finally {
        stream.channel.cancel(CancellationException("part fetch complete"))
    }
}

/**
 * Drains [channel] into a new file at [dest], returning total bytes written.
 */
private suspend fun writeChannelToPath(
    channel: io.ktor.utils.io.ByteReadChannel,
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
