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
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Behaviour the range reader promises that a single request cannot: refreshing
 * an expired signature, waiting out a 503, resuming a body that stopped early,
 * and never exceeding the connection budget.
 */
class RangeReaderMockTest {

    private val content = ByteArray(1024) { (it % 251).toByte() }

    @Test
    fun `expired signature is refreshed and the read completes`() = runBlocking {
        var issued = 0
        val client = clientWith { req ->
            // The first URL is dead; only the second one serves bytes.
            if (req.url.parameters["sig"] == "stale") {
                respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Forbidden)
            } else {
                partial(req, content)
            }
        }
        val reader = RangeReader(
            client = client,
            urlProvider = {
                issued++
                if (issued == 1) "https://cdn/file?sig=stale" else "https://cdn/file?sig=fresh"
            },
        )

        val bytes = reader.readBytes(0, 256)
        assertContentEquals(content.copyOfRange(0, 256), bytes)
        assertEquals(2, issued, "one initial URL plus one refresh")
        assertEquals(1, reader.stats.value.urlRefreshes)
        client.close()
    }

    @Test
    fun `503 is waited out rather than failed`() = runBlocking {
        var attempts = 0
        val client = clientWith { req ->
            attempts++
            if (attempts <= 2) {
                respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.ServiceUnavailable)
            } else {
                partial(req, content)
            }
        }
        val reader = RangeReader(client, { "https://cdn/file" })

        val bytes = reader.readBytes(0, 128)
        assertContentEquals(content.copyOfRange(0, 128), bytes)
        assertTrue(reader.stats.value.throttled >= 1, "503 must be counted as throttling")
        client.close()
    }

    @Test
    fun `a truncated body resumes from the delivered offset`() = runBlocking {
        val ranges = mutableListOf<String>()
        var served = 0
        val client = clientWith { req ->
            ranges += req.headers[HttpHeaders.Range].orEmpty()
            served++
            val (from, to) = parseRange(req.headers[HttpHeaders.Range]!!)
            val full = content.copyOfRange(from.toInt(), (to + 1).toInt())
            // The first response announces its full length and then stops
            // halfway, which is what a dropped connection looks like.
            val body = if (served == 1) full.copyOfRange(0, full.size / 2) else full
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes $from-$to/${content.size}")
                    append(HttpHeaders.ContentLength, full.size.toString())
                },
            )
        }
        val reader = RangeReader(client, { "https://cdn/file" })

        val bytes = reader.readBytes(100, 200)
        assertContentEquals(content.copyOfRange(100, 300), bytes)
        assertEquals(listOf("bytes=100-299", "bytes=200-299"), ranges, "the resume must start where the body stopped")
        client.close()
    }

    @Test
    fun `concurrent reads never exceed the connection budget`() = runBlocking {
        val budget = 3
        val gate = CompletableDeferred<Unit>()
        val counter = Mutex()
        var inFlight = 0
        var peak = 0

        val client = clientWith { req ->
            counter.withLock {
                inFlight++
                if (inFlight > peak) peak = inFlight
            }
            // Hold every connection open until the last permitted one has
            // arrived; without a budget all eight would be here at once.
            if (peak >= budget) gate.complete(Unit)
            gate.await()
            counter.withLock { inFlight-- }
            partial(req, content)
        }
        val reader = RangeReader(client, { "https://cdn/file" }, connectionBudget = budget)

        val reads = (0 until 8).map { i -> async { reader.readBytes(i * 64L, 64) } }
        reads.awaitAll()
        assertEquals(budget, peak, "the gate must cap in-flight requests at the budget")
        client.close()
    }

    @Test
    fun `a provider that cannot refresh fails instead of looping`() = runBlocking {
        val client = clientWith { respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Forbidden) }
        val reader = RangeReader(client, { "https://cdn/file?sig=stale" })

        val e = assertFailsWith<PikPakException> { reader.readBytes(0, 16) }
        assertTrue(
            e.message!!.contains("same rejected URL"),
            "a provider returning the dead URL must be reported, not retried forever: ${e.message}",
        )
        client.close()
    }

    @Test
    fun `a range past the end of the file stops at EOF instead of asking for more`() = runBlocking {
        val ranges = mutableListOf<String>()
        val client = clientWith { req ->
            ranges += req.headers[HttpHeaders.Range].orEmpty()
            clippedPartial(req, content)
        }
        val reader = RangeReader(client, { "https://cdn/file" })

        var got = 0
        reader.read(content.size - 100L, 300) { channel ->
            val buf = ByteArray(1024)
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n == -1) break
                got += n
            }
        }
        assertEquals(100, got, "only the bytes that exist are delivered")
        assertEquals(1, ranges.size, "the clipped response must not be followed by a request past EOF: $ranges")
        client.close()
    }

    @Test
    fun `a cancelled read gives its connection slot back`() = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val client = clientWith { req -> partial(req, content) }
        val reader = RangeReader(client, { "https://cdn/file" }, connectionBudget = 1)

        val stalled = async {
            reader.read(0, 64) {
                holding.complete(Unit)
                awaitCancellation()
            }
        }
        holding.await()
        stalled.cancel()
        stalled.join()

        // With the only slot leaked this would hang, so bound the wait.
        val bytes = withTimeout(2_000) { reader.readBytes(0, 64) }
        assertContentEquals(content.copyOfRange(0, 64), bytes)
        assertEquals(0, reader.stats.value.activeReads)
        client.close()
    }

    /**
     * One read, three requests, three reports — and the bytes counted once.
     *
     * The observer is called from six places in the pump, one per way an
     * attempt can end, and a path that forgets to call it is invisible until
     * the capture it was added for comes back missing exactly the attempts
     * worth looking at. Asserting the sum as well as the count is what pins
     * `delivered` as per-attempt: a cumulative counter would pass on the count
     * and report the resumed bytes twice.
     *
     * Single read, single pump coroutine, so the list needs no lock.
     */
    @Test
    fun `every way an attempt can end is reported once`() = runBlocking {
        // 503 is deliberately not among these: HttpEngine retries 5xx before
        // the body ever reaches the pump, so a throttled request is not an
        // attempt this observer can see — it lands inside one.
        var served = 0
        val client = clientWith { req ->
            served++
            when (served) {
                1 -> respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.Forbidden)
                2 -> {
                    // Announces its full length and stops halfway: a dropped
                    // connection, which resumes rather than restarts.
                    val (from, to) = parseRange(req.headers[HttpHeaders.Range]!!)
                    val full = content.copyOfRange(from.toInt(), (to + 1).toInt())
                    respond(
                        content = ByteReadChannel(full.copyOfRange(0, full.size / 2)),
                        status = HttpStatusCode.PartialContent,
                        headers = Headers.build {
                            append(HttpHeaders.ContentRange, "bytes $from-$to/${content.size}")
                            append(HttpHeaders.ContentLength, full.size.toString())
                        },
                    )
                }
                else -> partial(req, content)
            }
        }
        val seen = mutableListOf<RangeAttempt>()
        var issued = 0
        val reader = RangeReader(
            client,
            { "https://cdn/file?sig=${issued++}" },
            onAttempt = { seen += it },
        )

        val bytes = reader.readBytes(0, 200)

        assertContentEquals(content.copyOfRange(0, 200), bytes)
        assertEquals(
            listOf(RangeAttempt.Outcome.Expired, RangeAttempt.Outcome.Failed, RangeAttempt.Outcome.Complete),
            seen.map { it.outcome },
        )
        assertEquals(listOf(0L, 100L), seen.take(2).map { it.delivered })
        assertEquals(200L, seen.sumOf { it.delivered }, "the resumed bytes must be counted once, not twice")
        assertEquals(listOf(0L, 0L, 100L), seen.map { it.start }, "each attempt starts where the last one stopped")
        assertEquals(1, seen.count { it.timeToFirstByte == null }, "only the expired signature delivered nothing")
        assertTrue(seen.all { it.timeline.sum() == it.delivered }, "the timeline must account for every byte")
        client.close()
    }

    /** An observer that throws explains nothing, and must not be able to end the read either. */
    @Test
    fun `a failing observer does not break the read`() = runBlocking {
        val client = clientWith { req -> partial(req, content) }
        val reader = RangeReader(client, { "https://cdn/file" }, onAttempt = { error("observer is broken") })

        assertContentEquals(content.copyOfRange(0, 64), reader.readBytes(0, 64))
        client.close()
    }

    // --- helpers ---

    /** Serves like a real CDN: clips the range at EOF and answers 416 past it. */
    private fun MockRequestHandleScope.clippedPartial(req: HttpRequestData, body: ByteArray): HttpResponseData {
        val (from, to) = parseRange(req.headers[HttpHeaders.Range]!!)
        if (from >= body.size) {
            return respond(ByteReadChannel(ByteArray(0)), HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val end = minOf(to, body.size - 1L)
        val slice = body.copyOfRange(from.toInt(), end.toInt() + 1)
        return respond(
            content = ByteReadChannel(slice),
            status = HttpStatusCode.PartialContent,
            headers = Headers.build {
                append(HttpHeaders.ContentRange, "bytes $from-$end/${body.size}")
                append(HttpHeaders.ContentLength, slice.size.toString())
            },
        )
    }

    private fun MockRequestHandleScope.partial(req: HttpRequestData, body: ByteArray): HttpResponseData {
        val (from, to) = parseRange(req.headers[HttpHeaders.Range]!!)
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

    private fun parseRange(header: String): Pair<Long, Long> {
        val spec = header.removePrefix("bytes=")
        val from = spec.substringBefore('-').toLong()
        val to = spec.substringAfter('-').toLongOrNull() ?: (content.size - 1L)
        return from to to
    }

    private fun clientWith(
        cdnHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
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
}
