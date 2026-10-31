package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RangeReaderRobustnessTest {
    @Test
    fun `initial failure is shared for two seconds and then retried`() = runTest {
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(MockEngine { error("no network expected") }), timeSource = testScheduler.timeSource)
        val failure = IllegalStateException("mint failed")
        var calls = 0
        val reader = RangeReader(client, {
            calls++
            delay(100)
            throw failure
        })
        try {
            List(8) { async { assertFailsWith<IllegalStateException> { reader.prewarm() } } }
                .awaitAll().forEach { assertSame(failure, it) }
            assertEquals(1, calls)
            delay(1999)
            assertSame(failure, assertFailsWith<IllegalStateException> { reader.prewarm() })
            assertEquals(1, calls)
            delay(1)
            assertFailsWith<IllegalStateException> { reader.prewarm() }
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test
    fun `initial cancellation is never cached`() = runTest {
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(MockEngine { error("no network expected") }), timeSource = testScheduler.timeSource)
        var calls = 0
        val reader = RangeReader(client, {
            if (++calls == 1) throw CancellationException("cancel mint")
            "https://cdn/file"
        })
        try {
            assertFailsWith<CancellationException> { reader.prewarm() }
            assertEquals("https://cdn/file", reader.prewarm())
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test
    fun `closed consumer channel fails the pump without retrying or dropping bytes`() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            requests++
            respond(ByteReadChannel(ByteArray(131072)), HttpStatusCode.PartialContent, Headers.build {
                append(HttpHeaders.ContentRange, "bytes 0-131071/131072")
                append(HttpHeaders.ContentLength, "131072")
            })
            }
        }))
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = http, cdnHttpClient = http, timeSource = testScheduler.timeSource)
        val reader = RangeReader(client, { "https://cdn/file" })
        val failure = IllegalStateException("consumer failed")
        try {
            val propagated = assertFailsWith<Exception> {
                reader.read(0, 131072L) { channel ->
                    channel.cancel(failure)
                    delay(1000)
                }
            }
            // Ktor may wrap the close cause, but the original failure must remain observable.
            assertTrue(generateSequence<Throwable>(propagated) { it.cause }.any { it === failure })
            assertEquals(1, requests)
            assertEquals(0, reader.stats.value.retries)
        } finally { client.close() }
    }

    @Test
    fun `consumer cancellation cannot turn a failed pump into a successful read`() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                requests++
                respond(ByteReadChannel(ByteArray(131072)), HttpStatusCode.PartialContent, Headers.build {
                    append(HttpHeaders.ContentRange, "bytes 0-131071/131072")
                    append(HttpHeaders.ContentLength, "131072")
                })
            }
        }))
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = http, cdnHttpClient = http, timeSource = testScheduler.timeSource)
        val reader = RangeReader(client, { "https://cdn/file" })
        val failure = CancellationException("consumer cancelled its channel")
        try {
            val propagated = assertFailsWith<Exception> {
                reader.read(0, 131072L) { channel ->
                    // Close before the pump can finish; cancelling an already cleanly closed
                    // channel is a no-op and cannot exercise sink failure propagation.
                    channel.cancel(failure)
                    delay(100)
                    Unit
                }
            }
            assertTrue(generateSequence<Throwable>(propagated) { it.cause }.any { it === failure })
            assertEquals(1, requests)
            assertEquals(0, reader.stats.value.retries)
        } finally { client.close() }
    }

    @Test
    fun `a nearly complete body survives silence beyond its ordinary deadline`() = runTest {
        var requests = 0
        // Blocking reads normally allow 2s; the tail has 4s, so stay strictly inside it.
        val gap = 3.seconds
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                requests++
                val body = ByteChannel(autoFlush = true)
                this@runTest.launch {
                    body.writeFully(ByteArray(16384))
                    delay(gap)
                    body.writeFully(ByteArray(16384))
                    body.flushAndClose()
                }
                respond(body, HttpStatusCode.PartialContent, Headers.build {
                    append(HttpHeaders.ContentRange, "bytes 0-32767/32768")
                    append(HttpHeaders.ContentLength, "32768")
                })
            }
        }))
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = http, cdnHttpClient = http, timeSource = testScheduler.timeSource)
        try {
            val reader = RangeReader(client, { "https://cdn/file" })
            assertEquals(32768, reader.readBytes(0, 32768, PikPakStreamReader.BLOCKING_PRIORITY).size)
            assertEquals(1, requests)
            assertEquals(0, reader.stats.value.retries)
        } finally { client.close() }
    }

    @Test
    fun `tail grace requires progress and is capped`() {
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 0, 10))
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 10, null))
        assertEquals(6.seconds, RangeReader.tailSilenceDeadline(3.seconds, 1, 65537))
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 1, 65538))
        assertEquals(6.seconds, RangeReader.tailSilenceDeadline(3.seconds, 800000, 1000000))
        assertEquals(10.seconds, RangeReader.tailSilenceDeadline(8.seconds, 800000, 1000000))
    }
}
