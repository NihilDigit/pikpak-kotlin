package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.TransferScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferSchedulerTest {
    @Test fun modesAreCeilingsNotExperiments() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        var notifications = 0
        val file = scheduler.register { notifications++ }
        assertEquals(8, scheduler.connectionAllowanceFor(file))
        scheduler.concurrencyMode = ConcurrencyMode.Serial
        assertEquals(1, scheduler.connectionAllowanceFor(file))
        scheduler.concurrencyMode = ConcurrencyMode.Fixed(3)
        assertEquals(3, scheduler.connectionAllowanceFor(file))
        scheduler.concurrencyMode = ConcurrencyMode.Adaptive()
        assertEquals(8, scheduler.connectionAllowanceFor(file))
        scheduler.concurrentAcceleration = false
        assertEquals(1, scheduler.connectionAllowanceFor(file))
        scheduler.concurrentAcceleration = true
        assertEquals(8, scheduler.connectionAllowanceFor(file))
        assertTrue(notifications >= 5)
        scheduler.unregister(file)
    }

    @Test fun spanFollowsDurableVsStreamingBand() {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val file = scheduler.register {}
        assertEquals(4096, scheduler.spanFor(file, 4, durable = true))
        assertEquals(1, scheduler.spanFor(file, 4, durable = false))
        scheduler.unregister(file)
    }
}
