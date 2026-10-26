package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.github.nihildigit.pikpak.internal.ReadTelemetry
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
    private fun cache(source: RangeSource, store: BlockStore? = null) = BlockCache(
        source, size = 256, concurrency = 1, parentCoroutineContext = EmptyCoroutineContext,
        foregroundStreams = null, store = store, blockSize = 64, readAheadBytes = 192,
        memoryCapBytes = 512, wideBlockThresholdBytes = 1,
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
                }
                if (start != 384L) return@coroutineScope block(ByteReadChannel(ByteArray(length.toInt())))
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
            assertEquals(256L, withTimeout(3000) { requested.await() })
            withTimeout(1000) { while (cache.deliveredBytes < 448) delay(5) }
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            cache.seek(cursor, 384)
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
    fun `promoting an evicted offer waits for capacity instead of exceeding the cap`() = runBlocking<Unit> {
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
            withTimeout(3000) { cache.warm(listOf(512L..575L), StreamRole.BACKGROUND, null).await() }
            val download = cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null, durable = true)
            delay(100)
            assertFalse(download.isCompleted)
            assertEquals(512L, cache.accountedBytesForTest())
        } finally { cache.close() }
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
            assertEquals(128L, cache.accountedBytesForTest())
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
