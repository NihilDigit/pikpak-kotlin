package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.OssSign
import io.github.nihildigit.pikpak.internal.formatHttpDate
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.EOFException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Result of [upload]. */
data class UploadResult(
    /** PikPak file id of the uploaded entry. */
    val fileId: String,
    /** True when PikPak recognized the content hash and skipped the actual upload. */
    val instantUpload: Boolean,
    /** Bytes actually transferred to OSS (zero on instant upload). */
    val bytesUploaded: Long,
)

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val DEFAULT_UPLOAD_CHUNK = 256L * 1024L
private const val OSS_USER_AGENT = "aliyun-sdk-android/2.9.5(Linux/Android 11/ONEPLUS%20A6000;RKQ1.201217.002)"
private const val OSS_CONTENT_TYPE = "application/octet-stream"

/**
 * Uploads a local file to PikPak under [parentId] (empty for root).
 *
 * Three-phase flow:
 *  1. Compute the gcid hash and POST it to `/drive/v1/files`. PikPak may
 *     respond with `PHASE_TYPE_COMPLETE` — that's an instant upload, no bytes
 *     are transferred. We return immediately in that case.
 *  2. Otherwise PikPak returns Aliyun OSS STS credentials. We initiate an OSS
 *     multipart upload, then PUT each chunk with HMAC-SHA1 signed requests.
 *  3. Send the part list as XML to complete the multipart upload.
 *
 * Sequential by design — for very large files a future revision can parallelize
 * step 2; the OSS protocol supports it but most accounts hit per-account
 * upload bandwidth caps long before chunk parallelism helps. Use the
 * [PikPakClient]'s [RetryPolicy] to control transient retry.
 */
suspend fun PikPakClient.upload(parentId: String, source: Path): UploadResult {
    val size = SystemFileSystem.metadataOrNull(source)?.size
        ?: throw IllegalArgumentException("upload source does not exist: $source")
    return upload(
        parentId = parentId,
        name = source.name,
        size = size,
        gcid = PikPakHash.fromPath(source),
        open = { SystemFileSystem.source(source) },
    )
}

/**
 * [upload] for content that is not a [Path] on this file system, an Android
 * `content:` URI say, or whose [gcid] the caller already has: from
 * [PikPakHash.fromSource], or from [gcidByCid] without reading the whole file.
 *
 * [gcid] must be the content's: PikPak requires one but does not check it.
 * Measured 2026-09-25, an upload under a wrong 40-hex hash completed and kept
 * the wrong value, so instant uploads of that value would be served these
 * bytes. A [gcidByCid] miss therefore means hashing the file, not skipping it.
 *
 * [open] yields the content from its first byte and is called at most once,
 * only when PikPak does not recognise [gcid]. [onProgress] receives the bytes
 * sent so far after each part.
 *
 * This is [startUpload], [continueUpload] and, on any failure or
 * cancellation, [cancelUpload]. Use those three directly for an upload that
 * should survive the process.
 */
suspend fun PikPakClient.upload(
    parentId: String,
    name: String,
    size: Long,
    gcid: String,
    open: () -> RawSource,
    onProgress: (uploadedBytes: Long) -> Unit = {},
): UploadResult {
    val session = when (val start = startUpload(parentId, name, size, gcid)) {
        is UploadStart.Instant -> return UploadResult(fileId = start.fileId, instantUpload = true, bytesUploaded = 0L)
        is UploadStart.Pending -> start.session
    }
    try {
        // A fresh session has no parts yet, so continueUpload asks for offset 0 only
        continueUpload(session, open = { offset -> check(offset == 0L) { "unexpected offset $offset" }; open() }, onProgress)
    } catch (e: Throwable) {
        // A cancelled coroutine cannot send these without NonCancellable. Best
        // effort: the original failure is what the caller needs to see.
        withContext(NonCancellable) { runCatching { cancelUpload(session) } }
        throw e
    }
    return UploadResult(fileId = session.fileId, instantUpload = false, bytesUploaded = size)
}

/** Outcome of [startUpload]. */
sealed interface UploadStart {
    /** PikPak recognised the gcid; the file is complete and no bytes move. */
    data class Instant(val fileId: String) : UploadStart

    /** The bytes have to go up: pass the session to [continueUpload]. */
    data class Pending(val session: UploadSession) : UploadStart
}

/**
 * Everything [continueUpload] needs to carry an upload on in another process,
 * serializable so a caller can persist it. It holds live OSS credentials: keep
 * it where the account's session is kept, not in logs.
 */
