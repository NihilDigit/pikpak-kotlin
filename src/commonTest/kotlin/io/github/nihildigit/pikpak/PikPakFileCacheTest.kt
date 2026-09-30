package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Downloads into a [DurableBlockStore] through the same cache the streams read from.
 *
 * What is worth asserting is what reaches the network and what reaches the store: a download
 * that re-fetches what a stream already has, or what the store already holds, costs a free
 * account its daily allowance; one that completes before its blocks are written leaves a
 * caller trusting a file that is not there.
 */
class PikPakFileCacheTest {
    private val unit = 64L * 1024

    private fun payload(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    private fun cache(
        source: FakeRangeSource,
        size: Long,
        store: BlockStore? = null,
        concurrency: Int = 4,
        foreground: ForegroundStreams? = null,
        foregroundIdle: Duration = BlockCache.FOREGROUND_IDLE,
        memoryCapBytes: Long = 2L * 1024 * 1024,
    ) = BlockCache(
        source = source,
        size = size,
        concurrency = concurrency,
        parentCoroutineContext = EmptyCoroutineContext,
        foregroundStreams = foreground,
        store = store,
        storeKey = "file",
        blockSize = unit,
        readAheadBytes = unit * 4,
        memoryCapBytes = memoryCapBytes,
        wideBlockThresholdBytes = unit * 2,
        foregroundIdle = foregroundIdle,
    )

    /** Holds blocks by offset, answers [missing] from them, and can hold a write back or fail it. */
    private inner class DurableMemoryStore(
        private val failWritesAt: Set<Long> = emptySet(),
    ) : DurableBlockStore {
        private val lock = Mutex()
        private val blocks = HashMap<Long, ByteArray>()
        val writeGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var gate: CompletableDeferred<Unit> = writeGate

        override suspend fun read(file: String, offset: Long, length: Int): ByteArray? =
            lock.withLock { blocks[offset]?.takeIf { it.size == length } }

        override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
            gate.await()
            if (offset in failWritesAt) throw IllegalStateException("disk full at $offset")
            lock.withLock { blocks[offset] = bytes }
        }

        override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = lock.withLock {
            ranges.flatMap { range ->
                (range.first / unit..range.last / unit)
                    .filter { it * unit !in blocks }
                    .map { block -> maxOf(range.first, block * unit)..minOf(range.last, (block + 1) * unit - 1) }
            }
        }

        suspend fun offsets(): Set<Long> = lock.withLock { blocks.keys.toSet() }

        suspend fun content(size: Int): ByteArray = lock.withLock {
            val out = ByteArray(size)
            for ((offset, bytes) in blocks) bytes.copyInto(out, offset.toInt())
            out
        }

        suspend fun put(offset: Long, bytes: ByteArray) = lock.withLock { blocks[offset] = bytes }
    }

    @Test
    fun `a download writes every block and skips what the store holds`() = runBlocking<Unit> {
        val content = payload((unit * 8 + 100).toInt())
        val store = DurableMemoryStore()
        store.put(unit * 2, content.copyOfRange((unit * 2).toInt(), (unit * 3).toInt()))
        val source = FakeRangeSource(content)
        val cache = cache(source, content.size.toLong(), store)
        try {
            withTimeout(10.seconds) { PikPakFileCache(cache).download().await() }
            assertContentEquals(content, store.content(content.size))
            assertTrue(source.requests().none { it.start == unit * 2 }, "a held block was fetched: ${source.requests()}")
        } finally {
            cache.close()
        }
    }

    @Test
    fun `a download is not done until its blocks are written`() = runBlocking<Unit> {
        val content = payload((unit * 4).toInt())
        val store = DurableMemoryStore()
        store.gate = CompletableDeferred()
        val source = FakeRangeSource(content)
        val cache = cache(source, content.size.toLong(), store)
        try {
            val job = PikPakFileCache(cache).download()
            waitUntil("every block is fetched") { source.requests().sumOf { it.length } >= content.size }
            delay(200.milliseconds)
            assertFalse(job.isCompleted, "the download completed while its writes were held back")
            store.gate.complete(Unit)
            withTimeout(10.seconds) { job.await() }
            assertEquals((0 until 4).map { it * unit }.toSet(), store.offsets())
        } finally {
            cache.close()
        }
    }

