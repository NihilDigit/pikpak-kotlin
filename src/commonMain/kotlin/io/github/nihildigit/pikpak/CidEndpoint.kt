package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
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
    fun of(size: Long, read: (offset: Long, length: Int) -> ByteArray): String {
        require(size >= 0) { "size must not be negative" }
        val sha1 = SHA1()
        if (size < WHOLE_FILE_BELOW) {
            sha1.update(read(0, size.toInt()))
        } else {
            sha1.update(read(0, WINDOW))
            sha1.update(read(size / 3, WINDOW))
            sha1.update(read(size - WINDOW, WINDOW))
        }
        return sha1.digest().toHex().uppercase()
    }
}

/**
 * The gcid of content PikPak already holds with this [cid] (see [XunleiCid])
 * and [size] (`GET /drive/v1/resource/cid`), or null when it holds none.
 *
 * The server checks the size too: the right CID with the size off by one byte
 * is a miss. A miss is `200 {"gcid": ""}`, not an error; a zero size or a
 * malformed CID is a 400.
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
    return gcid?.takeIf { it.isNotEmpty() }?.lowercase()
}
