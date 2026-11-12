package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AdaptiveRangeSource
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BlockCacheTest {
    @Test
    fun `foreground warm stays bounded to two workers and yields full capacity to active foreground read`() = runTest {
        val scheduler = TransferScheduler(
            16,
            8,
            BandwidthLimiter(),
            testScheduler.timeSource,
            concurrencyMode = ConcurrencyMode.Fixed(8),
        )
        val players = List(2) { scheduler.register(8) {} }
        val downloads = List(2) { scheduler.register(8) {} }
        var active = 0
        var allowance = 0
        val priorities = mutableListOf<Int>()
        val source = object : RangeSource, AdaptiveRangeSource {
            override val maxConnections = 8
            override val connectionLimit = MutableStateFlow(8)
            override fun updateConnectionAllowance(value: Int) {
                allowance = value
                connectionLimit.value = value
            }
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T {
                priorities += priority
                active++
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
        }
        val cache = BlockCache(
            source,
            4096,
            8,
            coroutineContext,
            null,
            blockSize = 64,
            readAheadBytes = 512,
            memoryCapBytes = 4096,
            scheduler = scheduler,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(ConsumerIntent(service = ServiceIntent.PLAYING))
        try {
            val warm = cache.warm(listOf(0L..2047L), StreamRole.FOREGROUND, null)
            runCurrent()
            assertEquals(8, allowance)
            assertEquals(2, active, "warm without active stream stays capped to 2 workers")
            assertEquals(listOf(8, 8), players.map(scheduler::connectionAllowanceFor))
            assertTrue(downloads.all { scheduler.spanFor(it, 2, true) > 1 })

            val ticket = cache.beginRead(owner, 2048L)
            val read = async { ticket.readSome(ByteArray(1), 0, 1) }
            runCurrent()
            assertTrue(active > 2, "active foreground stream lifts the 2-worker warm cap")
            assertTrue(PikPakStreamReader.STREAMING_PRIORITY in priorities)
            read.cancelAndJoin()
            ticket.endRead(allowFinishInFlight = false)
            owner.close()
            warm.cancelAndJoin()
            runCurrent()
            assertEquals(0, active)
            assertEquals(0, allowance)
        } finally {
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
            (players + downloads).forEach(scheduler::unregister)
        }
    }
}
