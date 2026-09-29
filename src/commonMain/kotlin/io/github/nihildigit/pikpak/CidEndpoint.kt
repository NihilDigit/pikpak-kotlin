package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.sha1.SHA1

/**
 * Xunlei's sampled content id ("CID"): SHA-1 over three 20 KB windows at the
 * start, at a third of the way in and at the end, or over the whole file below
 * 60 KB. [gcidByCid] maps it to the gcid, so an upload of content PikPak
 * already holds needs 60 KB read instead of the whole file hashed.
 *
 * Measured 2026-09-25 on four files from 29 KB to 7.5 GB: every lookup
 * returned the file's `hash`. The index is PikPak's, not the account's: a
 * release ISO the account had never held resolved to the gcid [resolveMagnet]
 * reported for it, and [upload] under that gcid completed instantly. Two files
 * of equal size that agree on all three windows share a CID, so a hit is not
 * proof of identical content.
 */
object XunleiCid {
    private const val WINDOW = 0x5000
    private const val WHOLE_FILE_BELOW = 0xF000L

    /**
     * The CID of a file of [size] bytes, uppercase hex. [read] returns exactly
     * `length` bytes starting at `offset`; it is called once for a small file,
     * three times otherwise. Random access is the caller's because a stream
     * would have to read through two thirds of the file to reach the windows.
     */
    fun of(size: Long, read: (offset: Long, length: Int) -> ByteArray): String =
        digest(windows(size).map { (offset, length) -> read(offset, length) })

    /** The (offset, length) pairs a file of [size] bytes is hashed over, in order. */
    internal fun windows(size: Long): List<Pair<Long, Int>> {
        require(size >= 0) { "size must not be negative" }
        return if (size < WHOLE_FILE_BELOW) {
            listOf(0L to size.toInt())
        } else {
            listOf(0L to WINDOW, size / 3 to WINDOW, size - WINDOW to WINDOW)
        }
    }

    /** The CID of the bytes of [windows], read in that order. */
    internal fun digest(parts: List<ByteArray>): String {
        val sha1 = SHA1()
        parts.forEach(sha1::update)
        return sha1.digest().toHex().uppercase()
    }
}

/**
 * The screenshot thumbnail PikPak keeps for the video content [gcid] names.
 *
 * A listing's `thumbnail_link` is exactly this URL: no signature, no expiry,
 * nothing tied to the file object or the account. It answers without any
 * authorization, also for content the account has never held, and 404 when
 * PikPak has no screenshot of it (measured 2026-09-29). So a caller that keeps
 * only a gcid, a file it no longer holds, still has its thumbnail. What the
 * `240/720` segments mean is not known; they are the values listings carry.
 */
fun thumbnailUrlOf(gcid: String): String = "https://sg-thumbnail-drive.mypikpak.com/v0/screenshot-thumbnails/${gcid.canonicalGcid()}/240/720"

/**
 * The CID of [detail]'s original, read from its octet-stream link: at most
 * 60 KB whatever the size. [gcidByCid] takes no gcid, so a caller that wants
 * to check later whether PikPak still holds some content keeps this beside it.
 */
suspend fun PikPakClient.sampleCid(detail: FileDetail): String {
    val url = detail.downloadUrl ?: throw PikPakException(-1, "sampleCid: file ${detail.id} has no download link")
    val parts = XunleiCid.windows(detail.sizeBytes).map { (offset, length) ->
        if (length == 0) {
            ByteArray(0)
        } else {
            streamRangeFromUrl(url, start = offset, length = length.toLong()) { it.channel.toByteArray() }
        }
    }
    return XunleiCid.digest(parts)
}

/**
 * The gcid of content PikPak already holds with this [cid] (see [XunleiCid])
 * and [size] (`GET /drive/v1/resource/cid`), or null when it holds none.
 *
 * The server checks the size too: the right CID with the size off by one byte
 * is a miss. A miss is `200 {"gcid": ""}`, not an error; a zero size or a
 * malformed CID is a 400.
 *
 * It reads without creating anything, so it doubles as a check that content
 * is still in the index: a free account that had never held a file got its
 * gcid back from the CID (2026-09-29). The endpoint has no gcid-keyed form
 * (`?gcid=` answers 400 "cid and file_size is required"), so a caller that
 * wants the check later has to keep the CID. A hit shows the index has the
 * content, not that nobody has let it go since; only [instantCreate] proves it.
 */
suspend fun PikPakClient.gcidByCid(cid: String, size: Long): String? {
    require(size > 0) { "size must be positive" }
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(
            PikPakConstants.DRIVE_BASE,
            "/drive/v1/resource/cid",
            mapOf("cid" to cid.lowercase(), "file_size" to size.toString()),
        ),
        captchaAction = "GET:/drive/v1/resource/cid",
    )
    val gcid = (response as? JsonObject)?.get("gcid")?.jsonPrimitive?.contentOrNull
    return gcid?.takeIf { it.isNotEmpty() }?.canonicalGcid()
}
