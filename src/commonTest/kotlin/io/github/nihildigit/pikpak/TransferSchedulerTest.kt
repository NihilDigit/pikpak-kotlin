package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.CacheDemand
import io.github.nihildigit.pikpak.internal.TransferScheduler
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferSchedulerTest {
    @Test fun backgroundRecoversWithoutClockOrSamples() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        var notifications = 0
        val background = scheduler.register { notifications++ }
        val player = scheduler.register {}
        scheduler.reportDemand(background, CacheDemand(background = true))
        assertEquals(8, scheduler.workersFor(background, true))
        scheduler.reportDemand(player, CacheDemand(foreground = true, hungry = true))
        assertEquals(0, scheduler.workersFor(background, true))
        scheduler.reportDemand(player, CacheDemand(foreground = true))
        assertEquals(8, scheduler.workersFor(background, true))
        scheduler.reportDemand(player, CacheDemand(foreground = true, urgent = true))
        assertEquals(0, scheduler.workersFor(background, true))
        scheduler.unregister(player)
        assertEquals(8, scheduler.workersFor(background, true))
        kotlin.test.assertTrue(notifications >= 5)
    }
    @Test fun modesAreCeilingsNotExperiments() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val file = scheduler.register {}
        scheduler.reportDemand(file, CacheDemand(background = true))
        assertEquals(8, scheduler.workersFor(file, true))
        scheduler.concurrencyMode = ConcurrencyMode.Serial
        assertEquals(1, scheduler.workersFor(file, true))
        scheduler.concurrencyMode = ConcurrencyMode.Fixed(3)
        assertEquals(3, scheduler.workersFor(file, true))
        scheduler.concurrencyMode = ConcurrencyMode.Adaptive()
        assertEquals(8, scheduler.workersFor(file, true))
    }
    @Test fun mixedCacheYieldsOnlyDurableWork() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val file = scheduler.register {}
        scheduler.reportDemand(file, CacheDemand(foreground = true, background = true, hungry = true))
        assertEquals(8, scheduler.workersFor(file, false))
        assertEquals(0, scheduler.workersFor(file, true))
    }
}
