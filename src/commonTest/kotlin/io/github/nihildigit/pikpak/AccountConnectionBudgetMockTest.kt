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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The account-wide half of the connection limit.
 *
 * A per-file budget alone does not bound a client: eight readers each keeping
 * to their own eight would open sixty-four, and PikPak refuses well before
 * that. These cover what the second gate adds — a ceiling across files, and
 * priority that carries across them too, so a background download cannot get
 * in front of playback just by being on a different URL.
 */
class AccountConnectionBudgetMockTest {
    private val content = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `several files together stay inside the account budget`() = runBlocking {
        val tracker = InFlightTracker()
        val client = clientWith(accountBudget = 6) { req -> tracker.serve(this, req, content) }

        // Four files, four connections each: sixteen requests, every one of them
        // allowed by its own file's budget of 8, and none of them by the account's 6.
        val readers = List(4) { RangeReader(client, { "https://cdn/file$it" }, connectionBudget = 8) }
        val reads = readers.flatMap { reader ->
            (0 until 4).map { i -> async { reader.readBytes(i * 64L, 64) } }
        }
        reads.awaitAll()

        assertEquals(6, tracker.peak, "the account gate must cap in-flight requests across files")
        client.close()
    }

    @Test
    fun `one file cannot take the whole account budget`() = runBlocking {
        val tracker = InFlightTracker()
        val client = clientWith(accountBudget = 16) { req -> tracker.serve(this, req, content) }

        // A single reader asking for far more than it may hold. Its own budget is
        // the binding one; the account's larger ceiling must stay free for others.
        val reader = RangeReader(client, { "https://cdn/hog" }, connectionBudget = 4)
        (0 until 20).map { i -> async { reader.readBytes(i * 64L, 64) } }.awaitAll()

        assertEquals(4, tracker.peak, "a reader may never exceed its own budget, account room notwithstanding")
        client.close()
    }

    @Test
    fun `a higher priority read on another file is served first`() = runBlocking {
        val released = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val orderLock = Mutex()

        // One account slot, so everything after the first request has to queue and
        // the order the gate hands slots out in is the order requests are seen in.
        val client = clientWith(accountBudget = 1) { req ->
            val tag = req.url.encodedPath
            orderLock.withLock { order += tag }
            if (tag.endsWith("/blocker")) released.await()
            respondPartial(req, content)
        }

        val blocker = RangeReader(client, { "https://cdn/blocker" }, connectionBudget = 8)
        val background = RangeReader(client, { "https://cdn/background" }, connectionBudget = 8)
        val playback = RangeReader(client, { "https://cdn/playback" }, connectionBudget = 8)

        val holding = async { blocker.readBytes(0, 64) }
        // Wait until the blocker owns the only slot, otherwise the two queued reads
        // race it and the queue order proves nothing.
        while (orderLock.withLock { order.isEmpty() }) delay(1)

        val low = async { background.readBytes(0, 64, priority = 0) }
        val high = async { playback.readBytes(0, 64, priority = 10) }
        // Both must be queued before the slot frees, or whichever got there first wins
        // on availability rather than on priority.
        delay(50)

        released.complete(Unit)
        awaitAll(holding, low, high)

        val queuedOrder = order.drop(1)
        assertEquals(
            listOf("/playback", "/background"),
            queuedOrder,
            "priority must order the queue across files, not just within one",
        )
        client.close()
    }

    @Test
    fun `a cancelled read gives its account slot back`() = runBlocking {
        val tracker = InFlightTracker()
        val client = clientWith(accountBudget = 1) { req -> tracker.serve(this, req, content) }

        val first = RangeReader(client, { "https://cdn/a" }, connectionBudget = 8)
        val second = RangeReader(client, { "https://cdn/b" }, connectionBudget = 8)

        val cancelled = async { first.readBytes(0, 64) }
        cancelled.cancel()
        runCatching { cancelled.await() }

        // With the account slot leaked this would never return; the per-file gate of
        // the second reader is untouched and would not catch the leak.
        val bytes = second.readBytes(0, 64)
        assertTrue(bytes.isNotEmpty(), "the account slot must be returned when a read is cancelled")
        client.close()
    }

    /** Counts concurrent handler invocations so a test can assert the peak. */
    private class InFlightTracker {
        private val lock = Mutex()
        private var current = 0
        var peak = 0
            private set

        suspend fun serve(
            scope: MockRequestHandleScope,
            req: HttpRequestData,
            body: ByteArray,
        ): HttpResponseData {
            lock.withLock {
                current++
                if (current > peak) peak = current
            }
            // Hold the slot long enough for the other requests to pile up behind it;
            // without this the reads would serialise on their own and the peak would
            // say nothing about the gate.
            delay(20)
            lock.withLock { current-- }
            return respondPartialIn(scope, req, body)
        }
    }

    private fun clientWith(
        accountBudget: Int,
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
            accountConnectionBudget = accountBudget,
        )
        runBlocking { client.login() }
        return client
    }
}

private fun respondPartialIn(
    scope: MockRequestHandleScope,
    req: HttpRequestData,
    body: ByteArray,
): HttpResponseData {
    val spec = req.headers[HttpHeaders.Range]!!.removePrefix("bytes=")
    val from = spec.substringBefore('-').toLong()
    val to = spec.substringAfter('-').toLongOrNull() ?: (body.size - 1L)
    val slice = body.copyOfRange(from.toInt(), (to + 1).toInt().coerceAtMost(body.size))
    return scope.respond(
        content = ByteReadChannel(slice),
        status = HttpStatusCode.PartialContent,
        headers = Headers.build {
            append(HttpHeaders.ContentRange, "bytes $from-$to/${body.size}")
            append(HttpHeaders.ContentLength, slice.size.toString())
        },
    )
}

private fun MockRequestHandleScope.respondPartial(req: HttpRequestData, body: ByteArray): HttpResponseData =
    respondPartialIn(this, req, body)
