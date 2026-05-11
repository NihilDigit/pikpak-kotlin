package io.github.nihildigit.pikpak

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel

/**
 * A bounded byte-range over a remote file, returned by [streamRangeFromUrl].
 *
 * Lifecycle: this is a thin value type. The underlying HTTP connection lives
 * for as long as [channel] is open. Callers MUST either fully consume [channel]
 * or call [ByteReadChannel.cancel] on it — leaking the channel leaks the
 * connection. The recommended pattern is to copy/process the channel inside a
 * try/finally that cancels on the way out:
 *
 *     val stream = client.streamRangeFromUrl(url, start = 0, length = 1 shl 20)
 *     try {
 *         stream.channel.copyTo(sink)
 *     } finally {
 *         stream.channel.cancel()
 *     }
 *
 * Field semantics:
 *  - [channel]: the response body. Yields exactly [contentLength] bytes.
 *  - [contentLength]: byte count of THIS range, not the underlying file.
 *  - [totalSize]: size of the full remote file in bytes, parsed from the
 *    `Content-Range: bytes X-Y/TOTAL` response header. `-1` if the server
 *    omitted Content-Range or returned a non-numeric total ("*").
 *  - [rangeStart] / [rangeEndInclusive]: the actual range the server says it
 *    sent (X-Y from Content-Range). Differs from what the caller asked for
 *    when the request range extended past EOF. Both `-1` if no Content-Range.
 */
data class RangeStream(
    val channel: ByteReadChannel,
    val contentLength: Long,
    val totalSize: Long,
    val rangeStart: Long,
    val rangeEndInclusive: Long,
)

/**
 * Streams a byte range from a signed CDN URL without writing to disk. Intended
 * use: random-access reads into a remote media file, e.g. time-based clipping
 * of PikPak's MPEG-TS transcoded variants (one transcode URL serves the whole
 * media; `Range: bytes=start-end` slices any segment of it, server returns 206).
 *
 * Request shape:
 *  - [length] null   → `Range: bytes=$start-` (open-ended, server returns from
 *    [start] to EOF). Use when the caller wants "from here to the end".
 *  - [length] >= 1   → `Range: bytes=$start-${start+length-1}` (closed range).
 *    Use when the caller wants a fixed-size slice.
 *
 * Pipeline reuse:
 *  - Goes through `PikPakClient.http.sendRaw`, so transport-level retries
 *    (5xx, 429, transient I/O) follow the configured [RetryPolicy].
 *  - Does NOT add PikPak's `Authorization` / `X-Device-Id` / `Accept` headers.
 *    The URL is expected to be a signed CDN/transcode link, which rejects them
 *    (this is the same constraint as [downloadFromUrl]).
 *  - Goes through the client's [rateLimiter] like every other outbound call.
 *
 * Failure modes — throws [PikPakException] on:
 *  - `start < 0`, or `length != null && length <= 0` (caller bug — fail fast,
 *    do not silently coerce).
 *  - Server returns 200 OK instead of 206 Partial Content. This means the
 *    server ignored the Range header; the body would be the full file. Surface
 *    this as a hard error rather than streaming hundreds of MB the caller did
 *    not ask for. Random-access contract is broken; caller decides recovery.
 *  - Server returns 416 Range Not Satisfiable (typically `start >= totalSize`).
 *    PikPakException carries httpStatus = 416 so callers can match.
 *  - Any other non-2xx after sendRaw's retry budget is exhausted.
 *
 * URL lifecycle: NOT handled. If the signed URL has expired (401/403), this
 * throws and the caller is expected to re-fetch a fresh URL via [getFile] and
 * retry. Built-in refresh would hide the URL lifecycle from callers and break
 * the atomic-SDK contract; deliberately omitted.
 *
 * @param url    a signed PikPak CDN URL — typically from [FileDetail.downloadUrl]
 *               or [MediaVariant.url].
 * @param start  starting byte offset, inclusive. Must be `>= 0`.
 * @param length number of bytes to fetch, or null for "from [start] to EOF".
 *               Must be `>= 1` when non-null.
 * @return a [RangeStream] whose channel MUST be consumed or cancelled by the
 *         caller (see [RangeStream] kdoc for the lifecycle contract).
 */
