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
    @Test
    fun `blockwise missing reports collapse into one ordered run`() {
        val slots = BlockCache.SlotRanges(List(8192) { it..it })
        assertEquals(listOf(0..8191), slots.ranges)
        assertEquals(8192, slots.size)
        for (index in 0 until slots.size) assertEquals(index, slots[index])
    }

    @Test
    fun `range compaction preserves first occurrence order across overlapping requests`() {
        val input = listOf(10..20, 0..5, 6..15, 30..35, 25..32, 0..40)
        val expected = input.flatMap { it.toList() }.distinct()
        val slots = BlockCache.SlotRanges(input)
        assertEquals(expected, slots.toList())
        assertEquals(expected, (0 until slots.size).map { slots[it] })
        assertEquals(expected.size, slots.size)
    }
    private fun cache(source: RangeSource, store: BlockStore? = null,
                      timeSource: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic) = BlockCache(
        source, size = 256, concurrency = 1, parentCoroutineContext = EmptyCoroutineContext,
        foregroundStreams = null, store = store, blockSize = 64, readAheadBytes = 192,
        memoryCapBytes = 512, wideBlockThresholdBytes = 1, timeSource = timeSource,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `streaming chunks and cache hits never sweep buffer ownership`() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val budget = TransportMemoryBudget(64)
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = block(channel)
        }
        val cache = BlockCache(source, 64, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            memoryBudget = budget)
        val cursor = cache.openCursor(StreamRole.BACKGROUND)
        cache.setReadAhead(cursor, 0)
        try {
            repeat(32) {
                val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
                runCurrent()
                channel.writeFully(byteArrayOf(it.toByte()))
                runCurrent()
                assertEquals(1, read.await())
                assertEquals(0L, cache.ownershipSweepsForTest(), "partial chunks retain the same owner")
            }
            channel.writeFully(ByteArray(32))
            channel.close()
            runCurrent()
            val sweeps = cache.ownershipSweepsForTest()
            repeat(32) {
                assertEquals(1, cache.read(cursor, ByteArray(1), 0, 1))
                assertEquals(sweeps, cache.ownershipSweepsForTest(), "LRU hits do not drop ownership")
            }
            assertEquals(64L, budget.usedBytes)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test
    fun `a leading slot wakes its reader before the suffix arrives`() = runBlocking<Unit> {
        val firstDelivered = CompletableDeferred<Unit>()
        val releaseSuffix = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                if (start != 64L || length < 128L) return@coroutineScope block(ByteReadChannel(ByteArray(length.toInt())))
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(64))
                    firstDelivered.complete(Unit)
                    releaseSuffix.await()
                    channel.writeFully(ByteArray((length - 64).toInt()))
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
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), concurrencyMode = ConcurrencyMode.Fixed(8))
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
            cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null, durable = true).await()
            val download = cache.warm(listOf(64L..1023L), StreamRole.BACKGROUND, null, durable = true)
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `promotion releases a mid range limiter wait without replacing the transport`() = runTest {
        val limiter = BandwidthLimiter(64, testScheduler.timeSource)
        var requests = 0
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) = Unit
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
        }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                requests++
                return block(ByteReadChannel(ByteArray(length.toInt())))
            }
        }
        val cache = BlockCache(source, 256, 1, coroutineContext, null, store,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            downloadLimiter = limiter, timeSource = testScheduler.timeSource)
        try {
            val download = cache.warm(listOf(0L..127L), StreamRole.BACKGROUND, null, durable = true)
            runCurrent()
            assertEquals(64L, cache.deliveredBytes)
            assertFalse(download.isCompleted)
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 63)
            cache.seek(cursor, 64)
            val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
            runCurrent()
            assertTrue(read.isCompleted, "promotion must bypass tokens without advancing time")
            assertEquals(1, read.await())
            download.await()
            assertEquals(1, requests, "the original multi-block transport remains owned")
            assertEquals(0L, testScheduler.currentTime)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `mid range limiter time is excluded from service samples without double counting sink wait`() = runTest {
        for (sinkWait in listOf(0.milliseconds, 400.milliseconds)) {
            val limiter = BandwidthLimiter(64, testScheduler.timeSource)
            var observed: ReadTelemetry? = null
            val store = object : DurableBlockStore {
                override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
                override suspend fun write(file: String, offset: Long, bytes: ByteArray) = Unit
                override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
            }
            val source = object : RangeSource {
                override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                             block: suspend (ByteReadChannel) -> T): T {
                    val telemetry = currentCoroutineContext()[ReadTelemetry]!!
                    observed = telemetry
                    val started = testScheduler.timeSource.markNow()
                    delay(10)
                    try {
                        return coroutineScope {
                            // Model an actual pump wait overlapping the block's token wait;
                            // a cumulative sinkWait alone establishes no interval overlap.
                            val sink = launch {
                                telemetry.beginSinkWait()
                                try { delay(sinkWait) } finally { telemetry.endSinkWait() }
                            }
                            try { block(ByteReadChannel(ByteArray(length.toInt()))) }
                            finally { sink.cancelAndJoin() }
                        }
                    } finally {
                        telemetry.attempts = 1
                        telemetry.deliveredBytes = length
                        telemetry.timeToFirstByte = 5.milliseconds
                        telemetry.sinkWait = sinkWait
                        telemetry.networkDuration = started.elapsedNow() - sinkWait
                    }
                }
            }
            val cache = BlockCache(source, 256, 1, coroutineContext, null, store,
                blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
                downloadLimiter = limiter, timeSource = testScheduler.timeSource)
            try {
                cache.warm(listOf(0L..127L), StreamRole.BACKGROUND, null, durable = true).await()
                runCurrent()
                assertEquals(10.milliseconds, observed!!.networkDuration)
            } finally {
                cache.close()
                cache.awaitClosedForTest()
            }
        }
    }

    @Test
    fun `only cached contiguous bytes make the foreground buffer comfortable`() = runBlocking<Unit> {
        val limiter = BandwidthLimiter()
        val scheduler = TransferScheduler(16, 8, limiter, concurrencyMode = ConcurrencyMode.Fixed(8))
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
            cache.setReadAhead(cursor, 128) // Two workers each claim one slot in this small window.
            assertEquals(1, withTimeout(1000) { cache.read(cursor, ByteArray(1), 0, 1) })
            withTimeout(1000) { while (cache.inFlightSlotsForTest() == 0) delay(5) }
            // 63 ready bytes are below the 4-second entry cushion; reserved slots do not count.
            assertEquals(8, scheduler.workersFor(background, true))
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
    fun `idle workers preserve issued ranges while other downloads finish`() = runTest {
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
            assertFalse(slowFinished, "the slow request is still receiving its first block")
            assertFalse(requests.any { (start, _) -> start > original.first && start in original },
                "unread transport buffers still belong to the issued request")
            assertTrue(writes.keys.any { it > original.last }, "independent ranges can finish")
            assertEquals(1, requests.count { it.first == original.first }, "the partially read block stays owned")
            download.await()
            assertEquals((0L until size step blockSize).toSet(), writes.keys)
            assertTrue(writes.values.all { it == 1 }, "each block commits exactly once")
            assertTrue(peak <= 8, "requests stay within worker capacity")
            assertEquals(size, requests.sumOf { it.second }, "wire ranges must not overlap")
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cold startup and seek do not speculate beyond their required head`() = runTest {
        val slotBytes = 256L * 1024
        val budget = TransportMemoryBudget(16L * 1024 * 1024)
        val requests = mutableListOf<Pair<Long, Long>>()
        var cancelled = 0
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                requests += start to length
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        val scheduler = TransferScheduler(4, 4, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(source, 256 * slotBytes, 4, coroutineContext, null,
            blockSize = slotBytes, readAheadBytes = 64 * slotBytes, memoryCapBytes = 80 * slotBytes,
            scheduler = scheduler, timeSource = testScheduler.timeSource, memoryBudget = budget)
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        try {
            for (head in listOf(0L, 128 * slotBytes)) {
                cache.setUrgent(cursor, true)
                cache.seek(cursor, head)
                val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
                runCurrent()
                val wave = requests.filter { it.first >= head }
                assertEquals(1, wave.size)
                assertEquals(head, wave.single().first)
                assertEquals(1L, wave.single().second / slotBytes,
                    "blocking head uses a single slot so CDN burst edges finish head immediately")
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, requests.count { it.first >= head }, "startup has no speculative suffix before its first block")
                assertFalse(read.isCompleted)
                assertEquals(slotBytes, budget.usedBytes)
                read.cancelAndJoin()
            }
            assertEquals(1, cancelled, "seek cancels the obsolete head")
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(2, cancelled)
        assertEquals(0L, budget.usedBytes)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `inactive cursor withdraws in-flight speculation but retains cached head and position`() = runTest {
        var requests = 0
        var cancellations = 0
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                requests++
                if (start > 0) {
                    try { awaitCancellation() } finally { cancellations++ }
                }
                return block(ByteReadChannel(ByteArray(length.toInt()) { 7 }))
            }
        }
        val cache = BlockCache(source, 4096, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 512, memoryCapBytes = 1024)
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        try {
            val first = async { cache.read(cursor, ByteArray(1), 0, 1) }
            runCurrent()
            assertEquals(1, first.await())
            assertEquals(2, requests)
            val position = cursor.position
            val cached = cache.cachedBytesForTest()
            cache.setDemandActive(cursor, false)
            runCurrent()
            assertEquals(1, cancellations)
            assertEquals(0, cache.inFlightSlotsForTest())
            assertEquals(position, cursor.position)
            assertEquals(cached, cache.cachedBytesForTest())
            assertEquals(2, requests)
            cache.setDemandActive(cursor, true)
            runCurrent()
            assertEquals(3, requests)
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test
    fun `expanded playback ranges stay within shared sixteen MiB ownership budget`() = runTest {
        val slotBytes = 256L * 1024
        val budget = TransportMemoryBudget(16L * 1024 * 1024)
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T =
                block(ByteReadChannel(ByteArray(length.toInt())))
        }
        val cache = BlockCache(source, 256 * slotBytes, 4, coroutineContext, null,
            blockSize = slotBytes, readAheadBytes = 32 * slotBytes, memoryCapBytes = 80 * slotBytes,
            memoryBudget = budget)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            repeat(128) {
                assertEquals(slotBytes.toInt(), cache.read(cursor, ByteArray(slotBytes.toInt()), 0, slotBytes.toInt()))
                assertTrue(budget.usedBytes <= budget.budgetBytes)
            }
            assertTrue(cache.deliveredBytes >= 128 * slotBytes)
            assertTrue(budget.peakBytes <= 16L * 1024 * 1024)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(0L, budget.usedBytes)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `dynamic worker growth wakes parked workers and shrink drains active reads`() = runTest {
        var limit = 0
        val releases = mutableListOf<CompletableDeferred<Unit>>()
        var cancellations = 0
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int,
                                         block: suspend (ByteReadChannel) -> T): T {
                val release = CompletableDeferred<Unit>()
                releases += release
                try {
                    release.await()
                    return block(ByteReadChannel(ByteArray(length.toInt())))
                } catch (e: CancellationException) { cancellations++; throw e }
            }
        }
        val cache = BlockCache(source, 4096, 4, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 2048,
            workerLimit = { limit })
        try {
            cache.warm(listOf(0L..4095L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(0, releases.size)
            limit = 1
            cache.workerLimitChanged()
            runCurrent()
            assertEquals(1, releases.size)
            limit = 99
            cache.workerLimitChanged()
            runCurrent()
            assertEquals(4, releases.size, "pre-created worker count is the hard ceiling")
            limit = 1
            cache.workerLimitChanged()
            runCurrent()
            assertEquals(0, cancellations)
            releases.take(3).forEach { it.complete(Unit) }
            runCurrent()
            assertEquals(4, releases.size, "no replacement while one admitted read remains")
            releases[3].complete(Unit)
            runCurrent()
            assertEquals(5, releases.size)
            assertEquals(0, cancellations)
        } finally {
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(1, cancellations)
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
