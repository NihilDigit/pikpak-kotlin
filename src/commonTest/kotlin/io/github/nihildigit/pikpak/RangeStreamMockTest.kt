package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class RangeStreamMockTest {

    // --- happy path: closed range ---

    @Test
    fun `happy closed range returns correct RangeStream fields`() = runBlocking {
        val body = ByteArray(50) { it.toByte() }
        var requestCount = 0

        val client = clientWithCdnHandler { req ->
            requestCount++
            assertEquals("bytes=100-149", req.headers[HttpHeaders.Range])
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes 100-149/10000")
                    append(HttpHeaders.ContentLength, "50")
                },
            )
        }

        val stream = client.streamRangeFromUrl("https://cdn/file", start = 100L, length = 50L)
        assertEquals(50L, stream.contentLength)
        assertEquals(10000L, stream.totalSize)
        assertEquals(100L, stream.rangeStart)
        assertEquals(149L, stream.rangeEndInclusive)

        // Verify channel bytes
        val received = ByteArray(50)
        val n = stream.channel.readAvailable(received, 0, 50)
        assertEquals(50, n)
        assertEquals(body.toList(), received.toList())
        stream.channel.cancel(CancellationException("test done"))

        assertEquals(1, requestCount)
        client.close()
    }

    // --- happy path: open-ended range ---

    @Test
    fun `happy open-ended range sends bytes=start- header`() = runBlocking {
        var capturedRange: String? = null
        val body = ByteArray(10) { it.toByte() }

        val client = clientWithCdnHandler { req ->
            capturedRange = req.headers[HttpHeaders.Range]
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes 100-109/200")
                    append(HttpHeaders.ContentLength, "10")
                },
            )
        }

        val stream = client.streamRangeFromUrl("https://cdn/file", start = 100L, length = null)
        assertEquals("bytes=100-", capturedRange)
        stream.channel.cancel(CancellationException("test done"))
        client.close()
    }

    // --- server returns 200 (Range ignored) ---

    @Test
    fun `server 200 throws PikPakException`() = runBlocking {
        val client = clientWithCdnHandler { _ ->
            respond(
                content = ByteReadChannel("huge file content".encodeToByteArray()),
                status = HttpStatusCode.OK,
            )
        }

        assertFailsWith<PikPakException> {
            client.streamRangeFromUrl("https://cdn/file", start = 100L, length = 50L)
        }
        client.close()
    }

    // --- 416 Range Not Satisfiable ---

    @Test
    fun `416 throws PikPakException with httpStatus 416`() = runBlocking {
        val client = clientWithCdnHandler { _ ->
            respond(
                content = ByteReadChannel(ByteArray(0)),
                status = HttpStatusCode.RequestedRangeNotSatisfiable,
            )
        }

        val ex = assertFailsWith<PikPakException> {
            client.streamRangeFromUrl("https://cdn/file", start = 99999L, length = 1L)
        }
        assertEquals(416, ex.httpStatus)
        client.close()
    }

    // --- caller bug: start = -1 ---

    @Test
    fun `start negative throws before any HTTP call`() = runBlocking {
        var httpInvoked = false

        val client = clientWithCdnHandler { _ ->
            httpInvoked = true
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.PartialContent)
        }

        assertFailsWith<PikPakException> {
            client.streamRangeFromUrl("https://cdn/file", start = -1L, length = 50L)
        }
        assertEquals(false, httpInvoked, "HTTP must not be called for invalid start")
        client.close()
    }

    // --- caller bug: length = 0 ---

    @Test
    fun `length zero throws before any HTTP call`() = runBlocking {
        var httpInvoked = false

        val client = clientWithCdnHandler { _ ->
            httpInvoked = true
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.PartialContent)
        }

        assertFailsWith<PikPakException> {
            client.streamRangeFromUrl("https://cdn/file", start = 0L, length = 0L)
        }
        assertEquals(false, httpInvoked, "HTTP must not be called for invalid length")
        client.close()
    }

    // --- Content-Range missing ---

    @Test
    fun `missing Content-Range returns negative sentinel fields`() = runBlocking {
        val body = ByteArray(50) { it.toByte() }

        val client = clientWithCdnHandler { _ ->
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentLength, "50"),
            )
        }

        val stream = client.streamRangeFromUrl("https://cdn/file", start = 0L, length = 50L)
        assertEquals(-1L, stream.totalSize)
        assertEquals(-1L, stream.rangeStart)
        assertEquals(-1L, stream.rangeEndInclusive)
        // contentLength comes from Content-Length header
        assertEquals(50L, stream.contentLength)
        stream.channel.cancel(CancellationException("test done"))
        client.close()
    }

    // --- Content-Range with * total ---

    @Test
    fun `Content-Range with star total sets totalSize to -1 but preserves rangeStart and End`() = runBlocking {
        val body = ByteArray(100) { it.toByte() }

        val client = clientWithCdnHandler { _ ->
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes 0-99/*")
                    append(HttpHeaders.ContentLength, "100")
                },
            )
        }

        val stream = client.streamRangeFromUrl("https://cdn/file", start = 0L, length = 100L)
        assertEquals(-1L, stream.totalSize)
        assertEquals(0L, stream.rangeStart)
        assertEquals(99L, stream.rangeEndInclusive)
        assertEquals(100L, stream.contentLength)
        stream.channel.cancel(CancellationException("test done"))
        client.close()
    }

    // --- helpers ---

    private fun clientWithCdnHandler(
        retryPolicy: RetryPolicy = RetryPolicy(
            maxAttempts = 2,
            initialDelay = 1.milliseconds,
            maxDelay = 2.milliseconds,
        ),
        cdnHandler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") ->
                    respond(
                        content = ByteReadChannel("""{"captcha_token":"CAP"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                path.endsWith("/v1/auth/signin") ->
                    respond(
                        content = ByteReadChannel(
                            """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> cdnHandler(req)
            }
        }
        val client = PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            retryPolicy = retryPolicy,
            httpClient = HttpClient(engine),
        )
        runBlocking { client.login() }
        return client
    }
}
