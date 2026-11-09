package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.CacheDemand
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.github.nihildigit.pikpak.internal.AdaptiveRangeSource
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BlockCacheTest {
    @Test
    fun `foreground warm stays bounded and becomes hungry only when a playback cursor blocks`() = runTest {
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), testScheduler.timeSource,
            concurrencyMode = ConcurrencyMode.Fixed(8))
        val players = List(2) { scheduler.register(8) {} }
        val downloads = List(2) { scheduler.register(8) {} }
        players.forEach { scheduler.reportDemand(it, CacheDemand(foreground = true)) }
        downloads.forEach { scheduler.reportDemand(it, CacheDemand(background = true)) }
        val otherWarm = scheduler.register(8) {}
        scheduler.reportDemand(otherWarm, CacheDemand(warm = true))
        var active = 0
        var allowance = 0
        val source = object : RangeSource, AdaptiveRangeSource {
            override val maxConnections = 8
            override val connectionLimit = MutableStateFlow(8)
            override fun updateConnectionAllowance(value: Int) {
                allowance = value
                connectionLimit.value = value
            }
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                block: suspend (ByteReadChannel) -> T): T {
                active++
                try { awaitCancellation() } finally { active-- }
            }
        }
        val cache = BlockCache(source, 4096, 8, coroutineContext, null,
            blockSize = 64, readAheadBytes = 512, memoryCapBytes = 4096,
            scheduler = scheduler, timeSource = testScheduler.timeSource)
        try {
            val warm = cache.warm(listOf(0L..2047L), StreamRole.FOREGROUND, null)
            runCurrent()
            assertFalse(scheduler.isAccountHungry())
            assertEquals(8, allowance)
            assertEquals(2, active)
            assertEquals(listOf(8, 8), players.map(scheduler::connectionAllowanceFor))
            assertTrue(downloads.all { scheduler.spanFor(it, 2, true) > 1 })
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
            runCurrent()
            assertTrue(scheduler.isAccountHungry())
            assertTrue(allowance > 1)
            assertTrue(downloads.all { scheduler.workersFor(it, true) == 0 })
            read.cancelAndJoin()
            cache.closeCursor(cursor)
            runCurrent()
            assertFalse(scheduler.isAccountHungry())
            warm.cancelAndJoin()
            runCurrent()
            assertEquals(0, active)
            assertEquals(0, allowance)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
            (players + downloads + otherWarm).forEach(scheduler::unregister)
        }
    }
}
