package io.github.nihildigit.pikpak

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel

/**
 * A bounded byte-range over a remote file, handed to the block passed to
 * [streamRangeFromUrl].
 *
 * Lifecycle: valid only for the duration of that block. [channel] is a live
 * connection, not a buffer — the bytes have not been read yet, so a large or
 * open-ended range costs no memory until you read it. Once the block returns,
 * the connection is released and [channel] is dead. Do not let it escape.
 *
 * Field semantics — every size is null when the server did not say, rather
 * than a sentinel that reads as a real number in arithmetic:
 *  - [channel]: the response body. Yields exactly [contentLength] bytes.
 *  - [contentLength]: byte count of THIS range, not the underlying file.
 *  - [totalSize]: size of the full remote file, from
 *    `Content-Range: bytes X-Y/TOTAL`. Null when Content-Range was absent or
 *    the total was "*".
 *  - [rangeStart] / [rangeEndInclusive]: the range the server says it actually
 *    sent. Differs from what the caller asked for when the request extended
 *    past EOF. Null when there was no Content-Range.
 */
data class RangeStream(
    val channel: ByteReadChannel,
    val contentLength: Long?,
    val totalSize: Long?,
    val rangeStart: Long?,
    val rangeEndInclusive: Long?,
)

/**
 * Streams a byte range from a signed CDN URL without writing to disk. Intended
 * use: random-access reads into a remote media file, e.g. time-based clipping
 * of PikPak's MPEG-TS transcoded variants (one transcode URL serves the whole
 * media; `Range: bytes=start-end` slices any segment of it, server returns 206).
 *
 * For sustained playback-style reads against one file prefer [RangeReader],
 * which adds a connection budget, priority scheduling and automatic URL
 * refresh on top of this call.
 *
 * Request shape:
 *  - [length] null   → `Range: bytes=$start-` (open-ended, server returns from
 *    [start] to EOF). Use when the caller wants "from here to the end".
 *  - [length] >= 1   → `Range: bytes=$start-${start+length-1}` (closed range).
 *    Use when the caller wants a fixed-size slice.
 *
 * Pipeline reuse:
 *  - Goes through `PikPakClient.http.sendRaw`, so transport-level retries
 *    (5xx, 429, transient I/O) follow the configured [RetryPolicy], and the
 *    request does NOT consume a rate-limiter token.
 *  - Does NOT add PikPak's `Authorization` / `X-Device-Id` headers. The URL is
 *    expected to be a signed CDN/transcode link, which rejects them.
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
 * URL lifecycle: an expired signature (401/403) surfaces as
 * [UrlExpiredException]. This function does not refresh it — re-fetch via
 * [getFile] and retry, or use [RangeReader] which does exactly that.
 *
 * @param url    a signed PikPak CDN URL — typically from [FileDetail.downloadUrl]
 *               or [MediaVariant.url].
 * @param start  starting byte offset, inclusive. Must be `>= 0`.
 * @param length number of bytes to fetch, or null for "from [start] to EOF".
 *               Must be `>= 1` when non-null.
 * @param configure escape hatch onto the request builder, applied after the
 *               SDK's own headers. Its reason to exist is per-call timeouts:
 *               the CDN client's timeouts are sized for long streaming reads,
 *               which is wrong for a 64 KB probe that should fail fast.
 *               `configure = { timeout { requestTimeoutMillis = 5_000 } }`.
 * @param block  receives the live range. Everything you need must be read here.
 */
public suspend fun <T> PikPakClient.streamRangeFromUrl(
    url: String,
    start: Long,
    length: Long? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
    block: suspend (RangeStream) -> T,
): T {
    if (start < 0) throw PikPakException(-1, "streamRangeFromUrl: start must be >= 0, got $start")
    if (length != null && length <= 0) {
        throw PikPakException(-1, "streamRangeFromUrl: length must be >= 1 when non-null, got $length")
    }

    val rangeHeader = if (length == null) {
        "bytes=$start-"
    } else {
        "bytes=$start-${start + length - 1}"
    }

    return http.sendRaw(
        method = HttpMethod.Get,
        url = url,
        configure = {
            // set, not append: an injected client that already carries a
            // User-Agent would otherwise send two of them.
            headers[HttpHeaders.UserAgent] = PikPakConstants.USER_AGENT
            headers[HttpHeaders.Range] = rangeHeader
            configure()
        },
    ) { response ->
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

        val parsed = parseContentRange(response.headers[HttpHeaders.ContentRange])
        val rawContentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val derivedLength = parsed?.let { it.endInclusive - it.start + 1L }

        block(
            RangeStream(
                channel = response.bodyAsChannel(),
                contentLength = rawContentLength ?: derivedLength,
                totalSize = parsed?.totalSize,
                rangeStart = parsed?.start,
                rangeEndInclusive = parsed?.endInclusive,
            ),
        )
    }
}

/** What a `Content-Range` header says. A Triple of Longs said none of this. */
private data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    /** Null when the server sent "*" — it knows the range but not the file's length. */
    val totalSize: Long?,
)

/**
 * Parses `bytes X-Y/TOTAL` or `bytes X-Y/ *`. Null when the header is absent
 * or malformed; an unparseable header is not a range we can reason about.
 */
private fun parseContentRange(header: String?): ContentRange? {
    if (header == null) return null
    val withoutPrefix = header.removePrefix("bytes ").trim()
    val slashIndex = withoutPrefix.indexOf('/')
    if (slashIndex < 0) return null
    val rangePart = withoutPrefix.substring(0, slashIndex)
    val totalPart = withoutPrefix.substring(slashIndex + 1)
    val dashIndex = rangePart.indexOf('-')
    if (dashIndex < 0) return null
    val start = rangePart.substring(0, dashIndex).toLongOrNull() ?: return null
    val end = rangePart.substring(dashIndex + 1).toLongOrNull() ?: return null
    val total = if (totalPart == "*") null else totalPart.toLongOrNull() ?: return null
    return ContentRange(start, end, total)
}
