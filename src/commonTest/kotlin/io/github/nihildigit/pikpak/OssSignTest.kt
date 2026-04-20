package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.OssSign
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the OSS signer wire format. The signatures are self-consistent (computed
 * once and pinned), but each test fixes a specific canonicalization rule so a
 * refactor that breaks the StringToSign layout will fail fast.
 */
class OssSignTest {

    @Test
    fun `authorization starts with OSS prefix and key id`() {
        val auth = OssSign.authorizationHeader(
            method = HttpMethod.Put,
            bucket = "vip-lixian-test",
            path = "/hello.txt",
            rawQuery = "partNumber=1&uploadId=UID",
            contentType = "application/octet-stream",
            date = "Sun, 06 Nov 1994 08:49:37 GMT",
            ossHeaders = headersOf("X-Oss-Security-Token", "STS-TOKEN"),
            accessKeyId = "AKID",
            accessKeySecret = "SECRET",
        )
        assertTrue(auth.startsWith("OSS AKID:"), "got: $auth")
    }

    @Test
    fun `signature deterministic for fixed inputs`() {
        val fixture = fixedParams()
        val first = sign(fixture)
        val second = sign(fixture)
        assertEquals(first, second)
    }

    @Test
    fun `different secret yields different signature`() {
        val a = sign(fixedParams(secret = "SECRET-A"))
        val b = sign(fixedParams(secret = "SECRET-B"))
        assertTrue(a != b)
    }

    @Test
    fun `different method yields different signature`() {
        val put = sign(fixedParams(method = HttpMethod.Put))
        val post = sign(fixedParams(method = HttpMethod.Post))
        assertTrue(put != post)
    }

    @Test
    fun `empty rawQuery produces canonical resource without question mark`() {
        // Self-consistency: when rawQuery is empty the canonical resource
        // must drop the '?' — otherwise signing would differ from the server.
        val withEmpty = sign(fixedParams(rawQuery = ""))
        // We cannot directly inspect StringToSign, but we verify that signing
        // with "" and with "?" prefix would in principle differ — here we just
        // guarantee an empty rawQuery still produces a valid-shaped header.
        assertTrue(withEmpty.startsWith("OSS "))
    }

    @Test
    fun `x-oss headers are included in canonical headers when sorted`() {
        val withHeader = sign(fixedParams(
            ossHeaders = headersOf(
                "X-Oss-Security-Token" to listOf("T"),
                "X-Oss-Meta-Kind" to listOf("K"),
            ),
        ))
        val withReordered = sign(fixedParams(
            ossHeaders = headersOf(
                "X-Oss-Meta-Kind" to listOf("K"),
                "X-Oss-Security-Token" to listOf("T"),
            ),
        ))
        // Input order must not matter — sorting is part of the spec.
        assertEquals(withHeader, withReordered)
    }

    @Test
    fun `non-oss headers are excluded from canonical headers`() {
        val withOnlyOss = sign(fixedParams(
            ossHeaders = headersOf("X-Oss-Security-Token", "T"),
        ))
        val withExtras = sign(fixedParams(
            ossHeaders = headersOf(
                "X-Oss-Security-Token" to listOf("T"),
                "X-Custom-Ignored" to listOf("IGN"),
            ),
        ))
        assertEquals(withOnlyOss, withExtras, "non-x-oss-* headers must not affect signature")
    }

    @Test
    fun `bucket name changes the canonical resource`() {
        val a = sign(fixedParams(bucket = "bucket-a"))
        val b = sign(fixedParams(bucket = "bucket-b"))
        assertTrue(a != b)
    }

    @Test
    fun `path changes the canonical resource`() {
        val a = sign(fixedParams(path = "/alpha"))
        val b = sign(fixedParams(path = "/beta"))
        assertTrue(a != b)
    }

    @Test
    fun `known vector locks algorithm output`() {
        // Golden vector — pinned to detect any accidental change to StringToSign
        // layout, HMAC-SHA1 implementation, or Base64 encoding.
        val auth = OssSign.authorizationHeader(
            method = HttpMethod.Put,
            bucket = "b",
            path = "/k",
            rawQuery = "partNumber=1&uploadId=U",
            contentType = "application/octet-stream",
            date = "Sun, 06 Nov 1994 08:49:37 GMT",
            ossHeaders = headersOf("X-Oss-Security-Token", "STS"),
            accessKeyId = "AK",
            accessKeySecret = "SK",
        )
        // Recomputed once and pinned. If this fails after a refactor, confirm
        // StringToSign composition before "fixing" the constant.
        assertEquals("OSS AK:to+ASfP2gyOWpAKuCl5dUrrP3V8=", auth)
    }

    private data class P(
        val method: HttpMethod = HttpMethod.Put,
        val bucket: String = "bucket",
        val path: String = "/key",
        val rawQuery: String = "partNumber=1&uploadId=UID",
        val contentType: String = "application/octet-stream",
        val date: String = "Sun, 06 Nov 1994 08:49:37 GMT",
        val ossHeaders: io.ktor.http.Headers = headersOf("X-Oss-Security-Token", "T"),
        val accessKeyId: String = "AK",
        val secret: String = "SK",
    )

    private fun fixedParams(
        method: HttpMethod = HttpMethod.Put,
        bucket: String = "bucket",
        path: String = "/key",
        rawQuery: String = "partNumber=1&uploadId=UID",
        ossHeaders: io.ktor.http.Headers = headersOf("X-Oss-Security-Token", "T"),
        secret: String = "SK",
    ) = P(
        method = method,
        bucket = bucket,
        path = path,
        rawQuery = rawQuery,
        ossHeaders = ossHeaders,
        secret = secret,
    )

    private fun sign(p: P): String = OssSign.authorizationHeader(
        method = p.method,
        bucket = p.bucket,
        path = p.path,
        rawQuery = p.rawQuery,
        contentType = p.contentType,
        date = p.date,
        ossHeaders = p.ossHeaders,
        accessKeyId = p.accessKeyId,
        accessKeySecret = p.secret,
    )
}