public suspend fun PikPakClient.streamRangeFromUrl(
    url: String,
    start: Long,
    length: Long? = null,
): RangeStream {
    if (start < 0) throw PikPakException(-1, "streamRangeFromUrl: start must be >= 0, got $start")
    if (length != null && length <= 0) {
        throw PikPakException(-1, "streamRangeFromUrl: length must be >= 1 when non-null, got $length")
    }

    val rangeHeader = if (length == null) {
        "bytes=$start-"
    } else {
        "bytes=$start-${start + length - 1}"
    }

    val response = http.sendRaw(HttpMethod.Get, url) {
        header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
        header(HttpHeaders.Range, rangeHeader)
    }

    val status = response.status
    when {
        status == HttpStatusCode.OK ->
            throw PikPakException(
                -1,
                "streamRangeFromUrl: server returned 200 instead of 206 — Range header was ignored, " +
                    "streaming the full file would violate the range contract",
                httpStatus = 200,
            )
        status == HttpStatusCode.RequestedRangeNotSatisfiable ->
            throw PikPakException(
                -1,
                "streamRangeFromUrl: server returned 416 Range Not Satisfiable (start=$start may exceed file size)",
                httpStatus = 416,
            )
        status != HttpStatusCode.PartialContent ->
            throw PikPakException(
                -1,
                "streamRangeFromUrl: unexpected HTTP status ${status.value}",
                httpStatus = status.value,
            )
    }

    // Parse Content-Range: bytes X-Y/TOTAL  (TOTAL may be *)
    val contentRangeHeader = response.headers[HttpHeaders.ContentRange]
    val parsed = parseContentRange(contentRangeHeader)
    val parsedStart = parsed.first
    val parsedEnd = parsed.second
    val parsedTotal = parsed.third

    val rawContentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val contentLength: Long = when {
        rawContentLength != null && rawContentLength >= 0L -> rawContentLength
        parsedStart >= 0L && parsedEnd >= 0L -> parsedEnd - parsedStart + 1L
        else -> -1L
    }

    return RangeStream(
        channel = response.bodyAsChannel(),
        contentLength = contentLength,
        totalSize = parsedTotal,
        rangeStart = parsedStart,
        rangeEndInclusive = parsedEnd,
    )
}

/**
 * Parses a Content-Range header of the form "bytes X-Y/TOTAL" or "bytes X-Y/ *" (star total).
 * Returns a triple of (rangeStart, rangeEndInclusive, totalSize).
 * Any field that cannot be parsed is returned as -1.
 */
private fun parseContentRange(header: String?): Triple<Long, Long, Long> {
    if (header == null) return Triple(-1L, -1L, -1L)
    // Expected format: "bytes 100-149/10000" or "bytes 0-99/*"
    val withoutPrefix = header.removePrefix("bytes ").trim()
    val slashIndex = withoutPrefix.indexOf('/')
    if (slashIndex < 0) return Triple(-1L, -1L, -1L)
    val rangePart = withoutPrefix.substring(0, slashIndex)
    val totalPart = withoutPrefix.substring(slashIndex + 1)
    val dashIndex = rangePart.indexOf('-')
    if (dashIndex < 0) return Triple(-1L, -1L, -1L)
    val rangeStart = rangePart.substring(0, dashIndex).toLongOrNull() ?: return Triple(-1L, -1L, -1L)
    val rangeEnd = rangePart.substring(dashIndex + 1).toLongOrNull() ?: return Triple(-1L, -1L, -1L)
    val total = if (totalPart == "*") -1L else totalPart.toLongOrNull() ?: return Triple(-1L, -1L, -1L)
    return Triple(rangeStart, rangeEnd, total)
}
