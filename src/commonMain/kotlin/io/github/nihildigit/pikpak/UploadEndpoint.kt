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
import kotlin.time.Clock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
    val name = source.name
    val hash = PikPakHash.fromPath(source)

    val initBody = buildJsonObject {
        put("kind", FileKind.FILE)
        put("name", name)
        put("size", size.toString())
        put("hash", hash)
        put("upload_type", "UPLOAD_TYPE_RESUMABLE")
        if (parentId.isNotEmpty()) put("parent_id", parentId)
        putJsonObject("body") {
            put("duration", "")
            put("width", "")
            put("height", "")
        }
        putJsonObject("objProvider") { put("provider", "UPLOAD_TYPE_UNKNOWN") }
    }

    val initResponse = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE/drive/v1/files",
        captchaAction = "POST:/drive/v1/files",
    ) { jsonBody(json, initBody) }

    val initObj = initResponse as JsonObject
    val fileNode = initObj["file"]?.jsonObject ?: throw PikPakException(-1, "upload: missing file in init response")
    val phase = fileNode["phase"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val fileId = fileNode["id"]?.jsonPrimitive?.contentOrNull.orEmpty()

    if (phase == "PHASE_TYPE_COMPLETE") {
        return UploadResult(fileId = fileId, instantUpload = true, bytesUploaded = 0L)
    }

    val params = initObj["resumable"]?.jsonObject?.get("params")?.jsonObject
        ?: throw PikPakException(-1, "upload: missing resumable.params for phase=$phase")
    val oss = OssParams(
        bucket = params["bucket"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        accessKeyId = params["access_key_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        accessKeySecret = params["access_key_secret"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        endpoint = params["endpoint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        key = params["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        securityToken = params["security_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    val uploadId = ossInitiate(oss)
    val parts = ossUploadParts(oss, uploadId, source, size)
    ossComplete(oss, uploadId, parts)
    return UploadResult(fileId = fileId, instantUpload = false, bytesUploaded = size)
}

private data class OssParams(
    val bucket: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    val endpoint: String,
    val key: String,
    val securityToken: String,
)

private data class UploadedPart(val partNumber: Int, val eTag: String)

private suspend fun PikPakClient.ossInitiate(oss: OssParams): String {
    val response = ossRequest(
        method = HttpMethod.Post,
        oss = oss,
        rawQuery = "uploads",
    )
    val xml = response.bodyAsText()
    return Regex("<UploadId>(.+?)</UploadId>").find(xml)?.groupValues?.get(1)
        ?: throw PikPakException(-1, "upload: OSS InitiateMultipartUpload missing UploadId\n$xml")
}

private suspend fun PikPakClient.ossUploadParts(
    oss: OssParams,
    uploadId: String,
    source: Path,
    size: Long,
): List<UploadedPart> {
    val chunkSize = computeChunkSize(size)
    val parts = mutableListOf<UploadedPart>()
    SystemFileSystem.source(source).buffered().use { input ->
        var partNumber = 1
        var remaining = size
        while (remaining > 0) {
            val want = minOf(remaining, chunkSize).toInt()
            val bytes = input.readByteArray(want)
            if (bytes.size != want) throw PikPakException(-1, "upload: source ended early at part $partNumber")

            val eTag = ossUploadPart(oss, uploadId, partNumber, bytes)
            parts += UploadedPart(partNumber, eTag)
            remaining -= want
            partNumber++
        }
    }
    return parts
}

private suspend fun PikPakClient.ossUploadPart(
    oss: OssParams,
    uploadId: String,
    partNumber: Int,
    body: ByteArray,
): String {
    val response = ossRequest(
        method = HttpMethod.Put,
        oss = oss,
        rawQuery = "partNumber=$partNumber&uploadId=$uploadId",
        body = body,
    )
    val raw = response.headers[HttpHeaders.ETag] ?: throw PikPakException(-1, "upload: part $partNumber missing ETag")
    return raw.trim('"')
}

private suspend fun PikPakClient.ossComplete(
    oss: OssParams,
    uploadId: String,
    parts: List<UploadedPart>,
) {
    val xml = buildString {
        append("<CompleteMultipartUpload>")
        for (p in parts) {
            append("<Part><PartNumber>${p.partNumber}</PartNumber><ETag>${p.eTag}</ETag></Part>")
        }
        append("</CompleteMultipartUpload>")
    }
    ossRequest(
        method = HttpMethod.Post,
        oss = oss,
        rawQuery = "uploadId=$uploadId",
        body = xml.encodeToByteArray(),
    )
}

private suspend fun PikPakClient.ossRequest(
    method: HttpMethod,
    oss: OssParams,
    rawQuery: String,
    body: ByteArray? = null,
): HttpResponse {
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
    val url = "https://${oss.endpoint}$ossPath?$rawQuery"
    return http.sendRaw(method, url) {
        applyOssHeaders(date, oss.securityToken, auth)
        if (body != null) setBody(body)
    }
}

private fun HttpRequestBuilder.applyOssHeaders(date: String, securityToken: String, authorization: String) {
    headers {
        append(HttpHeaders.UserAgent, OSS_USER_AGENT)
        append(HttpHeaders.Date, date)
        append("X-Oss-Security-Token", securityToken)
        append(HttpHeaders.Authorization, authorization)
    }
    contentType(ContentType.parse(OSS_CONTENT_TYPE))
}

private fun computeChunkSize(fileSize: Long): Long {
    // Match the Go reference: ceil(fileSize / 10000), floored at 256 KiB.
    val ideal = (fileSize + 9_999L) / 10_000L
    return maxOf(ideal, DEFAULT_UPLOAD_CHUNK)
}
