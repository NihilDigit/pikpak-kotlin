package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ReadTelemetryTest {
    @Test
    fun `stream progress is monotonic and clean steering remains trainable`() = runBlocking<Unit> {
        val clock = kotlin.time.TestTimeSource()
        val increments = mutableListOf<Long>()
        val telemetry = ReadTelemetry(clock) { increments += it }
        telemetry.attempts = 1
        telemetry.rerouted = true
        telemetry.recordBytes(4)
        clock += 10.milliseconds
        telemetry.recordBytes(7)
        assertEquals(listOf(4L, 7L), increments)
        assertEquals(11L, telemetry.deliveredBytes)
        assertEquals(10.milliseconds, telemetry.elapsed)
        assertTrue(telemetry.isClean)
        telemetry.rightCensored = true
        assertFalse(telemetry.isClean)
    }

    @Test
    fun `attempt service rate excludes consumer blocking and cancellation is censored`() = runBlocking<Unit> {
        val clock = kotlin.time.TestTimeSource()
        val recorder = RangeAttemptRecorder(0, 200, 1, 0, 0, timeSource = clock)
        clock += 10.milliseconds
        recorder.record(100)
        clock += 100.milliseconds
        recorder.recordSinkWait(100.milliseconds)
        clock += 10.milliseconds
        recorder.record(100)
        val sample = recorder.finish(RangeAttempt.Outcome.Cancelled)
        assertEquals(20.milliseconds, sample.networkDuration)
        assertEquals(20_000L, recorder.bodyBytesPerSecond())
        assertEquals(20_000L, sample.bytesPerSecondAfterFirstByte)
        assertTrue(sample.rightCensored)
        assertEquals(200L, sample.timeline.sum())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `reader is its own source and nullable reads remain available`() = runBlocking<Unit> {
        val client = client()
        try {
            val reader = RangeReader(client, { "https://cdn/file" })
            val source: RangeSource = reader
            assertSame(reader, reader.asRangeSource())
            assertContentEquals(byteArrayOf(1, 2, 3), source.readBytes(0, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), reader.read(0, null) { it.readFully(3) })
            assertContentEquals(byteArrayOf(1, 2, 3), reader.read(0) { it.readFully(3) })
        } finally {
            client.close()
        }
    }

    @Test
    fun `clean read records bytes timing and one attempt`() = runBlocking<Unit> {
        val client = client()
        try {
            val telemetry = ReadTelemetry()
            withContext(telemetry) { RangeReader(client, { "https://cdn/file" }).readBytes(0, 3) }
            assertEquals(3L, telemetry.deliveredBytes)
            assertEquals(1, telemetry.attempts)
            assertEquals(0, telemetry.retries)
            assertEquals(0, telemetry.throttled)
            assertTrue(telemetry.gateWait >= Duration.ZERO)
            assertTrue(telemetry.networkDuration > Duration.ZERO)
            assertTrue(assertNotNull(telemetry.timeToFirstByte) <= telemetry.networkDuration)
            assertTrue(telemetry.isClean)
        } finally {
            client.close()
        }
    }

    @Test
    fun `internal retry makes a read unclean`() = runBlocking<Unit> {
        val client = client(failFirst = HttpStatusCode.ServiceUnavailable)
        try {
            val telemetry = ReadTelemetry()
            withContext(telemetry) { RangeReader(client, { "https://cdn/file" }).readBytes(0, 3) }
            assertEquals(1, telemetry.attempts)
            assertEquals(1, telemetry.retries)
            assertEquals(1, telemetry.throttled)
            assertEquals(3L, telemetry.deliveredBytes)
            assertFalse(telemetry.isClean)
        } finally {
            client.close()
        }
    }

    @Test
    fun `expiry records refresh and another attempt`() = runBlocking<Unit> {
        val client = client(failFirst = HttpStatusCode.Forbidden)
        try {
            val telemetry = ReadTelemetry()
            var issued = 0
            withContext(telemetry) {
                RangeReader(client, { "https://cdn/file?sig=${issued++}" }).readBytes(0, 3)
            }
            assertEquals(2, telemetry.attempts)
            assertTrue(telemetry.refreshed)
            assertFalse(telemetry.rerouted)
            assertEquals(3L, telemetry.deliveredBytes)
            assertFalse(telemetry.isClean)
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancelled gate waiter records wait without starting network`() = runBlocking<Unit> {
        val client = client()
        try {
            val reader = RangeReader(client, { "https://cdn/file" }, connectionBudget = 1)
            val holding = CompletableDeferred<Unit>()
            val holder = async {
                reader.read(0, 3) {
                    holding.complete(Unit)
                    awaitCancellation()
                }
            }
            holding.await()
            val telemetry = ReadTelemetry()
            val entered = CompletableDeferred<Unit>()
            val waiter = async(telemetry) {
                entered.complete(Unit)
                reader.readBytes(0, 3)
            }
            entered.await()
            waiter.cancel()
            waiter.join()
            holder.cancel()
            holder.join()
            assertTrue(telemetry.gateWait > Duration.ZERO)
            assertEquals(Duration.ZERO, telemetry.networkDuration)
            assertEquals(0, telemetry.attempts)
            assertFalse(telemetry.isClean)
        } finally {
            client.close()
        }
    }

    private fun client(failFirst: HttpStatusCode? = null): PikPakClient {
        var requests = 0
        val mock = HttpClient(MockEngine {
            requests++
            if (requests == 1 && failFirst != null) {
                respond(ByteReadChannel(ByteArray(0)), failFirst)
            } else {
                respond(
                    ByteReadChannel(byteArrayOf(1, 2, 3)),
                    HttpStatusCode.PartialContent,
                    Headers.build {
                        append(HttpHeaders.ContentRange, "bytes 0-2/3")
                        append(HttpHeaders.ContentLength, "3")
                    },
                )
            }
        })
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = 1.milliseconds, maxDelay = 2.milliseconds),
            httpClient = mock,
            cdnHttpClient = mock,
        )
    }
}