@Serializable
data class UploadSession(
    /** The drive file, in `PHASE_TYPE_PENDING` until the upload completes. */
    val fileId: String,
    val size: Long,
    /** Fixed at start: every part but the last has this size. */
    val partSize: Long,
    val uploadId: String,
    val bucket: String,
    val endpoint: String,
    val key: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String,
    /**
     * When the credentials stop working, as PikPak sends it
     * (`2026-09-26T02:42:09.000+08:00`); 12 hours after start, measured
     * 2026-09-25. Past it the upload cannot be continued, only cancelled.
     */
    val expiration: String,
)

/**
 * Creates the drive file for an upload of [size] bytes named [name] under
 * [parentId] and, unless PikPak already holds [gcid], opens the OSS multipart
 * upload its bytes go to. See the [upload] overload taking a gcid for why
 * [gcid] must be right.
 *
 * A [UploadStart.Pending] result leaves a visible `PHASE_TYPE_PENDING` file
 * and an upload task in the drive (measured 2026-09-25). Neither goes away by
 * itself, and starting again does not pick them up: the new file is named
 * "name(1)" beside the old one. Finish it with [continueUpload] or remove it
 * with [cancelUpload].
 */
suspend fun PikPakClient.startUpload(parentId: String, name: String, size: Long, gcid: String): UploadStart {
    val node = createUploadNode(parentId, name, size, gcid)
    val fileId = node.fileId
    if (node.phase == TaskPhase.COMPLETE) return UploadStart.Instant(fileId)

    try {
        val params = ((node.response["resumable"] as? JsonObject)?.get("params") as? JsonObject)
            ?: throw PikPakException(-1, "upload: missing resumable.params for phase=${node.phase}")
        fun param(name: String) = params[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        val withoutUploadId = UploadSession(
            fileId = fileId,
            size = size,
            partSize = computeChunkSize(size),
            uploadId = "",
            bucket = param("bucket"),
            endpoint = param("endpoint"),
            key = param("key"),
            accessKeyId = param("access_key_id"),
            accessKeySecret = param("access_key_secret"),
            securityToken = param("security_token"),
            expiration = param("expiration"),
        )
        return UploadStart.Pending(withoutUploadId.copy(uploadId = ossInitiate(withoutUploadId)))
    } catch (e: Throwable) {
        // No session reaches the caller, so nobody else could remove the pending file
        withContext(NonCancellable) { if (fileId.isNotEmpty()) runCatching { deleteFile(fileId) } }
        throw e
    }
}

/** What `POST /drive/v1/files` made of an upload request: the new file and how far along it is. */
internal class UploadNode(val fileId: String, val phase: String, val response: JsonObject)

/**
 * The drive half of an upload: creates the file for [gcid] under [parentId]. PikPak answers
 * COMPLETE when it already holds the content, and PENDING with OSS credentials when the bytes
 * have to go up. Shared by [startUpload], which goes on to send them, and [instantCreate],
 * which has none to send.
 */
internal suspend fun PikPakClient.createUploadNode(parentId: String, name: String, size: Long, gcid: String): UploadNode {
    val body = buildJsonObject {
        put("kind", FileKind.FILE)
        put("name", name)
        put("size", size.toString())
        put("hash", gcid)
        put("upload_type", "UPLOAD_TYPE_RESUMABLE")
        if (parentId.isNotEmpty()) put("parent_id", parentId)
        putJsonObject("body") {
            put("duration", "")
            put("width", "")
            put("height", "")
        }
        putJsonObject("objProvider") { put("provider", "UPLOAD_TYPE_UNKNOWN") }
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE/drive/v1/files",
        captchaAction = "POST:/drive/v1/files",
    ) { jsonBody(json, body) }
    // as? rather than jsonObject: an explicit "file": null must reach the error below, not a cast failure
    val obj = response as? JsonObject
    val file = obj?.get("file") as? JsonObject
        ?: throw PikPakException(-1, "upload: missing file in response for $name", rawBody = response.toString())
    return UploadNode(
        fileId = file["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        phase = file["phase"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        response = obj,
    )
}

/**
 * Sends whatever of [session] is not on OSS yet and completes the upload; the
 * file is then `PHASE_TYPE_COMPLETE`. Works from a fresh process: measured
 * 2026-09-25, a session saved after one part was finished by another JVM and
 * the file read back byte for byte.
 *
 * Asks OSS which parts arrived and reads only the rest. [open] yields the
 * content from byte `offset`; it is called once per run of missing parts, so
 * once for an upload that stopped partway. [onProgress] receives the bytes on
 * OSS so far, first those already there, then after each part.
 *
 * A failure leaves the session as it was, to be continued again or given up
 * with [cancelUpload]. Once [UploadSession.expiration] has passed, OSS refuses
 * the credentials with a 403, which reaches the caller as [UrlExpiredException],
 * and only [cancelUpload] is left.
 *
 * An upload OSS no longer knows (404) may be one that completed with its answer
 * lost: the completion is not replayed, but a caller continuing the session
 * again would ask for its parts and find none. So a 404 is checked against the
 * drive file, and one already COMPLETE returns normally.
 */
suspend fun PikPakClient.continueUpload(
    session: UploadSession,
    open: (offset: Long) -> RawSource,
    onProgress: (uploadedBytes: Long) -> Unit = {},
) {
    try {
        sendMissingParts(session, open, onProgress)
    } catch (e: PikPakException) {
        if (e.httpStatus != 404 || !uploadCompleted(session.fileId)) throw e
    }
}

private suspend fun PikPakClient.sendMissingParts(
    session: UploadSession,
    open: (offset: Long) -> RawSource,
    onProgress: (uploadedBytes: Long) -> Unit,
) {
    val parts = listUploadedParts(session).toMutableMap()
    val partCount = ((session.size + session.partSize - 1) / session.partSize).toInt().coerceAtLeast(1)
    fun partLength(number: Int) = minOf(session.partSize, session.size - (number - 1) * session.partSize).toInt()
    onProgress(parts.keys.sumOf { partLength(it).toLong() })

    var input: kotlinx.io.Source? = null
    var inputPart = 0 // the part the open input is positioned at
    try {
        for (number in 1..partCount) {
            if (number in parts) continue
            if (input == null || inputPart != number) {
                input?.close()
                input = open((number - 1) * session.partSize).buffered()
                inputPart = number
            }
            val want = partLength(number)
            // readByteArray throws on a short source rather than returning fewer bytes
            val bytes = try {
                input.readByteArray(want)
            } catch (e: EOFException) {
                throw PikPakException(-1, "upload: source ended early at part $number", cause = e)
            }
            parts[number] = ossUploadPart(session, number, bytes)
            inputPart = number + 1
            onProgress(parts.keys.sumOf { partLength(it).toLong() })
        }
    } finally {
        input?.close()
    }
    ossComplete(session, parts)
}

/**
 * Gives an upload up: aborts the OSS multipart upload so its parts are not
 * kept, then permanently deletes the pending drive file [startUpload] made.
 * The abort is best effort, since expired credentials cannot send it; the
 * delete goes through the account and throws on failure.
 *
 * A drive file that is already COMPLETE is left alone: the upload finished,
 * perhaps with its answer lost, and the file is the user's now, not a leftover.
 * Deleting it here used to destroy a finished upload that merely looked failed.
 */
suspend fun PikPakClient.cancelUpload(session: UploadSession) {
    runCatching { ossRequest(method = HttpMethod.Delete, oss = session, rawQuery = "uploadId=${session.uploadId}") { } }
    if (uploadCompleted(session.fileId)) return
    deleteFile(session.fileId)
}

private suspend fun PikPakClient.uploadCompleted(fileId: String): Boolean = try {
    getFile(fileId).phase == TaskPhase.COMPLETE
} catch (e: PikPakException) {
    false
}

/**
 * Part number to ETag of every part OSS holds for [session]. OSS pages the
 * list; [pageSize] is a parameter so a test can make three parts span pages.
 */
internal suspend fun PikPakClient.listUploadedParts(session: UploadSession, pageSize: Int = 1000): Map<Int, String> {
    val parts = mutableMapOf<Int, String>()
    var marker = 0
    while (true) {
        val xml = ossRequest(
            method = HttpMethod.Get,
            oss = session,
            rawQuery = "uploadId=${session.uploadId}",
            unsignedQuery = "max-parts=$pageSize&part-number-marker=$marker",
        ) { it.bodyAsText() }
        PART_PATTERN.findAll(xml).forEach { parts[it.groupValues[1].toInt()] = it.groupValues[2] }
        val next = Regex("<NextPartNumberMarker>(\\d+)</NextPartNumberMarker>").find(xml)?.groupValues?.get(1)?.toInt()
        if (!xml.contains("<IsTruncated>true</IsTruncated>") || next == null || next <= marker) return parts
        marker = next
    }
}

// [\s\S] rather than DOT_MATCHES_ALL, which only the JVM has
private val PART_PATTERN = Regex(
    "<Part>[\\s\\S]*?<PartNumber>(\\d+)</PartNumber>[\\s\\S]*?<ETag>\"?([^<\"]+)\"?</ETag>[\\s\\S]*?</Part>",
)

private suspend fun PikPakClient.ossInitiate(oss: UploadSession): String {
    val xml = ossRequest(
        method = HttpMethod.Post,
        oss = oss,
        rawQuery = "uploads",
    ) { it.bodyAsText() }
    return Regex("<UploadId>(.+?)</UploadId>").find(xml)?.groupValues?.get(1)
        ?: throw PikPakException(-1, "upload: OSS InitiateMultipartUpload missing UploadId\n$xml")
}

private suspend fun PikPakClient.ossUploadPart(
    session: UploadSession,
    partNumber: Int,
    body: ByteArray,
): String {
    val raw = ossRequest(
        method = HttpMethod.Put,
        oss = session,
        rawQuery = "partNumber=$partNumber&uploadId=${session.uploadId}",
        body = body,
    ) { it.headers[HttpHeaders.ETag] }
        ?: throw PikPakException(-1, "upload: part $partNumber missing ETag")
    return raw.trim('"')
}

private suspend fun PikPakClient.ossComplete(session: UploadSession, parts: Map<Int, String>) {
    val xml = buildString {
        append("<CompleteMultipartUpload>")
        for ((number, eTag) in parts.entries.sortedBy { it.key }) {
            append("<Part><PartNumber>$number</PartNumber><ETag>$eTag</ETag></Part>")
        }
        append("</CompleteMultipartUpload>")
    }
    ossRequest(
        method = HttpMethod.Post,
        oss = session,
        rawQuery = "uploadId=${session.uploadId}",
        body = xml.encodeToByteArray(),
    ) { }
}

/**
 * One signed OSS exchange. Verifies the status before handing the live
 * response to [block]: a failed exchange still carries a body, an XML error
 * document, and a caller that only reads a header off it (ETag, say) would see
 * "success with a missing header". A 401 or 403 never gets this far — sendRaw
 * turns it into [UrlExpiredException] first — so this catches the rest, a 404
 * for an upload OSS no longer knows among them. That also means a 403 from a
 * skewed clock in the Date header reads as expiry; OSS says which it was only
 * in the body's `<Code>`, which is not parsed.
 *
 * [rawQuery] is signed whole, as the Go reference does; it must hold only OSS
 * subresources (`uploads`, `uploadId`, `partNumber`). Paging parameters are
 * not subresources, and OSS rejects a signature that covers them, so they go
 * in [unsignedQuery].
 */
private suspend fun <T> PikPakClient.ossRequest(
    method: HttpMethod,
    oss: UploadSession,
    rawQuery: String,
    unsignedQuery: String = "",
    body: ByteArray? = null,
    block: suspend (HttpResponse) -> T,
): T {
    val date = Clock.System.now().formatHttpDate()
    val ossHeaders = headersOf("X-Oss-Security-Token", oss.securityToken)
    val ossPath = "/${oss.key}"
    val auth = OssSign.authorizationHeader(
        method = method,
        bucket = oss.bucket,
        path = ossPath,
        rawQuery = rawQuery,
        contentType = OSS_CONTENT_TYPE,
        date = date,
        ossHeaders = ossHeaders,
        accessKeyId = oss.accessKeyId,
        accessKeySecret = oss.accessKeySecret,
    )
    val query = if (unsignedQuery.isEmpty()) rawQuery else "$rawQuery&$unsignedQuery"
    val url = "https://${oss.endpoint}$ossPath?$query"
    return http.sendRaw(
        method = method,
        url = url,
        configure = {
            applyOssHeaders(date, oss.securityToken, auth)
            if (body != null) setBody(body)
        },
    ) { response ->
        if (!response.status.isSuccess()) {
            throw PikPakException(
                errorCode = -1,
                errorMessage = "OSS ${method.value} $rawQuery failed with HTTP ${response.status.value}",
                httpStatus = response.status.value,
                rawBody = response.bodyAsText(),
            )
        }
        block(response)
    }
}

private fun HttpRequestBuilder.applyOssHeaders(date: String, securityToken: String, authorization: String) {
    headers {
        // set, not append: the OSS signature covers exactly one value per
        // header, and an injected client's own User-Agent would break it.
        set(HttpHeaders.UserAgent, OSS_USER_AGENT)
        set(HttpHeaders.Date, date)
        set("X-Oss-Security-Token", securityToken)
        set(HttpHeaders.Authorization, authorization)
    }
    contentType(ContentType.parse(OSS_CONTENT_TYPE))
}

private fun computeChunkSize(fileSize: Long): Long {
    // Match the Go reference: ceil(fileSize / 10000), floored at 256 KiB.
    val ideal = (fileSize + 9_999L) / 10_000L
    return maxOf(ideal, DEFAULT_UPLOAD_CHUNK)
}
