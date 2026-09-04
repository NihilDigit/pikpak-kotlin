package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Variant selection and the reader locked to one variant. The point of the
 * lock is that a refresh must land on the same bytes: the tests below check
 * both that it does and that a variant which disappeared fails loudly.
 */
class VariantSelectionMockTest {

    private val content = ByteArray(512) { (it % 251).toByte() }

    // --- pure selection ---

    @Test
    fun `a preferred resolution is selected over the original`() {
        val resolved = detail().resolveVariant(VariantPreference.Resolution("720P"))
        assertEquals("M720", resolved.mediaId)
        assertEquals("720P", resolved.label)
        assertEquals(false, resolved.isOrigin)
        assertEquals("https://cdn/ts720?sig=a", resolved.link.url)
        assertNull(resolved.sizeBytes, "a transcode does not carry its length in the metadata")
    }

    @Test
    fun `a missing resolution falls back to the original`() {
        val resolved = detail().resolveVariant(VariantPreference.Resolution("4K"))
        assertNull(resolved.mediaId)
        assertEquals("Original", resolved.label)
        assertEquals(1024L, resolved.sizeBytes)
    }

    @Test
    fun `a variant still transcoding falls back to the original`() {
        val stillTranscoding = transcode("M480", "480P", "https://cdn/ts480?sig=a").copy(video = null)
        val resolved = detail(medias = listOf(stillTranscoding))
            .resolveVariant(VariantPreference.Resolution("480P"))
        assertNull(resolved.mediaId, "no video metadata means the variant is not ready")
    }

    @Test
    fun `a variant with a blank link falls back to the original`() {
        val blank = transcode("M480", "480P", "")
        val resolved = detail(medias = listOf(blank))
            .resolveVariant(VariantPreference.Resolution("480P"))
        assertNull(resolved.mediaId, "a blank link means the variant is not ready")
    }

    @Test
    fun `the original resolves on a file with no medias at all`() {
        val resolved = detail(medias = emptyList()).resolveVariant(VariantPreference.Original)
        assertNull(resolved.mediaId)
        assertEquals("https://cdn/origin?sig=a", resolved.link.url)
        assertNull(resolved.video)
    }

    @Test
    fun `the original throws when the file has no octet-stream link`() {
        val e = assertFailsWith<PikPakException> {
            detail(links = emptyMap()).resolveVariant(VariantPreference.Original)
        }
        assertTrue(e.message!!.contains("octet-stream"), "the message must name the missing link: ${e.message}")
    }

    @Test
    fun `a stored media id reopens the same variant`() {
        val resolved = detail().variant("M1080")
        assertEquals("M1080", resolved?.mediaId)
        assertEquals("https://cdn/ts1080?sig=a", resolved?.link?.url)
    }

    @Test
    fun `an unknown media id resolves to null instead of another variant`() {
        assertNull(detail().variant("M-gone"))
    }

    @Test
    fun `a null media id is the original`() {
        val resolved = detail().variant(null)
        assertNull(resolved?.mediaId)
        assertEquals("Original", resolved?.label)
    }

    // --- remoteSize ---

    @Test
    fun `remoteSize reads the total out of the probe Content-Range`() = runBlocking {
        val client = clientWith(cdn = { probe(total = "98765") })
        assertEquals(98_765L, client.remoteSize("https://cdn/ts1080?sig=a"))
        client.close()
    }

    @Test
    fun `remoteSize throws when the server does not know the total`() = runBlocking {
        val client = clientWith(cdn = { probe(total = "*") })
        assertFailsWith<PikPakException> { client.remoteSize("https://cdn/ts1080?sig=a") }
        client.close()
    }

    // --- the reader locked to one variant ---

    @Test
    fun `an expired link is refreshed from the same media id`() = runBlocking {
        var details = 0
        val served = mutableListOf<String>()
        val client = clientWith(
            fileDetail = { details++; detailJson(sig = if (details == 1) "stale" else "fresh") },
            cdn = { req ->
                served += req.url.toString()
                if (req.url.parameters["sig"] == "stale") {
                    respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Forbidden)
                } else {
                    partial(req, content)
                }
            },
        )
        val reader = client.rangeReader("FID", "M720")

