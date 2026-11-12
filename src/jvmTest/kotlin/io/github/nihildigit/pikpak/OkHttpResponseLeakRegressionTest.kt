package io.github.nihildigit.pikpak

import com.sun.net.httpserver.HttpServer
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.defaultCdnHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.utils.io.readAvailable
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class OkHttpResponseLeakRegressionTest {

    private class RaceExchange(
        val headersSent: CompletableDeferred<Unit> = CompletableDeferred(),
        val holdBody: CountDownLatch = CountDownLatch(1),
    )

    @Test
    fun `cancelling at header arrival or mid-stream or failing Content-Range validation never leaks OkHttp ResponseBody and preserves connection reuse`() =
        runBlocking {
            val okHttpLogger = Logger.getLogger(OkHttpClient::class.java.name)
            val previousLevel = okHttpLogger.level
            val leakedMessages = Collections.synchronizedList(mutableListOf<String>())
            val handler = object : Handler() {
                override fun publish(record: LogRecord?) {
                    val msg = record?.message ?: return
                    if ("leaked" in msg.lowercase() || "response body" in msg.lowercase()) {
                        leakedMessages += msg
                    }
                }

                override fun flush() = Unit
                override fun close() = Unit
            }
            handler.level = Level.ALL
            okHttpLogger.level = Level.ALL
            okHttpLogger.addHandler(handler)

            val executor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 32)
            val releaseHeldBodies = CountDownLatch(1)
            val currentRace = AtomicReference(RaceExchange())

            server.executor = executor
            server.createContext("/bad-range") { exchange ->
                try {
                    val body = ByteArray(4096) { 1 }
                    exchange.responseHeaders.add("Content-Range", "bytes 4096-8191/16384")
                    exchange.sendResponseHeaders(206, body.size.toLong())
                    exchange.responseBody.use { out -> out.write(body) }
                } catch (_: Throwable) {
                } finally {
                    exchange.close()
                }
            }
            server.createContext("/race") { exchange ->
                val state = currentRace.get()
                try {
                    val total = 1024L * 1024L
                    val rangeHeader = exchange.requestHeaders.getFirst("Range").orEmpty().trim()
                    val match = RANGE_HEADER_REGEX.matchEntire(rangeHeader)
                    val rangeStart = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val requestedEnd =
                        match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (total - 1L)
                    val rangeEnd = requestedEnd.coerceAtMost(total - 1L)
                    val contentLen = (rangeEnd - rangeStart + 1L).coerceAtLeast(1L)
                    exchange.responseHeaders.add("Content-Range", "bytes $rangeStart-$rangeEnd/$total")
                    exchange.sendResponseHeaders(206, contentLen)
                    exchange.responseBody.flush()
                    state.headersSent.complete(Unit)
                    exchange.responseBody.use { out ->
                        val prefixLen = minOf(1024L, contentLen).toInt()
                        out.write(ByteArray(prefixLen) { 2 })
                        out.flush()
                        if (contentLen > prefixLen && releaseHeldBodies.count > 0L) {
                            state.holdBody.await(5, TimeUnit.SECONDS)
                        }
                    }
                } catch (_: Throwable) {
                    state.headersSent.complete(Unit)
                } finally {
                    exchange.close()
                }
            }
            server.createContext("/ok") { exchange ->
                try {
                    val body = ByteArray(4096) { 7 }
                    exchange.responseHeaders.add("Content-Range", "bytes 0-4095/4096")
                    exchange.sendResponseHeaders(206, body.size.toLong())
                    exchange.responseBody.use { out -> out.write(body) }
                } finally {
                    exchange.close()
                }
            }
            server.start()

            val port = server.address.port
            val api = HttpClient(MockEngine { error("API client must not be used") })
            val cdn = defaultCdnHttpClient(16)
            val client = PikPakClient(
                account = "okhttp-leak-test",
                passwordSupplier = { "unused" },
                sessionStore = InMemorySessionStore(),
                retryPolicy = RetryPolicy.None,
                httpClient = api,
                cdnHttpClient = cdn,
                accountConnectionBudget = 16,
                steerEdgeHosts = false,
            )

            try {
                // 1. Mismatched Content-Range (RangeStreamEndpoint.kt:184-197) closes response body cleanly.
                repeat(8) {
                    assertFailsWith<PikPakException> {
                        client.streamRangeFromUrl("http://127.0.0.1:$port/bad-range", 0L, 4096L) { }
                    }
                }

                // 2. Cancel both at valid HTTP 206 header arrival (pre-dispatch writer window) and mid-body after validation passes.
                val reader = RangeReader(client, { "http://127.0.0.1:$port/race?sig=1" }, connectionBudget = 8)
                try {
                    // 2a. Pre-body header-arrival cancellation race.
                    repeat(16) { i ->
                        val race = RaceExchange()
                        currentRace.set(race)
                        val job = launch(Dispatchers.Default) {
                            reader.read(0L, 64L * 1024L, PikPakStreamReader.BLOCKING_PRIORITY) { ch ->
                                val buf = ByteArray(4096)
                                while (ch.readAvailable(buf, 0, buf.size) >= 0) {
                                    // consume until cancelled
                                }
                            }
                        }
                        try {
                            race.headersSent.await()
                            if (i % 3 == 1) delay(1.milliseconds)
                            job.cancel()
                            job.join()
                        } finally {
                            race.holdBody.countDown()
                        }
                    }

                    // 2b. Valid 206 post-header body-entry cancellation (verifies Content-Range matched requested 64 KiB range).
                    var postHeaderCancels = 0
                    repeat(16) {
                        val race = RaceExchange()
                        currentRace.set(race)
                        val bodyEnteredGate = CompletableDeferred<Int>()
                        val job = launch(Dispatchers.Default) {
                            reader.read(0L, 64L * 1024L, PikPakStreamReader.BLOCKING_PRIORITY) { ch ->
                                val buf = ByteArray(4096)
                                while (true) {
                                    val n = ch.readAvailable(buf, 0, buf.size)
                                    if (n < 0) break
                                    if (n > 0) {
                                        assertEquals(2.toByte(), buf[0])
                                        bodyEnteredGate.complete(n)
                                    }
                                }
                            }
                        }
                        job.invokeOnCompletion { cause ->
                            if (!bodyEnteredGate.isCompleted) {
                                bodyEnteredGate.completeExceptionally(
                                    cause ?: AssertionError("206 stream ended before delivering prefix bytes"),
                                )
                            }
                        }
                        try {
                            val firstBytes = bodyEnteredGate.await()
                            assertTrue(firstBytes > 0)
                            postHeaderCancels++
                            job.cancel()
                            job.join()
                        } finally {
                            race.holdBody.countDown()
                        }
                    }
                    assertEquals(16, postHeaderCancels)
                } finally {
                    reader.close()
                }

                // 3. Normal completed 206 reads still reuse pooled HTTP/1.1 keep-alive connections!
                var sawReused = false
                repeat(4) {
                    val telemetry = ReadTelemetry()
                    withContext(telemetry) {
                        client.streamRangeFromUrl("http://127.0.0.1:$port/ok", 0L, 4096L) { stream ->
                            val buf = ByteArray(4096)
                            var off = 0
                            while (off < buf.size) {
                                val n = stream.channel.readAvailable(buf, off, buf.size - off)
                                if (n < 0) break
                                off += n
                            }
                            assertEquals(4096, off)
                        }
                    }
                    if (telemetry.connectionReused == true) sawReused = true
                }
                assertTrue(sawReused, "completed 206 exchanges must still return reusable HTTP/1.1 sockets to ConnectionPool")

                // Force GC + pool activity so RealConnectionPool.pruneAndGetAllocationCount surfaces any leaked ResponseBody.
                repeat(4) {
                    System.gc()
                    delay(25.milliseconds)
                    client.streamRangeFromUrl("http://127.0.0.1:$port/ok", 0L, 4096L) { stream ->
                        val buf = ByteArray(4096)
                        var off = 0
                        while (off < buf.size) {
                            val n = stream.channel.readAvailable(buf, off, buf.size - off)
                            if (n < 0) break
                            off += n
                        }
                    }
                }

                assertTrue(leakedMessages.isEmpty(), "OkHttp logged leaked ResponseBody/connection: $leakedMessages")
            } finally {
                releaseHeldBodies.countDown()
                okHttpLogger.removeHandler(handler)
                okHttpLogger.level = previousLevel
                client.close()
                cdn.close()
                api.close()
                server.stop(0)
                executor.shutdownNow()
            }
        }

    private companion object {
        val RANGE_HEADER_REGEX = Regex("""bytes=(\d+)-(\d*)""")
    }
}
