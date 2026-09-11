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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole point of [directDownloadFromUrl] is that the destination file is
 * always a valid prefix of the remote one and its length is the progress.
 * Every test here is about some way that could stop being true: a resumed run
 * refetching bytes it already has, a round landing out of order, a cancelled
 * run leaving a half-written block behind, or progress running ahead of the
 * bytes actually on disk.
 */
class DirectDownloadMockTest {

    private val scratchFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        scratchFiles.forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
    }

    private val block = 4096L

    private fun payload(size: Int) = ByteArray(size) { (it * 17 % 251).toByte() }

    @Test
    fun `the whole file arrives byte-identical over several rounds`() = runBlocking {
        val body = payload((block * 5 + 123).toInt())
        val client = clientWithAuth(cdnHandler = rangeServer(body))
        val dest = newScratchPath("whole.bin")
        val progress = MutableStateFlow(0L)

        val returned = client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            concurrency = 3,
            blockSize = block,
            progress = progress,
        )

        assertEquals(body.size.toLong(), returned)
        assertContentEquals(body, readFile(dest))
        assertEquals(body.size.toLong(), progress.value, "progress must end at the file's size")
        client.close()
    }

    @Test
    fun `rounds tile the file with no gap and no overlap`() = runBlocking {
        val body = payload((block * 7 + 1).toInt())
        val ranges = mutableListOf<Pair<Long, Long>>()
        val client = clientWithAuth(cdnHandler = rangeServer(body) { start, end -> ranges += start to end })
        val dest = newScratchPath("tiling.bin")

        client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            concurrency = 3,
            blockSize = block,
        )

        // Requests come back in any order within a round; the invariant is about
        // the set of ranges, not the sequence they were issued in.
        val sorted = ranges.sortedBy { it.first }
        var expected = 0L
        for ((start, end) in sorted) {
            assertEquals(expected, start, "ranges must be contiguous: $sorted")
            expected = end + 1
        }
        assertEquals(body.size.toLong(), expected, "ranges must cover the file exactly")
        assertContentEquals(body, readFile(dest))
        client.close()
    }

    @Test
    fun `a partial file is continued rather than refetched`() = runBlocking {
        val body = payload((block * 4).toInt())
        val already = (block * 2).toInt()
        val dest = newScratchPath("resume.bin")
        writeFile(dest, body.copyOfRange(0, already))

        val starts = mutableListOf<Long>()
        val client = clientWithAuth(cdnHandler = rangeServer(body) { start, _ -> starts += start })
        val progress = MutableStateFlow(-1L)

        client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            concurrency = 2,
            blockSize = block,
            progress = progress,
        )

        assertContentEquals(body, readFile(dest))
        // Without this the all{} below passes on an empty list, which is what a
        // resume that fetched nothing at all would look like.
        assertEquals(2, starts.size, "the remaining two blocks should have been fetched: $starts")
        assertTrue(
            starts.all { it >= already },
            "bytes already on disk were fetched again: $starts",
        )
        assertEquals(body.size.toLong(), progress.value)
        client.close()
    }

    @Test
    fun `a cancelled run leaves a whole number of blocks and the next call finishes the file`() = runBlocking {
        val body = payload((block * 6).toInt())
        // One block per round, so the cancellation lands between two blocks
        // rather than in the middle of a round's writes.
        val client = clientWithAuth(cdnHandler = rangeServer(body, delayPerRequest = 20.milliseconds))
        val dest = newScratchPath("cancel.bin")
        val progress = MutableStateFlow(0L)

        val job = launch {
            client.downloadFromUrl(
                url = "https://cdn/file",
                dest = dest,
                totalSize =body.size.toLong(),
                concurrency = 1,
                blockSize = block,
                progress = progress,
            )
        }
        withTimeout(30.seconds) { progress.first { it >= block * 2 } }
        job.cancelAndJoin()

        val partial = readFile(dest)
        assertTrue(partial.size < body.size, "the run was cancelled before the file was complete")
        assertEquals(
            0L,
            partial.size.toLong() % block,
            "a cancelled run must not leave half a block: got ${partial.size}",
        )
        assertEquals(
            progress.value,
            partial.size.toLong(),
            "progress must not claim bytes the file does not have",
        )
        assertContentEquals(
            body.copyOfRange(0, partial.size),
            partial,
            "what is on disk must be a prefix of the remote file",
        )

        client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            concurrency = 2,
            blockSize = block,
            progress = progress,
        )
        assertContentEquals(body, readFile(dest))
        client.close()
    }

    @Test
    fun `a transient failure mid-file does not disturb the order of what is written`() = runBlocking {
        val body = payload((block * 4).toInt())
        val failAt = block * 2
        var failuresLeft = 1
        val client = clientWithAuth { req ->
            val (start, end) = parseRangeHeader(req.headers[HttpHeaders.Range] ?: error("no Range header"))
            if (start == failAt && failuresLeft > 0) {
                failuresLeft--
                respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.InternalServerError)
            } else {
                respondSlice(body, start, end)
            }
        }
        val dest = newScratchPath("transient.bin")

        client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            concurrency = 2,
            blockSize = block,
        )

        assertEquals(0, failuresLeft, "the test never exercised the failure it set up")
        assertContentEquals(body, readFile(dest))
        client.close()
    }

    @Test
    fun `a local file longer than the remote one is discarded rather than resumed`() = runBlocking {
        val body = payload((block * 2).toInt())
        val dest = newScratchPath("oversize.bin")
        writeFile(dest, ByteArray(body.size + 500) { 0x5A })

        val starts = mutableListOf<Long>()
        val client = clientWithAuth(cdnHandler = rangeServer(body) { start, _ -> starts += start })

        client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            concurrency = 1,
            blockSize = block,
        )

        assertContentEquals(body, readFile(dest))
        assertTrue(starts.contains(0L), "an oversize file must be refetched from zero: $starts")
        client.close()
    }

    @Test
    fun `concurrency below one is rejected before any HTTP`() = runBlocking {
        var httpInvoked = false
        val client = clientWithAuth { _ ->
            httpInvoked = true
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.OK)
        }
        val dest = newScratchPath("bad-concurrency.bin")

        assertFailsWith<PikPakException> {
            client.downloadFromUrl("https://cdn/file", dest, totalSize = 10L, concurrency = 0)
        }
        assertFalse(httpInvoked, "HTTP must not be called for concurrency = 0")
        client.close()
    }

    @Test
    fun `an already complete file short-circuits before HTTP`() = runBlocking {
        val body = payload(block.toInt())
        val dest = newScratchPath("complete.bin")
        writeFile(dest, body)
        var calls = 0
        val client = clientWithAuth { _ ->
            calls++
            respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.OK)
        }

        val returned = client.downloadFromUrl(
            url = "https://cdn/file",
            dest = dest,
            totalSize =body.size.toLong(),
            blockSize = block,
        )

        assertEquals(body.size.toLong(), returned)
        assertEquals(0, calls, "a complete file must not be fetched again")
        client.close()
    }

    // --- helpers ---

    /** A CDN that answers any closed range out of [body], optionally slowly. */
    private fun rangeServer(
        body: ByteArray,
        delayPerRequest: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        onRange: (Long, Long) -> Unit = { _, _ -> },
    ): suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData =
        { req ->
            val (start, end) = parseRangeHeader(req.headers[HttpHeaders.Range] ?: error("no Range header"))
            onRange(start, end)
            if (delayPerRequest > kotlin.time.Duration.ZERO) delay(delayPerRequest)
            respondSlice(body, start, end)
        }

    private fun MockRequestHandleScope.respondSlice(body: ByteArray, start: Long, end: Long) = respond(
        content = ByteReadChannel(body.copyOfRange(start.toInt(), (end + 1).toInt())),
        status = HttpStatusCode.PartialContent,
        headers = Headers.build {
            append(HttpHeaders.ContentRange, "bytes $start-$end/${body.size}")
            append(HttpHeaders.ContentLength, "${end - start + 1}")
        },
    )

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
        val p = Path(dir, "ddl-$name")
        SystemFileSystem.delete(p, mustExist = false)
        scratchFiles += p
        return p
    }

    private fun tmpDir(): Path = Path("build", "dl-scratch")

    private fun writeFile(path: Path, bytes: ByteArray) {
        SystemFileSystem.sink(path).buffered().use { it.write(bytes, 0, bytes.size) }
    }

    private fun readFile(path: Path): ByteArray =
        SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    /**
     * A source shorter than the caller's `totalSize` must stop the download,
     * not spin.
     *
     * [RangeSource.readBytes] answers a range past the end with a short array
     * rather than an error, which is right for a range read and wrong here: a
     * short block appended as if it were whole puts every later byte at the
     * wrong offset, and a run of empty blocks at the end never advances
     * `written`, so the loop never terminates.
     */
    @Test
    fun `a source shorter than the declared size fails instead of looping`() = runBlocking<Unit> {
        val dest = newScratchPath("short-source.bin")
        val source = FakeRangeSource(ByteArray(100) { it.toByte() })

        val e = assertFailsWith<PikPakException> {
            withTimeout(10.seconds) {
                source.downloadTo(
                    dest = dest,
                    totalSize = 4096,
                    concurrency = 2,
                    blockSize = 64,
                    maxRoundFailures = 1,
                )
            }
        }
        assertTrue(e.message.orEmpty().contains("short read"), "message should name the cause: ${e.message}")
    }

    /**
     * A slow block must not hold the other connections idle behind it.
     *
     * This is the difference between the window and the batched rounds it
     * replaced. With rounds, nothing past the first `concurrency` blocks was
     * requested until every one of them had landed, so each round cost its
     * slowest block and the fan-out degraded from N times to N over the
     * latency variance — worst on exactly the weak links the fan-out is for.
     *
     * The slow block is placed last in the first window: under rounds that
     * blocks the whole batch, while the window writes the blocks ahead of it
     * and issues their replacements meanwhile.
     */
    @Test
    fun `a slow block does not stop the window from issuing further requests`() = runBlocking<Unit> {
        val dest = newScratchPath("window.bin")
        val blockSize = 64L
        val concurrency = 4
        val slowStart = blockSize * (concurrency - 1)
        val log = mutableListOf<String>()
        val logMutex = Mutex()

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = block(ByteReadChannel(ByteArray(length.toInt())))

            override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray {
                logMutex.withLock { log += "issued:$start" }
                if (start == slowStart) delay(300.milliseconds)
                logMutex.withLock { log += "landed:$start" }
                return ByteArray(length.toInt())
            }
        }

        source.downloadTo(
            dest = dest,
            totalSize = blockSize * 12,
            concurrency = concurrency,
            blockSize = blockSize,
        )

        val slowLanded = log.indexOf("landed:$slowStart")
        val nextIssued = log.indexOf("issued:${blockSize * concurrency}")
        assertTrue(slowLanded >= 0 && nextIssued >= 0, "expected both events in $log")
        assertTrue(
            nextIssued < slowLanded,
            "the block past the first window waited for the slow one: issued at $nextIssued, slow landed at $slowLanded",
        )
    }

    /** Parses "bytes=START-END" to Pair(start, end). */
    private fun parseRangeHeader(header: String): Pair<Long, Long> {
        val withoutPrefix = header.removePrefix("bytes=")
        val dashIdx = withoutPrefix.indexOf('-')
        val start = withoutPrefix.substring(0, dashIdx).toLong()
        val end = withoutPrefix.substring(dashIdx + 1).toLong()
        return Pair(start, end)
    }
}