        val bytes = reader.readBytes(0, 64)
        assertContentEquals(content.copyOfRange(0, 64), bytes)
        assertEquals(2, details, "one initial detail plus one refresh")
        assertTrue(
            served.all { it.contains("/ts720") },
            "the refresh must return to the same variant: $served",
        )
        client.close()
    }

    @Test
    fun `a variant that disappeared fails the read instead of switching`() = runBlocking {
        var details = 0
        val client = clientWith(
            // The second detail no longer lists M720 — PikPak dropped the
            // transcode. Reading 1080P bytes at a 720P offset would be silent
            // corruption, so this has to fail.
            fileDetail = { details++; detailJson(sig = "stale", withSeven20 = details == 1) },
            cdn = { respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Forbidden) },
        )
        val reader = client.rangeReader("FID", "M720")

        val e = assertFailsWith<PikPakException> { reader.readBytes(0, 64) }
        assertTrue(
            e.message!!.contains("no longer present"),
            "the failure must name the vanished variant: ${e.message}",
        )
        client.close()
    }

    // --- fixtures ---

    private fun transcode(id: String, resolution: String, url: String) = MediaVariant(
        mediaId = id,
        mediaName = resolution,
        resolutionName = resolution,
        video = VideoInfo(videoType = "mpegts"),
        link = DownloadLink(url = url),
        isOrigin = false,
        category = "category_transcode",
    )

    private fun detail(
        links: Map<String, DownloadLink> = mapOf(
            FileDetail.OCTET_STREAM to DownloadLink(url = "https://cdn/origin?sig=a"),
        ),
        medias: List<MediaVariant> = listOf(
            transcode("M1080", "1080P", "https://cdn/ts1080?sig=a"),
            transcode("M720", "720P", "https://cdn/ts720?sig=a"),
        ),
    ) = FileDetail(id = "FID", name = "v.mkv", size = "1024", links = links, medias = medias)

    private fun transcodeJson(id: String, resolution: String, height: Int, sig: String): String =
        """{"media_id":"$id","media_name":"$resolution","resolution_name":"$resolution",""" +
            """"is_origin":false,"category":"category_transcode",""" +
            """"video":{"height":$height},"link":{"url":"https://cdn/ts$height?sig=$sig"}}"""

    private fun detailJson(sig: String, withSeven20: Boolean = true): String {
        val transcodes = listOfNotNull(
            transcodeJson("M1080", "1080P", 1080, sig),
            transcodeJson("M720", "720P", 720, sig).takeIf { withSeven20 },
        ).joinToString(",")
        return """{"id":"FID","name":"v.mkv","size":"1024",""" +
            """"links":{"application/octet-stream":{"url":"https://cdn/origin?sig=$sig"}},""" +
            """"medias":[$transcodes]}"""
    }

    private fun MockRequestHandleScope.probe(total: String): HttpResponseData = respond(
        content = ByteReadChannel(byteArrayOf(0)),
        status = HttpStatusCode.PartialContent,
        headers = Headers.build {
            append(HttpHeaders.ContentRange, "bytes 0-0/$total")
            append(HttpHeaders.ContentLength, "1")
        },
    )

    private fun MockRequestHandleScope.partial(req: HttpRequestData, body: ByteArray): HttpResponseData {
        val spec = req.headers[HttpHeaders.Range]!!.removePrefix("bytes=")
        val from = spec.substringBefore('-').toLong()
        val to = spec.substringAfter('-').toLongOrNull() ?: (body.size - 1L)
        val slice = body.copyOfRange(from.toInt(), (to + 1).toInt().coerceAtMost(body.size))
        return respond(
            content = ByteReadChannel(slice),
            status = HttpStatusCode.PartialContent,
            headers = Headers.build {
                append(HttpHeaders.ContentRange, "bytes $from-$to/${body.size}")
                append(HttpHeaders.ContentLength, slice.size.toString())
            },
        )
    }

    private fun clientWith(
        fileDetail: () -> String = { detailJson(sig = "a") },
        cdn: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> json("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    json("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                path.contains("/drive/v1/files/") -> json(fileDetail())
                else -> cdn(req)
            }
        }
        val mock = HttpClient(engine)
        val client = PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 1.milliseconds, maxDelay = 2.milliseconds),
            httpClient = mock,
            cdnHttpClient = mock,
        )
        runBlocking { client.login() }
        return client
    }

    private fun MockRequestHandleScope.json(body: String): HttpResponseData = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
