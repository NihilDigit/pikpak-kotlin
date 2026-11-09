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
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.test.runCurrent
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun preemptionDuringConsumerBackpressurePreservesEveryAcceptedByte() = runTest {
        val content = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val range = request.headers[HttpHeaders.Range]!!.substringAfter("bytes=").split('-')
                val start = range[0].toInt()
                val end = range[1].toInt()
                respond(ByteReadChannel(content.copyOfRange(start, end + 1)), HttpStatusCode.PartialContent,
                    Headers.build {
                        append(HttpHeaders.ContentRange, "bytes $start-$end/${content.size}")
                        append(HttpHeaders.ContentLength, "${end - start + 1}")
                    })
            }
        }))
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http,
            sessionStore = InMemorySessionStore(), accountConnectionBudget = 1, timeSource = testScheduler.timeSource)
        val attempts = mutableListOf<RangeAttempt>()
        val background = RangeReader(client, { "https://cdn/background" }, onAttempt = { attempts.add(it) })
        val foreground = RangeReader(client, { "https://cdn/foreground" })
        try {
            val read = async {
                background.read(0, content.size.toLong(), 1) { channel ->
                    delay(100)
                    val result = ByteArray(content.size)
                    var offset = 0
                    while (offset < result.size) {
                        val count = channel.readAvailable(result, offset, result.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    result
                }
            }
            runCurrent()
            kotlin.test.assertContentEquals(content.copyOfRange(0, 64), foreground.readBytes(0, 64, 100))
            kotlin.test.assertContentEquals(content, read.await())
            assertTrue(attempts.any { it.endReason == RangeAttempt.EndReason.Preempted })
        } finally { background.close(); foreground.close(); client.close(); http.close() }
    }

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
    fun `near complete body on a historically fast host resumes at the ordinary deadline`() = runTest {
        val content = ByteArray(32768) { it.toByte() }
        val starts = mutableListOf<Int>()
        val writers = mutableListOf<kotlinx.coroutines.Job>()
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val start = request.headers[HttpHeaders.Range]!!.substringAfter("bytes=").substringBefore('-').toInt()
                starts.add(start)
                val body = if (starts.size == 1) ByteChannel(autoFlush = true).also { channel ->
                    writers.add(backgroundScope.launch {
                        channel.writeFully(content, 0, 16384)
                        kotlinx.coroutines.awaitCancellation()
                    })
                } else ByteReadChannel(content.copyOfRange(start, content.size))
                respond(body, HttpStatusCode.PartialContent, Headers.build {
                    append(HttpHeaders.ContentRange, "bytes $start-32767/32768")
                    append(HttpHeaders.ContentLength, "${32768 - start}")
                })
            }
        }))
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = http, cdnHttpClient = http, timeSource = testScheduler.timeSource)
        client.hostHealth.delivered("cdn", 1024 * 1024L, 10_000_000, "https://cdn/file")
        assertTrue(client.hostHealth.hasGoodHistory("https://cdn/file"))
        try {
            val reader = RangeReader(client, { "https://cdn/file" })
            kotlin.test.assertContentEquals(content, reader.readBytes(0, 32768, PikPakStreamReader.BLOCKING_PRIORITY))
            assertEquals(listOf(0, 16384), starts)
            assertTrue(testScheduler.currentTime in 2000L..2100L)
        } finally { writers.forEach { it.cancel() }; client.close(); http.close() }
    }

    @Test
    fun `tail completion never extends a silence deadline`() {
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 0, 10))
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 10, null))
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 1, 65537))
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 1, 65538))
        assertEquals(3.seconds, RangeReader.tailSilenceDeadline(3.seconds, 800000, 1000000))
        assertEquals(8.seconds, RangeReader.tailSilenceDeadline(8.seconds, 800000, 1000000))
    }
}
