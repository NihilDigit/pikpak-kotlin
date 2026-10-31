package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransferScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TransferSchedulerTest {
    @Test
    fun `foreground keeps full workers while hungry background gets two`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val player = scheduler.register(8) {}
        val download = scheduler.register(8) {}
        scheduler.update(player, true, true, true)
        scheduler.update(download, false, false, true)
        assertEquals(8, scheduler.workersFor(player, false))
        assertEquals(2, scheduler.workersFor(download, true))
        assertEquals(1, scheduler.spanFor(download, 2))
        scheduler.update(player, true, false, false)
        assertEquals(8, scheduler.workersFor(download, true))
        scheduler.update(player, true, false, true)
        assertEquals(4, scheduler.workersFor(player, true))
        assertEquals(4, scheduler.workersFor(download, true))
    }

    @Test
    fun `background files share account and interactive cap expires`() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), time)
        val downloads = List(4) { scheduler.register(8) {} }
        downloads.forEach { scheduler.update(it, false, false, true) }
        assertEquals(listOf(4, 4, 4, 4), downloads.map { scheduler.workersFor(it, true) })
        scheduler.onInteractiveActivity()
        assertEquals(listOf(2, 2, 2, 2), downloads.map { scheduler.workersFor(it, true) })
        time += 501.milliseconds
        assertEquals(listOf(4, 4, 4, 4), downloads.map { scheduler.workersFor(it, true) })
    }

    @Test
    fun `three unthrottled downloads use integer account shares`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val downloads = List(3) { scheduler.register(8) {} }
        downloads.forEach { scheduler.update(it, false, false, true) }
        assertEquals(listOf(5, 5, 5), downloads.map { scheduler.workersFor(it, true) })
    }

    @Test
    fun `hungry downloads also respect interactive aggregate cap`() {
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter())
        val player = scheduler.register(8) {}
        scheduler.update(player, true, true, false)
        val downloads = List(3) { scheduler.register(8) {} }
        downloads.forEach { scheduler.update(it, false, false, true) }
        scheduler.onInteractiveActivity()
        assertTrue(downloads.sumOf { scheduler.workersFor(it, true) } <= 4)
        assertTrue(downloads.all { scheduler.workersFor(it, true) <= 2 })
    }

    private fun sample(bytes: Long = 15_625_000) = ReadTelemetry().apply {
        attempts = 1
        deliveredBytes = bytes
        timeToFirstByte = 1350.milliseconds
        networkDuration = 2350.milliseconds
    }

    @Test
    fun `efficiency spans amortize high latency gigabit without worker cap`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val reg = scheduler.register(8) {}
        scheduler.recordSample(reg, sample(), 15_625_000, false)
        val span = scheduler.spanFor(reg, 8, durable = true)
        val bytes = span * 262144L
        assertTrue(bytes >= 15_625_000 * 1.35 * 19)
        assertTrue(bytes < 1024L * 1024 * 1024)
        assertEquals(span, scheduler.spanFor(reg, 1, durable = true))
        assertTrue(scheduler.spanFor(reg, 8) * 262144L <= 32L * 1024 * 1024)
        assertEquals(1, scheduler.spanFor(scheduler.register(8) {}, 8, true))
    }

    @Test
    fun `retries and censored reads do not train but steered successes do`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val reg = scheduler.register(8) {}
        scheduler.recordSample(reg, sample().apply { retries = 1 }, 15_625_000, false)
        scheduler.recordSample(reg, sample(), 15_625_000, true)
        assertEquals(1, scheduler.spanFor(reg, 8, true))
        scheduler.recordSample(reg, sample().apply { rerouted = true }, 15_625_000, false)
        assertTrue(scheduler.spanFor(reg, 8, true) > 256)
    }

    @Test
    fun `limiter lowers durable service rate without capping playback`() {
        val limiter = BandwidthLimiter()
        val scheduler = TransferScheduler(16, 8, limiter)
        val reg = scheduler.register(8) {}
        scheduler.recordSample(reg, sample(), 15_625_000, false)
        val playback = scheduler.spanFor(reg, 8)
        limiter.bytesPerSecond = 1024L * 1024
        assertEquals(13, scheduler.spanFor(reg, 8, true))
        assertEquals(playback, scheduler.spanFor(reg, 8))
    }

    @Test
    fun `probe ownership and release use progress not completion lumps`() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), time)
        val first = scheduler.register(8) {}
        val second = scheduler.register(8) {}
        listOf(first, second).forEach {
            scheduler.update(it, false, false, true)
            scheduler.recordSample(it, sample(), 15_625_000, false)
            assertFalse(scheduler.tryAcquireProbe(it))
            scheduler.recordProgress(it, 262144)
        }
        time += 1.seconds
        listOf(first, second).forEach { scheduler.recordProgress(it, 262144) }
        assertTrue(scheduler.tryAcquireProbe(first))
        assertFalse(scheduler.tryAcquireProbe(second))
        scheduler.unregister(first)
        assertTrue(scheduler.tryAcquireProbe(second))
        scheduler.releaseProbe(second)
        assertEquals(8, scheduler.workersFor(second, true))
        assertTrue(scheduler.tryAcquireProbe(second))
        time += 5.seconds
        scheduler.recordProgress(second, 5 * 524288L)
        assertFalse(scheduler.tryAcquireProbe(second))
        scheduler.releaseProbe(second)
        assertEquals(7, scheduler.workersFor(second, true))
    }
}
