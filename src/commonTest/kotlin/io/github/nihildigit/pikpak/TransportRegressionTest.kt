package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.CacheDemand
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds

class TransportRegressionTest {
    @Test fun foregroundMinimum() {
        for (budget in listOf(2, 4)) {
            val scheduler = TransferScheduler(budget, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
            val players = List(2) { scheduler.register(8) {} }
            players.forEach { scheduler.reportDemand(it, CacheDemand(foreground = true, hungry = true)) }
            val downloads = if (budget == 4) List(2) { scheduler.register(8) {} } else emptyList()
            downloads.forEach { scheduler.reportDemand(it, CacheDemand(background = true)) }
            assertEquals(listOf(1, 1), players.map(scheduler::connectionAllowanceFor))
            assertEquals(budget, (players + downloads).sumOf(scheduler::connectionAllowanceFor))
        }
    }

    @Test fun scarceForegroundRotates() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(1, 8, BandwidthLimiter(), time)
        val players = List(2) { scheduler.register(8) {} }
        players.forEach { scheduler.reportDemand(it, CacheDemand(foreground = true, hungry = true)) }
        assertEquals(listOf(1, 0), players.map(scheduler::connectionAllowanceFor))
        time += 1000.milliseconds
        assertEquals(listOf(0, 1), players.map(scheduler::connectionAllowanceFor))
    }

    @Test fun rotationInvalidatesProbe() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(5, 8, BandwidthLimiter(), time)
        val a = scheduler.register(8) {}
        val b = scheduler.register(8) {}
        listOf(a, b).forEach { scheduler.reportDemand(it, CacheDemand(background = true)) }
        val sample = ReadTelemetry(time).apply {
            attempts = 1; deliveredBytes = 1000
            timeToFirstByte = 10.milliseconds; networkDuration = 110.milliseconds
        }
        scheduler.recordSample(a, sample, 1000, false)
        scheduler.recordProgress(a, 1000)
        time += 500.milliseconds
        scheduler.recordProgress(a, 1000)
        assertEquals(3, scheduler.connectionAllowanceFor(a))
        assertTrue(scheduler.tryAcquireProbe(a))
        assertEquals(1, scheduler.workersFor(a, true))
        time += 500.milliseconds
        scheduler.recordProgress(a, 1000)
        assertEquals(2, scheduler.connectionAllowanceFor(a))
        assertEquals(2, scheduler.workersFor(a, true), "allocation changed: abort experiment and restore workers")
        assertFalse(scheduler.tryAcquireProbe(a), "old samples cannot seed another experiment")
    }

    @Test fun burstExpiryInvalidatesProbeBeforeProgressCanResolveIt() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), time)
        val reg = scheduler.register(8) {}
        scheduler.reportDemand(reg, CacheDemand(background = true))
        scheduler.onInteractiveActivity()
        scheduler.recordSample(reg, ReadTelemetry(time).apply {
            attempts = 1; deliveredBytes = 1000
            timeToFirstByte = 10.milliseconds; networkDuration = 110.milliseconds
        }, 1000, false)
        scheduler.recordProgress(reg, 1000)
        time += 100.milliseconds
        scheduler.recordProgress(reg, 1000)
        assertTrue(scheduler.tryAcquireProbe(reg))
        assertEquals(2, scheduler.workersFor(reg, true))
        time += 2100.milliseconds
        scheduler.recordProgress(reg, 100_000)
        assertEquals(8, scheduler.connectionAllowanceFor(reg))
        assertEquals(4, scheduler.workersFor(reg, true))
        assertFalse(scheduler.tryAcquireProbe(reg))
    }

    @Test fun unchangedAllocationsKeepBaselineAcrossTicks() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), time)
        val reg = scheduler.register(8) {}
        scheduler.reportDemand(reg, CacheDemand(background = true))
        scheduler.recordSample(reg, ReadTelemetry(time).apply {
            attempts = 1; deliveredBytes = 1000
            timeToFirstByte = 10.milliseconds; networkDuration = 110.milliseconds
        }, 1000, false)
        scheduler.recordProgress(reg, 1000)
        time += 500.milliseconds
        scheduler.recordProgress(reg, 1000)
        time += 500.milliseconds
        assertEquals(8, scheduler.connectionAllowanceFor(reg))
        assertTrue(scheduler.tryAcquireProbe(reg))
        time += 1000.milliseconds
        assertEquals(6, scheduler.workersFor(reg, true))
        assertFalse(scheduler.tryAcquireProbe(reg))
    }

    @Test fun rateLimitRetriesWithoutRefresh(): Unit = runBlocking {
        var calls = 0
        var issued = 0
        val http = HttpClient(MockEngine {
            calls++
            if (calls <= 2) respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
            else respond(ByteReadChannel(byteArrayOf(1)), HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange to listOf("bytes 0-0/1"), HttpHeaders.ContentLength to listOf("1")))
        })
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http, sessionStore = InMemorySessionStore())
        val reader = RangeReader(client, { issued++; "https://cdn/file?sign=s" })
        try {
            assertContentEquals(byteArrayOf(1), reader.readBytes(0, 1))
            assertEquals(1, issued)
        } finally { reader.close(); client.close(); http.close() }
    }
}
