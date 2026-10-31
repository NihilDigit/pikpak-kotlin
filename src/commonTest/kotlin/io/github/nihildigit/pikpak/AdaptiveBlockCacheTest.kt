package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.AdaptiveRangeSource
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveBlockCacheTest {
    private class AdaptiveSource : RangeSource, AdaptiveRangeSource {
        override val maxConnections = 16
        override val connectionLimit = MutableStateFlow(8)
        var allowance = 0
            private set
        private var signedCapacity = 8
        val requests = mutableListOf<CompletableDeferred<Unit>>()
        var active = 0
            private set
        var cancelled = 0
            private set

        override fun updateConnectionAllowance(allowance: Int) {
            this.allowance = allowance
            connectionLimit.value = minOf(signedCapacity, allowance)
        }

        fun finishSecondSignature() {
            signedCapacity = 16
            connectionLimit.value = minOf(signedCapacity, allowance)
        }

        fun finishCurrentReads() {
            requests.toList().forEach { it.complete(Unit) }
        }

        override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                     block: suspend (ByteReadChannel) -> T): T {
            val release = CompletableDeferred<Unit>()
            requests += release
            active++
            try {
                release.await()
                return block(ByteReadChannel(ByteArray(length.toInt())))
            } finally {
                if (!release.isCompleted) cancelled++
                active--
            }
        }
    }

    private fun TestScope.cache(source: AdaptiveSource, scheduler: TransferScheduler? = null,
                                store: BlockStore? = null) = BlockCache(
        source, size = 64 * 1024, concurrency = source.maxConnections,
        parentCoroutineContext = coroutineContext, foregroundStreams = null, store = store,
        blockSize = 64, readAheadBytes = 192, memoryCapBytes = 16 * 1024,
        scheduler = scheduler, timeSource = testScheduler.timeSource,
    )

    @Test
    fun `signed lane growth wakes default workers without a scheduler`() = runTest {
        val source = AdaptiveSource()
        val cache = cache(source)
        try {
            cache.warm(listOf(0L..65535L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(16, source.allowance, "potential allowance must not be limited by the first signature")
            assertEquals(8, source.active)
            source.finishSecondSignature()
            runCurrent()
            assertEquals(16, source.active, "StateFlow growth must wake the parked workers")
            assertEquals(0, source.cancelled)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(0, source.active)
        assertEquals(0, source.allowance)
        assertEquals(0, source.connectionLimit.subscriptionCount.value)
    }

    @Test
    fun `account allowance retreats to eight each then six five five`() = runTest {
        val scheduler = TransferScheduler(16, 16, BandwidthLimiter(), testScheduler.timeSource)
        val sources = List(3) { AdaptiveSource() }
        val caches = sources.map { cache(it, scheduler) }
        try {
            caches[0].warm(listOf(0L..65535L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(listOf(16, 0, 0), sources.map { it.allowance })
            assertEquals(8, sources[0].active)
            sources[0].finishSecondSignature()
            runCurrent()
            assertEquals(16, sources[0].active)

            caches[1].warm(listOf(0L..65535L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(listOf(8, 8, 0), sources.map { it.allowance })
            assertEquals(16, sources[0].active, "shrink drains existing reads rather than cancelling them")
            sources[0].finishCurrentReads()
            runCurrent()
            assertEquals(listOf(8, 8, 0), sources.map { it.active })

            caches[2].warm(listOf(0L..65535L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(listOf(6, 5, 5), sources.map { it.allowance })
            sources.forEach { it.finishCurrentReads() }
            runCurrent()
            assertEquals(listOf(6, 5, 5), sources.map { it.active })
            assertTrue(sources.all { it.cancelled == 0 })

            caches[2].close()
            caches[2].awaitClosedForTest()
            runCurrent()
            assertEquals(listOf(8, 8, 0), sources.map { it.allowance })
            assertEquals(listOf(8, 8, 0), sources.map { it.active })
        } finally {
            caches.forEach { it.close() }
            caches.forEach { it.awaitClosedForTest() }
        }
        assertTrue(sources.all { it.active == 0 && it.connectionLimit.subscriptionCount.value == 0 })
    }

    @Test
    fun `unused cursor and withdrawn warm have no expansion allowance`() = runTest {
        val scheduler = TransferScheduler(16, 16, BandwidthLimiter(), testScheduler.timeSource)
        val source = AdaptiveSource()
        val cache = cache(source, scheduler)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            runCurrent()
            assertEquals(0, source.allowance)
            assertEquals(0, source.active)
            cache.closeCursor(cursor)
            val warm = cache.warm(listOf(0L..65535L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(16, source.allowance)
            warm.cancelAndJoin()
            runCurrent()
            assertEquals(0, source.allowance)
            assertEquals(0, source.active)
            source.finishSecondSignature()
            runCurrent()
            assertEquals(0, source.active)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(0, source.connectionLimit.subscriptionCount.value)
        val requestsAtClose = source.requests.size
        source.connectionLimit.value = 16
        scheduler.onInteractiveActivity()
        runCurrent()
        assertEquals(requestsAtClose, source.requests.size)
        assertEquals(0, source.allowance)
    }

    @Test
    fun `durable demand expands and withdrawing it releases allowance`() = runTest {
        val scheduler = TransferScheduler(16, 16, BandwidthLimiter(), testScheduler.timeSource)
        val source = AdaptiveSource()
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) = Unit
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val cache = cache(source, scheduler, store)
        try {
            val download = cache.warm(listOf(0L..65535L), StreamRole.BACKGROUND, null, durable = true)
            runCurrent()
            assertEquals(16, source.allowance)
            assertEquals(8, source.active)
            source.finishSecondSignature()
            runCurrent()
            assertEquals(16, source.active)
            download.cancelAndJoin()
            runCurrent()
            assertEquals(0, source.allowance)
            assertEquals(0, source.active)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `blocking read restores allowance from zero and releases it without readahead`() = runTest {
        val scheduler = TransferScheduler(16, 16, BandwidthLimiter(), testScheduler.timeSource)
        val source = AdaptiveSource()
        val cache = cache(source, scheduler)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            runCurrent()
            assertEquals(0, source.connectionLimit.value)
            val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
            runCurrent()
            assertEquals(16, source.allowance)
            assertEquals(1, source.active)
            source.finishCurrentReads()
            assertEquals(1, read.await())
            runCurrent()
            assertEquals(0, source.allowance)
            assertEquals(0, source.active)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
    }
}
