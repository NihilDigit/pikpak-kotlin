package io.github.nihildigit.pikpak.internal

import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.kotlincrypto.macs.hmac.sha1.HmacSHA1

/**
 * Aliyun OSS HMAC-SHA1 signer. The PikPak resumable upload flow uploads file
 * chunks to OSS directly using temporary STS credentials returned by PikPak,
 * so we replicate just enough of OSS's signature scheme to satisfy that path.
 *
 * StringToSign layout matches the OSS v1 spec:
 *   VERB \n
 *   Content-MD5 \n
 *   Content-Type \n
 *   Date \n
 *   CanonicalizedOSSHeaders        // sorted "x-oss-*" headers
 *   CanonicalizedResource          // /<bucket><path>?<rawQuery>
 *
 * The Go reference includes the entire raw query in CanonicalizedResource
 * rather than only the OSS subresources subset; we match that intentionally
 * because the server validates against this exact form.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object OssSign {
    fun authorizationHeader(
        method: HttpMethod,
        bucket: String,
        path: String,
        rawQuery: String,
        contentType: String,
        date: String,
        ossHeaders: Headers,
        accessKeyId: String,
        accessKeySecret: String,
    ): String {
        val sb = StringBuilder()
        sb.append(method.value).append('\n')
        sb.append('\n') // Content-MD5 — always empty for these requests
        sb.append(contentType).append('\n')
        sb.append(date).append('\n')

        val sorted = ossHeaders.entries()
            .filter { it.key.lowercase().startsWith("x-oss-") }
            .map { it.key.lowercase() to (it.value.firstOrNull().orEmpty()) }
            .sortedBy { it.first }
        for ((k, v) in sorted) sb.append(k).append(':').append(v).append('\n')

        sb.append('/').append(bucket).append(path)
        if (rawQuery.isNotEmpty()) sb.append('?').append(rawQuery)

        val mac = HmacSHA1(accessKeySecret.encodeToByteArray())
        val sig = Base64.encode(mac.doFinal(sb.toString().encodeToByteArray()))
        return "OSS $accessKeyId:$sig"
    }
}
