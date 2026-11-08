package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.github.nihildigit.pikpak.internal.CacheDemand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TransferSchedulerTest {
    @Test
    fun `small budgets and mixed caches never double count account allocation`() {
        for (budget in 1..20) {
            val scheduler = TransferScheduler(budget, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
            val players = List(3) { scheduler.register(8) {} }
            val warms = List(7) { scheduler.register(8) {} }
            val downloads = List(4) { scheduler.register(8) {} }
            players.forEach { scheduler.reportDemand(it, CacheDemand(foreground = true, warm = true, background = true)) }
            warms.forEach { scheduler.reportDemand(it, CacheDemand(warm = true, background = true)) }
            downloads.forEach { scheduler.reportDemand(it, CacheDemand(background = true)) }
            val all = players + warms + downloads
            for (hungry in listOf(false, true)) {
                scheduler.reportDemand(players[0], CacheDemand(foreground = true, background = true, hungry = hungry))
                assertTrue(all.sumOf(scheduler::connectionAllowanceFor) <= budget)
                assertTrue(warms.all { scheduler.workersFor(it, false) <= if (hungry) 1 else 2 })
                assertTrue(all.all { scheduler.workersFor(it, true) <= scheduler.connectionAllowanceFor(it) })
                if (hungry) assertEquals(0, scheduler.workersFor(players[0], true))
            }
        }
    }

    @Test
    fun `allocation changes invalidate experiments instead of learning contention as poor scaling`() {
        val e = Experiment()
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(6, e.scheduler.workersFor(e.reg, true))
        val warmer = e.scheduler.register(8) {}
        e.scheduler.reportDemand(warmer, CacheDemand(warm = true))
        e.scheduler.releaseProbe(e.reg)
        assertEquals(4, e.scheduler.workersFor(e.reg, true))
        assertFalse(e.scheduler.hasPositiveScalingGain(e.reg))
        assertFalse(e.scheduler.tryAcquireProbe(e.reg), "new allocation needs a fresh baseline")
        e.scheduler.unregister(warmer)
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(6, e.scheduler.workersFor(e.reg, true))
    }
    @Test
    fun `two players two warm caches and two downloads share a single bounded budget`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
        val players = List(2) { scheduler.register(8) {} }
        val warms = List(2) { scheduler.register(8) {} }
        val downloads = List(2) { scheduler.register(8) {} }
        players.forEach { scheduler.reportDemand(it, CacheDemand(foreground = true)) }
        warms.forEach { scheduler.reportDemand(it, CacheDemand(warm = true, hungry = true)) }
        downloads.forEach { scheduler.reportDemand(it, CacheDemand(background = true)) }
        val all = players + warms + downloads
        assertFalse(scheduler.isAccountHungry())
        assertEquals(listOf(5, 5, 1, 1, 2, 2), all.map(scheduler::connectionAllowanceFor))
        downloads.forEach {
            assertTrue(scheduler.spanFor(it, 2, true) > 1)
            assertEquals(2, scheduler.workersFor(it, true))
        }
        scheduler.reportDemand(players[0], CacheDemand(foreground = true, hungry = true))
        assertTrue(scheduler.isAccountHungry())
        assertEquals(8, scheduler.connectionAllowanceFor(players[0]))
        assertTrue(all.sumOf(scheduler::connectionAllowanceFor) <= 16)
        downloads.forEach {
            assertEquals(1, scheduler.spanFor(it, 2, true))
            assertTrue(scheduler.workersFor(it, true) <= 2)
        }
        warms.forEach { assertTrue(scheduler.workersFor(it, false) <= 1) }
        scheduler.reportDemand(players[0], CacheDemand(foreground = true))
        assertFalse(scheduler.isAccountHungry())
        assertEquals(listOf(5, 5, 1, 1, 2, 2), all.map(scheduler::connectionAllowanceFor))
    }

    @Test
    fun `many warms cannot dilute playback and released reserves are reusable`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
        val players = List(2) { scheduler.register(8) {} }
        players.forEach { scheduler.reportDemand(it, CacheDemand(foreground = true)) }
        val warms = List(20) { scheduler.register(8) {} }
        warms.forEach { scheduler.reportDemand(it, CacheDemand(warm = true)) }
        assertEquals(listOf(7, 7), players.map(scheduler::connectionAllowanceFor))
        assertEquals(2, warms.sumOf(scheduler::connectionAllowanceFor))
        warms.forEach(scheduler::unregister)
        assertEquals(listOf(8, 8), players.map(scheduler::connectionAllowanceFor))
    }

    @Test
    fun `promotion reopens upward experiments without bypassing CDN backoff`() {
        val e = Experiment()
        val others = List(3) { e.scheduler.register(8) {} }
        others.forEach { e.scheduler.reportDemand(it, CacheDemand(background = true)) }
        assertEquals(2, e.scheduler.connectionAllowanceFor(e.reg))
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.finish(1000)
        assertEquals(1, e.scheduler.workersFor(e.reg, true))
        others.forEach(e.scheduler::unregister)
        e.scheduler.reportDemand(e.reg, CacheDemand(foreground = true))
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(2, e.scheduler.workersFor(e.reg, false))
        e.finish(1200)
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(4, e.scheduler.workersFor(e.reg, false))
        e.scheduler.onThrottled(e.reg)
        e.scheduler.reportDemand(e.reg, CacheDemand(warm = true))
        e.scheduler.reportDemand(e.reg, CacheDemand(foreground = true))
        assertFalse(e.scheduler.tryAcquireProbe(e.reg))
    }

    @Test
    fun `warm only work cannot train a playback capacity probe or claim emergency hunger`() {
        val e = Experiment()
        e.scheduler.reportDemand(e.reg, CacheDemand(warm = true, hungry = true))
        e.baseline(1000)
        assertFalse(e.scheduler.isAccountHungry())
        assertFalse(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(2, e.scheduler.workersFor(e.reg, false))
        e.scheduler.reportDemand(e.reg, CacheDemand(foreground = true))
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(6, e.scheduler.workersFor(e.reg, false))
    }
    @Test
    fun `adaptive starts at four and earns six then eight while serial and fixed remain exact`() {
        val e = Experiment()
        assertEquals(4, e.scheduler.workersFor(e.reg, true))
        e.growToEight()
        assertTrue(e.scheduler.hasPositiveScalingGain(e.reg))
        e.scheduler.concurrencyMode = ConcurrencyMode.Serial
        assertEquals(1, e.scheduler.workersFor(e.reg, true))
        e.scheduler.concurrencyMode = ConcurrencyMode.Fixed(7)
        assertEquals(7, e.scheduler.workersFor(e.reg, true))
        e.scheduler.concurrencyMode = ConcurrencyMode.Adaptive()
        assertEquals(4, e.scheduler.workersFor(e.reg, true))
    }
    @Test
    fun `503 steps down hungry foreground and probe cleanup cannot undo it`() {
        val e = Experiment()
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.scheduler.update(e.reg, true, true, false)
        e.scheduler.onThrottled(e.reg)
        val reduced = e.scheduler.workersFor(e.reg, false)
        assertTrue(reduced < 8)
        e.scheduler.releaseProbe(e.reg)
        assertEquals(reduced, e.scheduler.workersFor(e.reg, false))
        assertFalse(e.scheduler.tryAcquireProbe(e.reg))
        assertFalse(e.scheduler.hasPositiveScalingGain(e.reg))
    }

    @Test
    fun `fixed worker request still respects temporary CDN congestion`() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), time,
            concurrencyMode = ConcurrencyMode.Fixed(8))
        val reg = scheduler.register(8) {}
        scheduler.update(reg, true, true, false)
        scheduler.onThrottled(reg)
        assertEquals(7, scheduler.workersFor(reg, false))
        time += 4.seconds
        assertEquals(8, scheduler.workersFor(reg, false))
    }

    @Test
    fun `foreground keeps full workers while hungry background gets two`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
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
        assertEquals(8, scheduler.workersFor(download, true))
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
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
        val downloads = List(3) { scheduler.register(8) {} }
        downloads.forEach { scheduler.update(it, false, false, true) }
        assertEquals(listOf(6, 5, 5), downloads.map { scheduler.workersFor(it, true) })
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
    fun `potential lanes share sixteen before the second signature exists`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val first = scheduler.register(16) {}
        scheduler.update(first, false, false, true)
        assertEquals(16, scheduler.connectionAllowanceFor(first))
        val second = scheduler.register(16) {}
        scheduler.update(second, false, false, true)
        assertEquals(listOf(8, 8), listOf(first, second).map { scheduler.connectionAllowanceFor(it) })
        val third = scheduler.register(16) {}
        scheduler.update(third, false, false, true)
        assertEquals(listOf(6, 5, 5), listOf(first, second, third).map { scheduler.connectionAllowanceFor(it) })
        scheduler.unregister(second)
        scheduler.unregister(third)
        assertEquals(16, scheduler.connectionAllowanceFor(first))
    }

    @Test
    fun `scarce background slots rotate in serial fixed and adaptive modes`() {
        for (mode in listOf(ConcurrencyMode.Serial, ConcurrencyMode.Fixed(8), ConcurrencyMode.Adaptive(8))) {
            val time = TestTimeSource()
            val scheduler = TransferScheduler(1, 8, BandwidthLimiter(), time, concurrencyMode = mode)
            val downloads = List(3) { scheduler.register(8) {} }
            downloads.forEach { scheduler.update(it, false, false, true) }
            repeat(6) { turn ->
                assertEquals(List(3) { if (it == turn % 3) 1 else 0 },
                    downloads.map { scheduler.workersFor(it, true) })
                time += 1.seconds
            }
        }
    }

    private class Experiment {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), time)
        val reg = scheduler.register(8) {}
        init {
            scheduler.update(reg, false, false, true)
            warm()
        }
        fun warm(latencyMs: Int = 100) {
            val telemetry = ReadTelemetry(timeSource = time).apply {
                attempts = 1
                recordConnectionAcquired(reused = true)
                deliveredBytes = 1000
                timeToFirstByte = latencyMs.milliseconds
                networkDuration = (1000 + latencyMs).milliseconds
            }
            scheduler.recordSample(reg, telemetry, 1000, false)
        }
        fun baseline(rate: Long) {
            repeat(4) {
                time += 1.seconds
                scheduler.recordProgress(reg, rate)
            }
        }
        fun capAtOne() {
            for (expected in listOf(4, 2, 1)) {
                baseline(1000)
                assertTrue(scheduler.tryAcquireProbe(reg))
                finish(1000)
                assertEquals(expected, scheduler.workersFor(reg, true))
            }
        }
        fun growToEight() {
            for (expected in listOf(6, 8)) {
                baseline(1000)
                assertTrue(scheduler.tryAcquireProbe(reg))
                finish(1200)
                assertEquals(expected, scheduler.workersFor(reg, true))
            }
        }
        fun finish(rate: Long, latencyMs: Int = 100) {
            warm(latencyMs)
            repeat(2) {
                time += 1.seconds
                scheduler.recordProgress(reg, rate)
            }
            scheduler.releaseProbe(reg)
        }
    }

    @Test
    fun `flat capped throughput rejects expansion then descends from four to one`() {
        val e = Experiment()
        assertEquals(4, e.scheduler.workersFor(e.reg, true))
        e.capAtOne()
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.finish(1000)
        assertEquals(1, e.scheduler.workersFor(e.reg, true))
        assertFalse(e.scheduler.hasPositiveScalingGain(e.reg))
        assertFalse(e.scheduler.tryAcquireProbe(e.reg))
    }

    @Test
    fun `down probes freeze span despite completed sample jitter`() {
        val time = TestTimeSource()
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), time)
        val reg = scheduler.register(8, blockSize = 1000) {}
        scheduler.update(reg, false, false, true)
        fun complete(latencyMs: Int) {
            scheduler.recordSample(reg, ReadTelemetry(timeSource = time).apply {
                attempts = 1
                recordConnectionAcquired(reused = true)
                deliveredBytes = 1000
                timeToFirstByte = latencyMs.milliseconds
                networkDuration = (1000 + latencyMs).milliseconds
            }, 1000, false)
        }
        complete(100) // Two blocks; 110ms would round up to three without the freeze.
        for (target in listOf(4, 2, 1)) {
            repeat(4) {
                time += 1.seconds
                scheduler.recordProgress(reg, 1000)
            }
            complete(100)
            assertTrue(scheduler.tryAcquireProbe(reg))
            val span = scheduler.spanFor(reg, target, true)
            repeat(2) { index ->
                time += 1.seconds
                complete(if (index == 0) 110 else 90)
                assertEquals(span, scheduler.spanFor(reg, target, true))
                scheduler.recordProgress(reg, 1000)
            }
            scheduler.releaseProbe(reg)
            assertEquals(target, scheduler.workersFor(reg, true))
        }
    }

    @Test
    fun `long ranges scale upward from streaming progress before completion`() {
        val e = Experiment()
        e.capAtOne()
        for (target in listOf(2, 4, 6, 8)) {
            e.baseline(1000)
            assertTrue(e.scheduler.tryAcquireProbe(e.reg))
            // The Range is still streaming when the two-second probe window ends.
            repeat(5) {
                e.time += 500.milliseconds
                e.scheduler.recordProgress(e.reg, 600)
            }
            assertEquals(target, e.scheduler.workersFor(e.reg, true))
            e.warm() // Completion follows acceptance rather than authorizing it.
            e.scheduler.releaseProbe(e.reg)
            assertEquals(target, e.scheduler.workersFor(e.reg, true))
        }
        assertTrue(e.scheduler.hasPositiveScalingGain(e.reg))
    }

    @Test
    fun `down probe ignores bytes until surplus requests have drained`() {
        val e = Experiment()
        e.growToEight()
        e.baseline(1000)
        e.scheduler.reportActiveWorkers(e.reg, 8, 8)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        repeat(3) {
            e.time += 1.seconds
            e.scheduler.recordProgress(e.reg, 1000)
        }
        // Old eight-request throughput must not validate a six-request experiment.
        e.scheduler.releaseProbe(e.reg)
        assertEquals(8, e.scheduler.workersFor(e.reg, true))
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.scheduler.reportActiveWorkers(e.reg, 6, 8)
        e.finish(1000)
        assertEquals(6, e.scheduler.workersFor(e.reg, true))
    }

    @Test
    fun `adaptive converged to one remains within the reserved emergency background share`() {
        val e = Experiment()
        e.capAtOne()
        val player = e.scheduler.register(8) {}
        e.scheduler.update(player, true, true, false)
        assertEquals(1, e.scheduler.workersFor(e.reg, true))
        e.scheduler.update(player, true, false, false)
        assertEquals(1, e.scheduler.workersFor(e.reg, true))
    }

    @Test
    fun `fixed and adaptive ceilings only bound request capacity`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(3))
        val reg = scheduler.register(8) {}
        scheduler.update(reg, false, false, true)
        assertEquals(3, scheduler.workersFor(reg, true))
        val span = scheduler.spanFor(reg, 3)
        scheduler.concurrencyMode = ConcurrencyMode.Adaptive(2)
        assertEquals(2, scheduler.workersFor(reg, true))
        assertEquals(span, scheduler.spanFor(reg, 2))
    }

    @Test
    fun `positive upward steps reach eight after capped path converges to one`() {
        val e = Experiment()
        e.capAtOne()
        for (target in listOf(2, 4, 6, 8)) {
            e.baseline(1000)
            assertTrue(e.scheduler.tryAcquireProbe(e.reg))
            e.finish(1200)
            assertEquals(target, e.scheduler.workersFor(e.reg, true))
            assertEquals(target >= 8, e.scheduler.hasPositiveScalingGain(e.reg))
        }
    }

    @Test
    fun `upward throughput gain rejects warm latency regression and reverses after cooldown`() {
        val e = Experiment()
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.finish(800)
        assertEquals(4, e.scheduler.workersFor(e.reg, true))
        assertFalse(e.scheduler.tryAcquireProbe(e.reg))
        // Descend to two so an upward rejection can reverse away from both boundaries.
        repeat(1) {
            e.baseline(1000)
            assertTrue(e.scheduler.tryAcquireProbe(e.reg))
            e.finish(1000)
        }
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.finish(800)
        assertEquals(2, e.scheduler.workersFor(e.reg, true))
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        e.finish(1200, 200)
        assertEquals(2, e.scheduler.workersFor(e.reg, true))
        assertFalse(e.scheduler.hasPositiveScalingGain(e.reg))
        assertFalse(e.scheduler.tryAcquireProbe(e.reg))
        e.baseline(1000)
        assertTrue(e.scheduler.tryAcquireProbe(e.reg))
        assertEquals(1, e.scheduler.workersFor(e.reg, true))
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
        assertEquals(4, scheduler.spanFor(scheduler.register(8) {}, 8, true))
    }

    @Test
    fun `retries and censored reads do not train but steered successes do`() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val reg = scheduler.register(8) {}
        scheduler.recordSample(reg, sample().apply { retries = 1 }, 15_625_000, false)
        scheduler.recordSample(reg, sample(), 15_625_000, true)
        assertEquals(4, scheduler.spanFor(reg, 8, true))
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
        assertEquals(4, scheduler.workersFor(second, true))
        assertTrue(scheduler.tryAcquireProbe(second))
        time += 5.seconds
        scheduler.recordProgress(second, 5 * 1048576L)
        assertFalse(scheduler.tryAcquireProbe(second))
        scheduler.releaseProbe(second)
        assertEquals(6, scheduler.workersFor(second, true))
    }

    @Test
    fun `serial changes capacity but not span learning and hungry foreground suppresses background`() {
        var scheduleChanges = 0
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val player = scheduler.register(16) { scheduleChanges++ }
        val download = scheduler.register(8) { scheduleChanges++ }
        scheduler.update(player, true, true, false)
        scheduler.update(download, false, false, true)
        assertEquals(14, scheduler.connectionAllowanceFor(player))
        assertEquals(4, scheduler.workersFor(player, false))

        scheduler.concurrencyMode = ConcurrencyMode.Serial
        assertTrue(scheduleChanges >= 2)
        assertEquals(1, scheduler.connectionAllowanceFor(player))
        assertEquals(0, scheduler.connectionAllowanceFor(download))
        assertEquals(1, scheduler.workersFor(player, false))
        // While foreground player is hungry, background download yields completely
        assertEquals(0, scheduler.workersFor(download, true))
        assertTrue(scheduler.foregroundHungry())
        assertEquals(1, scheduler.spanFor(download, 1, durable = true))
        assertEquals(4, scheduler.spanFor(player, 1, durable = false))

        // Once foreground player buffer is healthy (!hungry), one background file runs sequentially
        val secondDownload = scheduler.register(8) { scheduleChanges++ }
        scheduler.update(secondDownload, false, false, true)
        scheduler.update(player, true, false, false)
        assertFalse(scheduler.foregroundHungry())
        assertEquals(1, scheduler.workersFor(download, true))
        assertEquals(1, scheduler.workersFor(secondDownload, true))
        assertEquals(4, scheduler.spanFor(download, 1, durable = true))
        assertFalse(scheduler.tryAcquireProbe(download))

        scheduler.recordSample(download, sample(), 15_625_000, false)
        val serialSpan = scheduler.spanFor(download, 1, true)
        scheduler.concurrencyMode = ConcurrencyMode.Fixed(8)
        assertEquals(serialSpan, scheduler.spanFor(download, 1, true))
    }
}