    @Test
    fun `a download fetches in the order it was given`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val source = FakeRangeSource(content, latency = 10.milliseconds)
        // One worker, so the order of the requests is the order of the claims
        val cache = cache(source, content.size.toLong(), DurableMemoryStore(), concurrency = 1)
        try {
            val tail = unit * 6 until unit * 8
            val head = 0L until unit * 2
            withTimeout(10.seconds) { PikPakFileCache(cache).download(listOf(tail, head)).await() }
            val starts = source.requests().map { it.start }
            assertEquals(unit * 6, starts.first(), "the first range asked for was not fetched first: $starts")
            assertTrue(starts.indexOf(0L) > starts.indexOf(unit * 6), "the head went before the tail: $starts")
        } finally {
            cache.close()
        }
    }

    @Test
    fun `a download widens to two blocks while nothing plays`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val source = FakeRangeSource(content)
        val cache = cache(source, content.size.toLong(), DurableMemoryStore(), concurrency = 1)
        try {
            withTimeout(10.seconds) { PikPakFileCache(cache).download().await() }
            assertTrue(source.requests().all { it.length == unit * 2 }, "requests were not widened: ${source.requests()}")
        } finally {
            cache.close()
        }
    }

    @Test
    fun `what a stream fetched is written from memory not fetched again`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val store = DurableMemoryStore()
        val source = FakeRangeSource(content)
        val cache = cache(source, content.size.toLong(), store)
        val files = PikPakFileCache(cache)
        val stream = files.openStream()
        try {
            val buffer = ByteArray(unit.toInt())
            assertEquals(buffer.size, stream.read(buffer, 0, buffer.size))
            waitUntil("the window is filled") { stream.readAheadDepthForTest() == unit * 4 }
            val fetched = source.requests().map { it.start }.toSet()
            val before = source.requests().size

            withTimeout(10.seconds) { files.download().await() }

            val again = source.requests().drop(before).filter { it.start in fetched }
            assertTrue(again.isEmpty(), "blocks the stream had were fetched again: $again")
            assertContentEquals(content, store.content(content.size))
        } finally {
            stream.close()
            cache.close()
        }
    }

    @Test
    fun `what a stream fetched and evicted before its write landed is not fetched again`() = runBlocking<Unit> {
        val content = payload((unit * 32).toInt())
        val store = DurableMemoryStore()
        // The disk is slow: every offer the stream makes is still queued when the download starts
        store.gate = CompletableDeferred()
        val source = FakeRangeSource(content)
        // The least memory one worker allows, so a seek evicts the window it leaves behind
        val cache = cache(source, content.size.toLong(), store, concurrency = 1, memoryCapBytes = unit * 7)
        val files = PikPakFileCache(cache)
        val stream = files.openStream()
        try {
            val buffer = ByteArray(16)
            assertEquals(buffer.size, stream.read(buffer, 0, buffer.size))
            waitUntil("the window is filled") { stream.readAheadDepthForTest() == unit * 4 }
            val played = source.requests().map { it.start / unit }.toSet()
            stream.seekTo(unit * 20)
            assertEquals(buffer.size, stream.read(buffer, 0, buffer.size))
            // Seven blocks of memory cannot hold both windows, so some of the first are gone
            waitUntil("the second window is filled") { stream.readAheadDepthForTest() == unit * 4 }
            val before = source.requests().size

            val download = files.download()
            delay(200.milliseconds)
            store.gate.complete(Unit)
            withTimeout(10.seconds) { download.await() }

            val again = source.requests().drop(before).filter { it.start / unit in played }
            assertTrue(again.isEmpty(), "blocks queued for the store were fetched again: $again")
            assertContentEquals(content, store.content(content.size))
        } finally {
            stream.close()
            cache.close()
        }
    }

    @Test
    fun `what a download wrote is read back without the network or memory`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val store = DurableMemoryStore()
        val source = FakeRangeSource(content)
        val cache = cache(source, content.size.toLong(), store)
        val files = PikPakFileCache(cache)
        try {
            withTimeout(10.seconds) { files.download().await() }
            assertEquals(0L, cache.cachedBytesForTest(), "a download filled the memory the streams read from")
            val before = source.requests().size

            files.openStream().use { stream ->
                stream.seekTo(unit * 5)
                val buffer = ByteArray(unit.toInt())
                assertEquals(buffer.size, stream.read(buffer, 0, buffer.size))
                assertContentEquals(content.copyOfRange((unit * 5).toInt(), (unit * 6).toInt()), buffer)
            }
            assertEquals(before, source.requests().size, "a stream fetched what the download had written")
        } finally {
            cache.close()
        }
    }

    @Test
    fun `a failed write fails the download and nothing else`() = runBlocking<Unit> {
        val content = payload((unit * 4).toInt())
        val store = DurableMemoryStore(failWritesAt = setOf(unit))
        val source = FakeRangeSource(content)
        val cache = cache(source, content.size.toLong(), store)
        val files = PikPakFileCache(cache)
        val stream = files.openStream()
        try {
            val buffer = ByteArray(unit.toInt())
            assertEquals(buffer.size, stream.read(buffer, 0, buffer.size))
            withTimeout(10.seconds) { assertFailsWith<PikPakException> { files.download().await() } }

            stream.seekTo(unit)
            assertEquals(buffer.size, stream.read(buffer, 0, buffer.size), "a reader lost a block whose write failed")
            assertContentEquals(content.copyOfRange(unit.toInt(), (unit * 2).toInt()), buffer)
        } finally {
            stream.close()
            cache.close()
        }
    }

    @Test
    fun `a download again after a failure fetches only what is missing`() = runBlocking<Unit> {
        val content = payload((unit * 6).toInt())
        val store = DurableMemoryStore()
        val source = FakeRangeSource(content, failAlwaysAt = setOf(unit * 2))
        val cache = cache(source, content.size.toLong(), store, concurrency = 1)
        val files = PikPakFileCache(cache)
        try {
            withTimeout(10.seconds) { assertFailsWith<PikPakException> { files.download().await() } }
        } finally {
            cache.close()
        }
        val held = store.offsets()
        assertTrue(held.isNotEmpty(), "nothing was written before the failure")

        val retry = FakeRangeSource(content)
        val second = cache(retry, content.size.toLong(), store)
        try {
            withTimeout(10.seconds) { PikPakFileCache(second).download().await() }
            assertTrue(retry.requests().none { it.start in held }, "held blocks were fetched again: ${retry.requests()}")
            assertContentEquals(content, store.content(content.size))
        } finally {
            second.close()
        }
    }

    @Test
    fun `a download needs a durable store`() = runBlocking<Unit> {
        val content = payload(unit.toInt())
        val cache = cache(FakeRangeSource(content), content.size.toLong())
        try {
            assertFailsWith<IllegalStateException> { PikPakFileCache(cache).download().await() }
        } finally {
            cache.close()
        }
    }

    @Test
    fun `a foreground stream that stops reading gives the account back`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val foreground = ForegroundStreams()
        val cache = cache(FakeRangeSource(content), content.size.toLong(), foreground = foreground, foregroundIdle = 300.milliseconds)
        val stream = PikPakFileCache(cache).openStream()
        try {
            val buffer = ByteArray(1024)
            stream.read(buffer, 0, buffer.size)
            assertEquals(1, foreground.active.value, "a reading stream is not foreground")

            waitUntil("the idle stream stops counting") { foreground.active.value == 0 }

            stream.read(buffer, 0, buffer.size)
            assertEquals(1, foreground.active.value, "a stream reading again is not foreground again")
        } finally {
            stream.close()
            cache.close()
        }
        assertEquals(0, foreground.active.value)
    }

    @Test
    fun `a paused foreground stream no longer throttles a download`() = runBlocking<Unit> {
        val content = payload((unit * 16).toInt())
        val foreground = ForegroundStreams()
        val playing = cache(FakeRangeSource(content), content.size.toLong(), foreground = foreground, foregroundIdle = 300.milliseconds)
        val source = FakeRangeSource(content, latency = 50.milliseconds)
        val downloading = cache(source, content.size.toLong(), DurableMemoryStore(), concurrency = 4, foreground = foreground)
        val stream = PikPakFileCache(playing).openStream()
        try {
            stream.read(ByteArray(16), 0, 16)
            waitUntil("the player goes idle") { foreground.active.value == 0 }
            withTimeout(10.seconds) { PikPakFileCache(downloading).download().await() }
            assertTrue(source.peakConcurrency() > PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND, "the download stayed throttled")
        } finally {
            stream.close()
            playing.close()
            downloading.close()
        }
    }

    @Test
    fun `a download on the file being played keeps to two single-block requests`() = runBlocking<Unit> {
        val content = payload((unit * 32).toInt())
        val source = FakeRangeSource(content, latency = 30.milliseconds)
        val cache = cache(source, content.size.toLong(), DurableMemoryStore(), concurrency = 8)
        val files = PikPakFileCache(cache)
        val stream = files.openStream()
        try {
            coroutineScope {
                // A player reading steadily through the whole download
                val player = launch {
                    val buffer = ByteArray(4096)
                    while (isActive) {
                        if (stream.read(buffer, 0, buffer.size) == -1) stream.seekTo(0)
                        delay(5.milliseconds)
                    }
                }
                withTimeout(20.seconds) { files.download().await() }
                player.cancel()
            }
            val downloads = source.requests().filter { it.priority == PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY }
            assertTrue(downloads.isNotEmpty(), "the download fetched nothing itself")
            assertTrue(downloads.all { it.length == unit }, "a download widened while the file played: $downloads")
            assertTrue(
                source.peakConcurrency(PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY) <= PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND,
                "the download held more than its share of the file's connections",
            )
        } finally {
            stream.close()
            cache.close()
        }
    }

    private suspend fun waitUntil(what: String, timeoutMillis: Long = 20_000, condition: suspend () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (deadline.elapsedNow().inWholeMilliseconds > timeoutMillis) {
                throw AssertionError("timed out waiting until $what")
            }
            delay(10.milliseconds)
        }
    }
}
