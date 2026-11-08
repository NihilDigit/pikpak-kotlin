package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.parseRetryAfter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RangeThrottleTest {
    @Test
    fun `429 and 503 honor retry after and release reader admission during backoff`() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            val starts = mutableListOf<Pair<String, Long>>()
            var issued = 0
            val http = HttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    starts += request.url.encodedPath to testScheduler.currentTime
                    if (starts.size == 1) respond("", status, headersOf(HttpHeaders.RetryAfter, "1"))
                    else respond(ByteReadChannel(byteArrayOf(1)), HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentRange to listOf("bytes 0-0/1"), HttpHeaders.ContentLength to listOf("1")))
                }
            }))
            val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http,
                sessionStore = InMemorySessionStore(), accountConnectionBudget = 1, timeSource = testScheduler.timeSource)
            val reader = RangeReader(client, { issued++; "https://cdn/file" }, connectionBudget = 1)
            val other = RangeReader(client, { "https://cdn/other" })
            try {
                val start = testScheduler.currentTime
                val read = async { reader.readBytes(0, 1) }
                runCurrent()
                assertEquals(0, client.accountGate.inUse)
                advanceTimeBy(100)
                runCurrent()
                val sameFile = async { reader.readBytes(0, 1, priority = 100) }
                val otherFile = async { other.readBytes(0, 1, priority = 100) }
                runCurrent()
                assertTrue(sameFile.isCompleted && otherFile.isCompleted)
                assertFalse(read.isCompleted)
                advanceTimeBy(899)
                runCurrent()
                assertEquals(3, starts.size)
                advanceTimeBy(1)
                runCurrent()
                read.await(); sameFile.await(); otherFile.await()
                assertEquals(start + 1000, starts.last().second)
                assertEquals(1, issued)
                assertEquals(1, reader.stats.value.throttled)
                assertEquals(0, client.accountGate.inUse)
            } finally { reader.close(); other.close(); client.close(); http.close() }
        }
    }

    @Test
    fun `direct range calls retry pressure but never replay the consumer block`() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                if (calls <= 2) respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                else respond(ByteReadChannel(byteArrayOf(1)), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange to listOf("bytes 0-0/1"), HttpHeaders.ContentLength to listOf("1")))
            }
        }))
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http, sessionStore = InMemorySessionStore())
        try {
            var entered = 0
            val start = testScheduler.currentTime
            client.streamRangeFromUrl("https://cdn/file", 0, 1) { entered++ }
            assertEquals(2000, testScheduler.currentTime - start)
            assertEquals(3, calls)
            assertEquals(1, entered)
            assertFailsWith<PikPakException> {
                client.streamRangeFromUrl("https://cdn/file", 0, 1) { throw PikPakException(-1, "consumer", httpStatus = 429) }
            }
            assertEquals(4, calls)
            advanceTimeBy(100)
            runCurrent()
        } finally { client.close(); http.close() }
    }

    @Test
    fun `cancellation during throttle backoff stops retries and frees admission`() = runTest {
        var calls = 0
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { calls++; respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "5")) }
        }))
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http, sessionStore = InMemorySessionStore())
        val reader = RangeReader(client, { "https://cdn/file" })
        try {
            val read = async { reader.readBytes(0, 1) }
            runCurrent()
            read.cancelAndJoin()
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(0, client.accountGate.inUse)
            val holders = List(8) { async { client.signedUrlGates.withSlot("https://cdn/file") {} } }
            holders.forEach { it.await() }
        } finally { reader.close(); client.close(); http.close() }
    }

    @Test
    fun `retry after accepts delta seconds and HTTP dates with bounded delays`() {
        val now = Instant.parse("2026-11-07T00:00:00Z").toEpochMilliseconds()
        assertEquals(2.seconds, parseRetryAfter("2", now))
        assertEquals(2.seconds, parseRetryAfter("Sat, 07 Nov 2026 00:00:02 GMT", now))
        assertEquals(30.seconds, parseRetryAfter("999", now))
        assertEquals(0.seconds, parseRetryAfter("Fri, 06 Nov 2026 00:00:00 GMT", now))
        assertNull(parseRetryAfter("-1", now))
        assertNull(parseRetryAfter("invalid", now))
    }
}
