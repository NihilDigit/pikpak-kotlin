package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.TransportMemoryBudget
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Regression coverage for publication before the enclosing multi-slot request finishes. */
class BlockCacheStreamingTest {
    private fun cache(source: RangeSource, store: BlockStore? = null,
                      timeSource: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic) = BlockCache(
        source, size = 256, concurrency = 1, parentCoroutineContext = EmptyCoroutineContext,
        foregroundStreams = null, store = store, blockSize = 64, readAheadBytes = 192,
        memoryCapBytes = 512, wideBlockThresholdBytes = 1, timeSource = timeSource,
    )

    @Test
    fun `a leading slot wakes its reader before the suffix arrives`() = runBlocking<Unit> {
        val firstDelivered = CompletableDeferred<Unit>()
        val releaseSuffix = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                if (start != 64L || length != 128L) return@coroutineScope block(ByteReadChannel(ByteArray(length.toInt())))
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(64))
                    firstDelivered.complete(Unit)
                    releaseSuffix.await()
                    channel.writeFully(ByteArray(64))
                    channel.close()
                }
                try { block(channel) } finally { writer.cancel() }
            }
        }
        val cache = cache(source)
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        try {
            // The first cached slot makes the next claim a standalone two-slot fetch.
            cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null).await()
            assertEquals(1, cache.read(cursor, ByteArray(1), 0, 1))
            withTimeout(3000) { firstDelivered.await() }
            cache.seek(cursor, 64)
            assertEquals(64, withTimeout(1000) { cache.read(cursor, ByteArray(64), 0, 64) })
            assertFalse(releaseSuffix.isCompleted)
        } finally {
            releaseSuffix.complete(Unit)
            cache.close()
        }
    }

    @Test
    fun `zero readahead fetches only the slot being read`() = runBlocking<Unit> {
        val source = FakeRangeSource(ByteArray(256))
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter())
        val bg = scheduler.register(8) {}
        scheduler.update(bg, false, false, true)
        val cache = BlockCache(
            source, size = 256, concurrency = 1, parentCoroutineContext = EmptyCoroutineContext,
            foregroundStreams = null, blockSize = 64, readAheadBytes = 192,
            memoryCapBytes = 512, wideBlockThresholdBytes = 1, scheduler = scheduler,
        )
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        try {
            cache.setReadAhead(cursor, 0)
            assertEquals(0L, cursor.readAhead)
            assertEquals(1, withTimeout(1000) { cache.read(cursor, ByteArray(1), 0, 1) })
            delay(100)
            assertEquals(listOf(0L), source.requests().map { it.start })
            assertEquals(listOf(64L), source.requests().map { it.length })
            assertEquals(0, cache.inFlightSlotsForTest())
            // Freezing readAhead at 0 immediately releases foreground demand after read() returns.
            assertEquals(8, scheduler.workersFor(bg, true))
        } finally {
            cache.close()
            scheduler.unregister(bg)
        }
    }

    @Test
    fun `hunger truncates a wide durable tail after the next complete slot`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<Long>()
        val limiter = BandwidthLimiter()
        val scheduler = TransferScheduler(8, 8, limiter)
        val store = object : DurableBlockStore {
            val mutex = Mutex()
            val blocks = mutableMapOf<Long, ByteArray>()
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = mutex.withLock { blocks[offset] }
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) { mutex.withLock { blocks[offset] = bytes } }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                currentCoroutineContext()[ReadTelemetry]?.apply {
                    attempts = 1
                    deliveredBytes = length
                    networkDuration = 100.milliseconds
                    timeToFirstByte = 20.milliseconds
                }
                if (start != 64L) return@coroutineScope block(ByteReadChannel(ByteArray(length.toInt())))
                requested.complete(length)
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(64))
                    release.await()
                    channel.writeFully(ByteArray(64))
                    awaitCancellation()
                }
                try { block(channel) } finally { writer.cancel(); returned.complete(Unit) }
            }
        }
        val cache = BlockCache(source, 1024, 1, EmptyCoroutineContext, null, store,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, scheduler = scheduler)
        try {
            val download = cache.warm(listOf(0L..1023L), StreamRole.BACKGROUND, null, durable = true)
            assertTrue(withTimeout(3000) { requested.await() } >= 128L)
            withTimeout(1000) { while (cache.deliveredBytes < 128) delay(5) }
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            cache.seek(cursor, 128)
            val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
            withTimeout(1000) { while (!cursor.reading) delay(5) }
            delay(20)
            release.complete(Unit)
            withTimeout(1000) { returned.await() }
            assertEquals(1, withTimeout(1000) { read.await() })
            withTimeout(3000) { download.await() }
        } finally { release.complete(Unit); cache.close() }
    }

    @Test
    fun `a playback cursor bypasses a durable fetch waiting for limiter tokens`() = runBlocking<Unit> {
        val limiter = BandwidthLimiter(1)
        limiter.acquire(1000)
        val scheduler = TransferScheduler(8, 8, limiter)
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) = Unit
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = FakeRangeSource(ByteArray(256))
        val cache = BlockCache(source, 256, 1, EmptyCoroutineContext, null, store,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, scheduler = scheduler, downloadLimiter = limiter)
        try {
            val download = cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null, durable = true)
            withTimeout(3000) { while (cache.inFlightSlotsForTest() == 0) delay(5) }
            delay(50)
            assertTrue(source.requests().isEmpty())
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            assertEquals(1, withTimeout(1000) { cache.read(cursor, ByteArray(1), 0, 1) })
            withTimeout(1000) { download.await() }
        } finally { cache.close() }
    }

    @Test
    fun `only cached contiguous bytes make the foreground buffer comfortable`() = runBlocking<Unit> {
        val limiter = BandwidthLimiter()
        val scheduler = TransferScheduler(16, 8, limiter)
        val background = scheduler.register(8) {}
        scheduler.update(background, false, false, true)
        val release = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                if (start > 0) release.await()
                return block(ByteReadChannel(ByteArray(length.toInt())))
            }
        }
        val cache = BlockCache(source, 256, 2, EmptyCoroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, scheduler = scheduler, mediaBytesPerSecond = 16)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            assertEquals(1, withTimeout(1000) { cache.read(cursor, ByteArray(1), 0, 1) })
            withTimeout(1000) { while (cache.inFlightSlotsForTest() == 0) delay(5) }
            // 63 ready bytes are below the 4-second entry cushion; reserved slots do not count.
            assertEquals(2, scheduler.workersFor(background, true))
            release.complete(Unit)
            withTimeout(3000) { while (scheduler.workersFor(background, true) != 8) delay(5) }
        } finally {
            release.complete(Unit)
            cache.close()
            scheduler.unregister(background)
        }
    }

    @Test
    fun `optional queued arrays keep ownership after cache eviction`() = runBlocking<Unit> {
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) { awaitCancellation() }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val cache = BlockCache(FakeRangeSource(ByteArray(1024)), 1024, 1, EmptyCoroutineContext, null, store,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512, wideBlockThresholdBytes = 1)
        try {
            withTimeout(3000) { cache.warm(listOf(0L..511L), StreamRole.BACKGROUND, null).await() }
            // Cover every cached slot except zero. Filling slot eight evicts zero while its
            // optional store write still owns the bytes, then leaves all 512 bytes pinned.
            for (position in listOf(64L, 256L, 448L)) {
                val cursor = cache.openCursor(StreamRole.BACKGROUND)
                cursor.consuming = true
                cache.seek(cursor, position)
            }
            val more = cache.warm(listOf(512L..575L), StreamRole.BACKGROUND, null)
            delay(100)
            // Eviction does not mint new credits while an optional writer still owns the array.
            assertFalse(more.isCompleted)
            assertEquals(512L, cache.accountedBytesForTest())
        } finally { cache.close() }
    }

    @Test
    fun `a retained closed cache drops queued optional writes before releasing credits`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val store = object : BlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val cache = cache(FakeRangeSource(ByteArray(256)), store)
        try {
            withTimeout(1000) { cache.warm(listOf(0L..255L), StreamRole.BACKGROUND, null).await() }
            withTimeout(1000) { entered.await() }
            assertFalse(cache.optionalWriteQueueEmptyForTest())
            cache.close()
            withTimeout(1000) { cache.awaitClosedForTest() }
            assertEquals(0L, cache.accountedBytesForTest())
            assertTrue(cache.optionalWriteQueueEmptyForTest())
        } finally { cache.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `close retains fetch credits until transport cleanup completes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val budget = TransportMemoryBudget(64)
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        val cache = BlockCache(source, 256, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, memoryBudget = budget)
        try {
            cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null)
            entered.await()
            assertEquals(64L, budget.usedBytes)
            cache.close()
            cleaning.await()
            val closed = async { cache.awaitClosedForTest() }
            runCurrent()
            assertFalse(closed.isCompleted)
            assertEquals(64L, budget.usedBytes, "transport still owns its reservation during cleanup")
            releaseCleanup.complete(Unit)
            closed.await()
            assertEquals(0L, budget.usedBytes)
            assertEquals(0L, cache.accountedBytesForTest())
        } finally {
            releaseCleanup.complete(Unit)
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `pending durable writes retain capacity and close cancels the store`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                entered.complete(Unit)
                try { awaitCancellation() }
                catch (e: CancellationException) { cancelled.complete(Unit); throw e }
            }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val cache = cache(FakeRangeSource(ByteArray(256)), store)
        try {
            cache.warm(listOf(0L..255L), StreamRole.BACKGROUND, null, durable = true)
            withTimeout(3000) { entered.await() }
            assertTrue(cache.accountedBytesForTest() >= 64L)
            assertTrue(cache.accountedBytesForTest() <= 512)
            cache.close()
            withTimeout(1000) { cancelled.await() }
            withTimeout(1000) { while (cache.accountedBytesForTest() != 0L) delay(5) }
        } finally { cache.close() }
    }

    @Test
    fun `cancelling a suffix still persists its published durable prefix`() = runBlocking<Unit> {
        val persisted = CompletableDeferred<Long>()
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) { persisted.complete(offset) }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(64))
                    awaitCancellation()
                }
                try { block(channel) } finally { writer.cancel() }
            }
        }
        val cache = cache(source, store)
        try {
            val download = cache.warm(listOf(0L..127L), StreamRole.BACKGROUND, null, durable = true)
            withTimeout(3000) { while (cache.deliveredBytes < 64) delay(5) }
            download.cancelAndJoin()
            assertEquals(0L, withTimeout(3000) { persisted.await() })
            withTimeout(3000) { while (cache.inFlightSlotsForTest() != 0) delay(5) }
        } finally { cache.close() }
    }

    @Test
    fun `a partial head is copied before its block completes`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val prefixSent = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(byteArrayOf(1, 2, 3, 4))
                    prefixSent.complete(Unit)
                    release.await()
                    channel.writeFully(ByteArray((length - 4).toInt()) { 9 })
                    channel.close()
                }
                try { block(channel) } finally { withContext(NonCancellable) { writer.cancelAndJoin() } }
            }
        }
        val cache = cache(source)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            val bytes = ByteArray(64)
            val read = async { cache.read(cursor, bytes, 0, bytes.size) }
            withTimeout(1000) { prefixSent.await() }
            assertEquals(4, withTimeout(1000) { read.await() })
            assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.take(4))
            release.complete(Unit)
            assertEquals(60, withTimeout(1000) { cache.read(cursor, ByteArray(64), 0, 64) })
            assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.take(4))
        } finally { release.complete(Unit); cache.close() }
    }

    @Test
    fun `a consumed partial prefix stays read after publication and buffer reuse`() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                if (start != 0L) return@coroutineScope block(ByteReadChannel(ByteArray(length.toInt())))
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(byteArrayOf(1, 2, 3, 4))
                    release.await()
                    channel.writeFully(ByteArray((length - 4).toInt()))
                    channel.close()
                }
                try { block(channel) } finally { withContext(NonCancellable) { writer.cancelAndJoin() } }
            }
        }
        val budget = TransportMemoryBudget(64)
        val cache = BlockCache(source, 256, 1, EmptyCoroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, memoryBudget = budget)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            val bytes = ByteArray(64)
            assertEquals(4, withTimeout(1000) { cache.read(cursor, bytes, 0, bytes.size) })
            assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.take(4))
            release.complete(Unit)
            withTimeout(1000) { while (cache.deliveredBytes != 64L) delay(1) }
            cache.closeCursor(cursor)
            withTimeout(1000) { cache.warm(listOf(64L..191L), StreamRole.BACKGROUND, null).await() }
            // Slot zero was consumed before publication. Slot one was not consumed and
            // must still count when the same multi-slot fetch advances to slot two.
            assertEquals(64L, cache.wastedBytes)
            assertEquals(64L, budget.peakBytes)
        } finally { release.complete(Unit); cache.close() }
    }

    @Test
    fun `durable prefix reaches disk while the same HTTP suffix is stalled`() = runBlocking<Unit> {
        val suffix = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Long>()
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) { persisted.complete(offset) }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(64))
                    suffix.await()
                    channel.writeFully(ByteArray((length - 64).toInt()))
                    channel.close()
                }
                try { block(channel) } finally { withContext(NonCancellable) { writer.cancelAndJoin() } }
            }
        }
        val cache = cache(source, store)
        try {
            val warm = cache.warm(listOf(0L..127L), StreamRole.BACKGROUND, null, durable = true)
            assertEquals(0L, withTimeout(1000) { persisted.await() })
            assertFalse(warm.isCompleted)
            suffix.complete(Unit)
            withTimeout(1000) { warm.await() }
        } finally { suffix.complete(Unit); cache.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `idle workers split a slow durable tail without copying its current block`() = runTest {
        val blockSize = 64L
        val size = 65 * blockSize
        val firstTransportFinished = CompletableDeferred<Unit>()
        val writes = mutableMapOf<Long, Int>()
        val requests = mutableListOf<Pair<Long, Long>>()
        var slowRange: LongRange? = null
        var slowFinished = false
        var active = 0
        var peak = 0
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                if (offset == 0L) firstTransportFinished.await()
                assertTrue(bytes.all { it == (offset / blockSize).toByte() })
                writes[offset] = (writes[offset] ?: 0) + 1
            }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                requests += start to length
                active++
                peak = maxOf(peak, active)
                // A clean fast observation teaches long ranges. The next connection is
                // slower; scheduling must recover without knowing which host it represents.
                val slow = start != 0L && slowRange == null
                if (slow) slowRange = start until start + length
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    var offset = start
                    while (offset < start + length) {
                        val bytes = ByteArray(blockSize.toInt()) { (offset / blockSize).toByte() }
                        channel.writeFully(bytes, 0, 1)
                        delay(if (slow) 100 else 1)
                        channel.writeFully(bytes, 1, bytes.size)
                        offset += blockSize
                    }
                    channel.close()
                }
                try {
                    val result = block(channel)
                    if (start == 0L) currentCoroutineContext()[ReadTelemetry]?.apply {
                        attempts = 1
                        deliveredBytes = length
                        timeToFirstByte = 10.milliseconds
                        networkDuration = 11.milliseconds
                    }
                    result
                } finally {
                    withContext(NonCancellable) { writer.cancelAndJoin() }
                    active--
                    if (start == 0L) firstTransportFinished.complete(Unit)
                    if (slow) slowFinished = true
                }
            }
        }
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(source, size, 8, coroutineContext, null, store,
            blockSize = blockSize, readAheadBytes = 192, memoryCapBytes = 2048,
            wideBlockThresholdBytes = 1, scheduler = scheduler, timeSource = testScheduler.timeSource)
        try {
            cache.warm(listOf(0L until blockSize), StreamRole.BACKGROUND, null, durable = true).await()
            // A durable waiter completes at commit; the transport's completion sample
            // follows when the enclosing request and its cleanup have returned.
            runCurrent()
            val download = cache.warm(listOf(blockSize until size), StreamRole.BACKGROUND, null, durable = true)
            runCurrent()
            val original = slowRange ?: error("slow fetch did not start")
            assertTrue(original.last - original.first + 1 > blockSize, "expected trained range but got $original requests=$requests")
            advanceTimeBy(50)
            runCurrent()
            assertFalse(slowFinished, "rescue must happen before the original current block completes")
            assertTrue(requests.any { (start, _) -> start > original.first && start in original },
                "idle workers must reclaim a tail already requested by the slow connection")
            assertTrue(writes.keys.any { it > original.first && it in original })
            assertEquals(1, requests.count { it.first == original.first }, "the partially read block stays owned")
            download.await()
            assertEquals((0L until size step blockSize).toSet(), writes.keys)
            assertTrue(writes.values.all { it == 1 }, "each block commits exactly once")
            assertTrue(peak <= 8, "splitting must use existing worker capacity")
            assertEquals(size, cache.deliveredBytes)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `a hundred GiB warm stores ranges rather than per block claims`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) = Unit
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val size = 100L * 1024 * 1024 * 1024
        val budget = TransportMemoryBudget(1024 * 1024)
        val cache = BlockCache(source, size, 1, EmptyCoroutineContext, null, store,
            memoryBudget = budget)
        try {
            cache.warm(listOf(0L until size), StreamRole.BACKGROUND, null, durable = true)
            withTimeout(3000) { entered.await() }
            assertEquals(1, cache.demandRangeCountForTest())
            assertTrue(budget.usedBytes <= budget.budgetBytes)
        } finally {
            cache.close()
            withTimeout(1000) { while (budget.usedBytes != 0L) delay(1) }
        }
    }

    @Test
    fun `expired head deadline awaits old transport cleanup before replacement`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        var calls = 0
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                calls++
                if (calls == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
                replacement.complete(Unit)
                return block(ByteReadChannel(ByteArray(length.toInt()) { 7 }))
            }
        }
        val cache = cache(source)
        try {
            val warm = cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null)
            withTimeout(1000) { started.await() }
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            cache.reportPlaybackBuffer(cursor, kotlin.time.Duration.ZERO)
            val output = ByteArray(1)
            val read = async { cache.read(cursor, output, 0, 1) }
            withTimeout(1000) { cleaning.await() }
            assertFalse(replacement.isCompleted)
            releaseCleanup.complete(Unit)
            assertEquals(1, withTimeout(1000) { read.await() })
            assertEquals(7.toByte(), output[0])
            withTimeout(1000) { warm.await() }
        } finally { releaseCleanup.complete(Unit); cache.close() }
    }

    @Test
    fun `feedback before fetch rescues once and buffer updates shorten a blocked deadline`() = runBlocking<Unit> {
        for (shorten in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val replacement = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            var cancellations = 0
            val source = object : RangeSource {
                override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                             block: suspend (ByteReadChannel) -> T): T {
                    calls++
                    try {
                        if (calls == 1) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                        replacement.complete(Unit)
                        finish.await()
                        return block(ByteReadChannel(ByteArray(length.toInt()) { 7 }))
                    } catch (e: CancellationException) {
                        cancellations++
                        throw e
                    }
                }
            }
            val clock = kotlin.time.TestTimeSource()
            val cache = cache(source, timeSource = clock)
            try {
                val cursor = cache.openCursor(StreamRole.FOREGROUND)
                cache.setReadAhead(cursor, 0)
                cache.reportPlaybackBuffer(cursor, 10_000.milliseconds)
                val output = ByteArray(1)
                val read = async { cache.read(cursor, output, 0, 1) }
                withTimeout(1000) { started.await() }
                if (shorten) cache.reportPlaybackBuffer(cursor, kotlin.time.Duration.ZERO)
                else {
                    clock += 10_000.milliseconds
                    cache.updateMediaBytesPerSecond(16) // Wake the deadline observer after advancing the clock.
                }
                withTimeout(1000) { replacement.await() }
                // Repeated expired feedback must not cancel the fresh replacement.
                repeat(3) {
                    cache.reportPlaybackBuffer(cursor, kotlin.time.Duration.ZERO)
                    delay(10)
                }
                assertEquals(2, calls)
                assertEquals(1, cancellations)
                finish.complete(Unit)
                assertEquals(1, withTimeout(1000) { read.await() })
                assertEquals(7.toByte(), output[0])
            } finally { finish.complete(Unit); cache.close() }
        }
    }

    @Test
    fun `urgent feedback rescues a lower priority fetch after the read has started`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        var calls = 0
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                calls++
                if (calls == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
                assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, priority)
                replacement.complete(Unit)
                return block(ByteReadChannel(ByteArray(length.toInt()) { 7 }))
            }
        }
        val cache = cache(source)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            val output = ByteArray(1)
            val read = async { cache.read(cursor, output, 0, 1) }
            withTimeout(1000) { started.await() }
            cache.setUrgent(cursor, true)
            withTimeout(1000) { cleaning.await() }
            assertFalse(replacement.isCompleted)
            releaseCleanup.complete(Unit)
            assertEquals(1, withTimeout(1000) { read.await() })
            assertEquals(7.toByte(), output[0])
            assertEquals(2, calls)
        } finally { releaseCleanup.complete(Unit); cache.close() }
    }

    @Test
    fun `shared budget reclaims idle cache ownership for another file`() = runBlocking<Unit> {
        val budget = TransportMemoryBudget(64)
        fun newCache() = BlockCache(FakeRangeSource(ByteArray(256)), 256, 1,
            EmptyCoroutineContext, null, blockSize = 64, readAheadBytes = 192,
            memoryCapBytes = 512, wideBlockThresholdBytes = 1, memoryBudget = budget)
        val first = newCache()
        val second = newCache()
        try {
            withTimeout(1000) { first.warm(listOf(0L..63L), StreamRole.BACKGROUND, null).await() }
            assertEquals(64L, budget.usedBytes)
            withTimeout(1000) { second.warm(listOf(0L..63L), StreamRole.BACKGROUND, null).await() }
            assertEquals(64L, budget.peakBytes)
            assertEquals(0L, first.cachedBytesForTest())
            budget.setBudgetBytes(128)
            assertEquals(128L, budget.budgetBytes)
        } finally {
            first.close()
            second.close()
            withTimeout(1000) { while (budget.usedBytes != 0L) delay(1) }
        }
    }

    @Test
    fun `a short suffix retries from the first unpublished slot`() = runBlocking<Unit> {
        val starts = mutableListOf<Long>()
        val store = object : DurableBlockStore {
            val held = mutableSetOf<Long>()
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) { held += offset }
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                starts += start
                return block(ByteReadChannel(ByteArray(if (start == 0L) 64 else length.toInt())))
            }
        }
        val cache = cache(source, store)
        try {
            withTimeout(3000) { cache.warm(listOf(0L..127L), StreamRole.BACKGROUND, null, durable = true).await() }
            assertEquals(listOf(0L, 64L), starts)
            assertEquals(setOf(0L, 64L), store.held)
            assertEquals(0, cache.inFlightSlotsForTest())
            assertEquals(0L, cache.cachedBytesForTest())
        } finally { cache.close() }
    }
}
