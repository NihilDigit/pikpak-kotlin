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
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ParallelDownloadMockTest {

    private val scratchFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        scratchFiles.forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
        // Also clean up any stray .part-N files from the scratch dir
        val dir = tmpDir()
        if (SystemFileSystem.exists(dir)) {
            runCatching {
                SystemFileSystem.list(dir).forEach { p ->
                    if (p.name.contains(".part-")) {
                        SystemFileSystem.delete(p, mustExist = false)
                    }
                }
            }
        }
    }

    // --- happy path: partCount=4, expectedSize supplied (no probe) ---

    @Test
    fun `happy path partCount=4 produces correct output with 4 range requests`() = runBlocking {
        val totalSize = 1000L
        val body = ByteArray(totalSize.toInt()) { it.toByte() }
        var requestCount = 0

        val client = clientWithAuth { req ->
            requestCount++
            val rangeHeader = req.headers[HttpHeaders.Range]
                ?: error("Range header missing for parallel part request")
            // parse "bytes=start-end" to produce the sub-slice
            val (start, end) = parseRangeHeader(rangeHeader)
            val length = (end - start + 1).toInt()
            val slice = body.copyOfRange(start.toInt(), start.toInt() + length)
            respond(
                content = ByteReadChannel(slice),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                    append(HttpHeaders.ContentLength, "$length")
                },
            )
        }

        val dest = newScratchPath("happy4.bin")
        val returned = client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = 4, expectedSize = totalSize)

        assertEquals(totalSize, returned, "return value must equal totalSize")
        // File must be byte-identical to source
        val written = SystemFileSystem.source(dest).buffered().use { it.readByteArray() }
        assertEquals(body.toList(), written.toList(), "file contents must be byte-identical")
        // No tmp files left behind
        assertNoPartFiles(dest)
        // Exactly 4 HTTP calls (no probe since expectedSize was supplied)
        assertEquals(4, requestCount, "should make exactly 4 range requests, no probe")
        client.close()
    }

    // --- partCount=1 delegates to downloadFromUrl (no tmp files) ---

    @Test
    fun `partCount=1 delegates to downloadFromUrl with no part files created`() = runBlocking {
        val body = ByteArray(100) { it.toByte() }
        var requestCount = 0

        val client = clientWithAuth { req ->
            requestCount++
            // downloadFromUrl sends no Range header for a fresh dest
            assertEquals(null, req.headers[HttpHeaders.Range], "partCount=1 should not send Range header for fresh dest")
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
            )
        }

        val dest = newScratchPath("delegate1.bin")
        val returned = client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = 1, expectedSize = body.size.toLong())

        assertEquals(body.size.toLong(), returned)
        assertNoPartFiles(dest)
        assertEquals(1, requestCount)
        client.close()
    }

    // --- partCount=0 throws before any HTTP ---

    @Test
    fun `partCount=0 throws PikPakException before any HTTP`() = runBlocking {
        var httpInvoked = false

        val client = clientWithAuth { _ ->
            httpInvoked = true
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.OK)
        }

        val dest = newScratchPath("zero-count.bin")
        assertFailsWith<PikPakException> {
            client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = 0)
        }
        assertFalse(httpInvoked, "HTTP must not be called for partCount=0")
        client.close()
    }

    // --- expectedSize=-1 probe path ---

    @Test
    fun `expectedSize=-1 probe path sends probe then partCount range requests`() = runBlocking {
        val totalSize = 500L
        val body = ByteArray(totalSize.toInt()) { (it % 256).toByte() }
        val requestCount = mutableListOf<String?>() // collect Range headers in order

        val client = clientWithAuth { req ->
            val rangeHeader = req.headers[HttpHeaders.Range]
            requestCount += rangeHeader
            when {
                rangeHeader == "bytes=0-0" -> {
                    // probe: return 1 byte with full Content-Range
                    respond(
                        content = ByteReadChannel(byteArrayOf(body[0])),
                        status = HttpStatusCode.PartialContent,
                        headers = Headers.build {
                            append(HttpHeaders.ContentRange, "bytes 0-0/$totalSize")
                            append(HttpHeaders.ContentLength, "1")
                        },
                    )
                }
                rangeHeader != null -> {
                    val (start, end) = parseRangeHeader(rangeHeader)
                    val length = (end - start + 1).toInt()
                    val slice = body.copyOfRange(start.toInt(), start.toInt() + length)
                    respond(
                        content = ByteReadChannel(slice),
                        status = HttpStatusCode.PartialContent,
                        headers = Headers.build {
                            append(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                            append(HttpHeaders.ContentLength, "$length")
                        },
                    )
                }
                else -> respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.InternalServerError)
            }
        }

        val partCount = 2
        val dest = newScratchPath("probe.bin")
        val returned = client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = partCount, expectedSize = -1L)

        assertEquals(totalSize, returned)
        // 1 probe + partCount part requests
        assertEquals(1 + partCount, requestCount.size, "must make 1 probe + $partCount part requests")
        assertEquals("bytes=0-0", requestCount[0], "first request must be the probe")
        // All subsequent must be real range requests (not probe)
        requestCount.drop(1).forEach { hdr ->
            assertTrue(hdr != null && hdr != "bytes=0-0", "subsequent requests must be real ranges")
        }
        client.close()
    }

    // --- probe returns Content-Range with * total → throws, no part requests ---

    @Test
    fun `probe with star total throws PikPakException without making part requests`() = runBlocking {
        var callCount = 0

        val client = clientWithAuth { req ->
            callCount++
            val rangeHeader = req.headers[HttpHeaders.Range]
            if (rangeHeader == "bytes=0-0") {
                respond(
                    content = ByteReadChannel(byteArrayOf(0)),
                    status = HttpStatusCode.PartialContent,
                    headers = Headers.build {
                        append(HttpHeaders.ContentRange, "bytes 0-0/*")
                        append(HttpHeaders.ContentLength, "1")
                    },
                )
            } else {
                respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.OK)
            }
        }

        val dest = newScratchPath("star-probe.bin")
        assertFailsWith<PikPakException> {
            client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = 3, expectedSize = -1L)
        }
        // Only the probe request, no part requests
        assertEquals(1, callCount, "only the probe request should be made")
        client.close()
    }

    // --- part 2 of 4 fails → exception bubbles, tmp files cleaned up, dest absent ---

    @Test
    fun `part failure cleans up all tmp files and does not create dest`() = runBlocking {
        val totalSize = 1000L
        val body = ByteArray(totalSize.toInt()) { it.toByte() }
        var callCount = 0

        val client = clientWithAuth(
            retryPolicy = RetryPolicy(
                maxAttempts = 2,
                initialDelay = 1.milliseconds,
                maxDelay = 2.milliseconds,
            ),
        ) { req ->
            callCount++
            val rangeHeader = req.headers[HttpHeaders.Range] ?: error("no Range header")
            val (start, _) = parseRangeHeader(rangeHeader)
            val partSize = totalSize / 4
            val isPartTwo = start == partSize * 2
            if (isPartTwo) {
                // Simulate server error that will be retried and then fail
                respond(
                    content = ByteReadChannel(ByteArray(0)),
                    status = HttpStatusCode.InternalServerError,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "text/plain")
                    },
                )
            } else {
                val end = minOf(start + partSize - 1, totalSize - 1)
                val length = (end - start + 1).toInt()
                val slice = body.copyOfRange(start.toInt(), start.toInt() + length)
                respond(
                    content = ByteReadChannel(slice),
                    status = HttpStatusCode.PartialContent,
                    headers = Headers.build {
                        append(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                        append(HttpHeaders.ContentLength, "$length")
                    },
                )
            }
        }

        val dest = newScratchPath("fail-part.bin")
        assertFailsWith<Throwable> {
            client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = 4, expectedSize = totalSize)
        }
        // dest must not exist
        assertFalse(SystemFileSystem.exists(dest), "dest must not exist when parts fail")
        // No .part-N files left
        assertNoPartFiles(dest)
        client.close()
    }

    // --- partCount=3, last part absorbs remainder: ranges match exactly ---

    @Test
    fun `partCount=3 with remainder distributes ranges correctly`() = runBlocking {
        val totalSize = 1001L
        val body = ByteArray(totalSize.toInt()) { it.toByte() }
        val capturedRanges = mutableListOf<Pair<Long, Long>>()

        val client = clientWithAuth { req ->
            val rangeHeader = req.headers[HttpHeaders.Range] ?: error("no Range header")
            val (start, end) = parseRangeHeader(rangeHeader)
            // MockEngine handlers run on a single dispatcher; no cross-thread mutation
            // happens in this test, so a plain mutation is safe and KMP-portable.
            capturedRanges += Pair(start, end)
            val length = (end - start + 1).toInt()
            val slice = body.copyOfRange(start.toInt(), start.toInt() + length)
            respond(
                content = ByteReadChannel(slice),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes $start-$end/$totalSize")
                    append(HttpHeaders.ContentLength, "$length")
                },
            )
        }

        val dest = newScratchPath("remainder.bin")
        client.parallelDownloadFromUrl("https://cdn/file", dest, partCount = 3, expectedSize = totalSize)

        // Sort by start offset since async may send in any order
        val sorted = capturedRanges.sortedBy { it.first }
        assertEquals(3, sorted.size, "should have exactly 3 range requests")

        // partSize = 1001 / 3 = 333
        // part 0: [0, 332]
        // part 1: [333, 665]
        // part 2: [666, 1000]  (absorbs remainder)
        assertEquals(Pair(0L, 332L), sorted[0], "part 0 range")
        assertEquals(Pair(333L, 665L), sorted[1], "part 1 range")
        assertEquals(Pair(666L, 1000L), sorted[2], "part 2 range (last, absorbs remainder)")

        client.close()
    }

    // --- helpers ---

    private fun clientWithAuth(
        retryPolicy: RetryPolicy = RetryPolicy(
            maxAttempts = 3,
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

    private fun newScratchPath(name: String): Path {
        val dir = tmpDir()
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
        val p = Path(dir, "pdl-$name")
        scratchFiles += p
        return p
    }

    private fun tmpDir(): Path = Path("build", "dl-scratch")

    private fun assertNoPartFiles(dest: Path) {
        val dir = dest.parent ?: Path(".")
        if (!SystemFileSystem.exists(dir)) return
        val partFiles = runCatching {
            SystemFileSystem.list(dir).filter { it.name.startsWith(dest.name + ".part-") }
        }.getOrDefault(emptyList())
        assertTrue(partFiles.isEmpty(), "Unexpected tmp files left: ${partFiles.map { it.name }}")
    }

    /** Parses "bytes=START-END" → Pair(start, end). */
    private fun parseRangeHeader(header: String): Pair<Long, Long> {
        val withoutPrefix = header.removePrefix("bytes=")
        val dashIdx = withoutPrefix.indexOf('-')
        val start = withoutPrefix.substring(0, dashIdx).toLong()
        val end = withoutPrefix.substring(dashIdx + 1).toLong()
        return Pair(start, end)
    }
}
