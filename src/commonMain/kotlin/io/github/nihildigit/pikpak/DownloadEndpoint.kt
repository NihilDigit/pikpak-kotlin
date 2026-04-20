package io.github.nihildigit.pikpak

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

/**
 * Downloads [fileId] to [dest]. Supports resume: if [dest] already exists,
 * the download starts at its current size via a `Range: bytes=N-` request.
 * Returns the total bytes on disk after success (== expected file size).
 *
 * Failure semantics:
 *  - Transient network errors are retried per the client's [RetryPolicy].
 *  - If the server responds 200 instead of 206 to a range request, the local
 *    file is truncated and the download restarts from zero (one extra attempt).
 *  - If the file already exists at the expected size, returns immediately.
 *
 * Atomic: this method writes directly to [dest]. If you need a temp-then-rename
 * dance for crash safety, do it at the call site.
 */
suspend fun PikPakClient.download(fileId: String, dest: Path): Long {
    val detail = getFile(fileId)
    val url = detail.downloadUrl
        ?: throw PikPakException(-1, "download: file has no octet-stream link")
    val expectedSize = detail.sizeBytes
    return downloadFromUrl(url, dest, expectedSize)
}

/**
 * Lower-level: downloads [url] to [dest] with resume + retry. Use this when
 * you already have a signed download URL (e.g. from a cached [FileDetail]).
 * If [expectedSize] is negative, no length verification is performed.
 */
suspend fun PikPakClient.downloadFromUrl(url: String, dest: Path, expectedSize: Long = -1L): Long {
    var attempt = 0
    var restartCount = 0
    val maxRestarts = retryPolicy.maxAttempts
    while (true) {
        val existing = SystemFileSystem.metadataOrNullSafe(dest)?.size ?: 0L
        if (expectedSize >= 0 && existing == expectedSize) return existing
        val offset = if (expectedSize >= 0 && existing > expectedSize) {
            // local file is bigger than the source — start over.
            SystemFileSystem.delete(dest, mustExist = false)
            0L
        } else existing

        val outcome = tryDownloadOnce(url, dest, offset, expectedSize)
        when (outcome) {
            is DownloadOutcome.Done -> return outcome.totalBytes
            is DownloadOutcome.RestartFromZero -> {
                // Cap restarts: a server that consistently ignores Range + sends
                // an incomplete body would otherwise loop forever.
                restartCount++
                if (restartCount > maxRestarts) {
                    throw PikPakException(
                        errorCode = -1,
                        errorMessage = "download restarted $restartCount times without progress",
                    )
                }
                SystemFileSystem.delete(dest, mustExist = false)
                attempt = 0
                continue
            }
            is DownloadOutcome.Retry -> {
                attempt++
                if (attempt >= retryPolicy.maxAttempts) throw outcome.cause
                delay(retryPolicy.delayFor(attempt))
            }
        }
    }
}

private sealed interface DownloadOutcome {
    data class Done(val totalBytes: Long) : DownloadOutcome
    data object RestartFromZero : DownloadOutcome
    data class Retry(val cause: Throwable) : DownloadOutcome
}

private suspend fun PikPakClient.tryDownloadOnce(
    url: String,
    dest: Path,
    offset: Long,
    expectedSize: Long,
): DownloadOutcome {
    val response = try {
        http.sendRaw(HttpMethod.Get, url) {
            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
        }
    } catch (t: Throwable) {
        return DownloadOutcome.Retry(t)
    }

    val status = response.status
    return when {
        offset > 0 && status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
            if (expectedSize >= 0 && offset == expectedSize) DownloadOutcome.Done(expectedSize)
            else DownloadOutcome.RestartFromZero
        }
        offset > 0 && status == HttpStatusCode.OK -> DownloadOutcome.RestartFromZero
        offset > 0 && status != HttpStatusCode.PartialContent -> {
            if (status.value >= 500 || status.value == 429) DownloadOutcome.Retry(
                PikPakException(-1, "download HTTP ${status.value}", httpStatus = status.value),
            ) else throw PikPakException(-1, "download HTTP ${status.value}", httpStatus = status.value)
        }
        offset == 0L && status != HttpStatusCode.OK -> {
            if (status.value >= 500 || status.value == 429) DownloadOutcome.Retry(
                PikPakException(-1, "download HTTP ${status.value}", httpStatus = status.value),
            ) else throw PikPakException(-1, "download HTTP ${status.value}", httpStatus = status.value)
        }
        else -> {
            try {
                val written = streamBody(response.bodyAsChannel(), dest, append = offset > 0)
                val total = offset + written
                if (expectedSize >= 0 && total != expectedSize) {
                    DownloadOutcome.Retry(PikPakException(-1, "download incomplete: got $total of $expectedSize"))
                } else DownloadOutcome.Done(total)
            } catch (t: Throwable) {
                DownloadOutcome.Retry(t)
            }
        }
    }
}

private suspend fun streamBody(channel: ByteReadChannel, dest: Path, append: Boolean): Long {
    SystemFileSystem.sink(dest, append = append).buffered().use { sink ->
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
        return total
    }
}

private fun kotlinx.io.files.FileSystem.metadataOrNullSafe(path: Path): FileMetadata? =
    runCatching { metadataOrNull(path) }.getOrNull()
