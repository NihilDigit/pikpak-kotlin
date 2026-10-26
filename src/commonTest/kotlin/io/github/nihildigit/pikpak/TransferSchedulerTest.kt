package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransferScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        // When the playing file also downloads, both durable downloads share the half-account budget (8).
        scheduler.update(player, true, false, true)
        assertEquals(4, scheduler.workersFor(player, true))
        assertEquals(4, scheduler.workersFor(download, true))
    }

    @Test
    fun `background files share account and interactive cap expires`() = runTest {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), testScheduler.timeSource)
        val downloads = List(4) { scheduler.register(8) {} }
        downloads.forEach { scheduler.update(it, false, false, true) }
        assertEquals(listOf(4, 4, 4, 4), downloads.map { scheduler.workersFor(it, true) })
        scheduler.onInteractiveActivity()
        assertEquals(listOf(2, 2, 2, 2), downloads.map { scheduler.workersFor(it, true) })
        testScheduler.advanceTimeBy(501)
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

    @Test
    fun `only clean complete samples tune spans and limiter bounds them`() {
        val limiter = BandwidthLimiter()
        val scheduler = TransferScheduler(16, 8, limiter)
        val download = scheduler.register(8) {}
        scheduler.update(download, false, false, true)
        fun sample(milliseconds: Long, retries: Int = 0, truncated: Boolean = false) {
            val bytes = scheduler.spanFor(download, 1) * 256L * 1024
            scheduler.recordSample(download, ReadTelemetry().apply {
                attempts = 1
                this.retries = retries
                deliveredBytes = bytes
                networkDuration = milliseconds.milliseconds
            }, bytes, truncated)
        }
        sample(100, retries = 1)
        sample(100, truncated = true)
        assertEquals(1, scheduler.spanFor(download, 8))
        sample(100)
        sample(100)
        assertEquals(2, scheduler.spanFor(download, 8))
        repeat(6) { sample(100) }
        assertEquals(16, scheduler.spanFor(download, 1))
        assertEquals(4, scheduler.spanFor(download, 8))
        val seeded = scheduler.register(8) {}
        assertEquals(4, scheduler.spanFor(seeded, 8))
        sample(1700)
        assertEquals(8, scheduler.spanFor(download, 1))
        limiter.bytesPerSecond = 4L * 1024 * 1024
        assertEquals(2, scheduler.spanFor(download, 8))
    }

    @Test
    fun `probe ownership is account wide and unregister releases it`() = runTest {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), testScheduler.timeSource)
        val first = scheduler.register(8) {}
        val second = scheduler.register(8) {}
        fun sample() = ReadTelemetry().apply {
            attempts = 1
            deliveredBytes = 262144
            networkDuration = 1000.milliseconds
        }
        scheduler.recordSample(first, sample(), 262144, false)
        testScheduler.advanceTimeBy(1000)
        scheduler.recordSample(second, sample(), 262144, false)
        assertTrue(scheduler.tryAcquireProbe(first))
        assertFalse(scheduler.tryAcquireProbe(second))
        scheduler.unregister(first)
        assertTrue(scheduler.tryAcquireProbe(second))
        scheduler.releaseProbe(second)
        assertTrue(scheduler.tryAcquireProbe(second))
    }

    @Test
    fun `resolved probe retains ownership until finally releases it`() = runTest {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), testScheduler.timeSource)
        val download = scheduler.register(8) {}
        scheduler.update(download, false, false, true)
        fun sample(bytes: Long) = ReadTelemetry().apply {
            attempts = 1
            deliveredBytes = bytes
            networkDuration = 1000.milliseconds
        }
        testScheduler.advanceTimeBy(1000)
        scheduler.recordSample(download, sample(262144), 262144, false)
        scheduler.recordSample(download, sample(262144), 262144, false)
        assertTrue(scheduler.tryAcquireProbe(download))
        assertEquals(7, scheduler.workersFor(download, true))
        // A late completion must compare against the whole experiment, not just a 4s suffix.
        testScheduler.advanceTimeBy(5000)
        scheduler.recordSample(download, sample(5 * 524288), 5 * 524288, false)
        assertFalse(scheduler.tryAcquireProbe(download))
        scheduler.releaseProbe(download)
        assertEquals(7, scheduler.workersFor(download, true))
        scheduler.recordSample(download, sample(262144), 262144, false)
        assertTrue(scheduler.tryAcquireProbe(download))
    }
}
