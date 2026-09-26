package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ForegroundStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The stream reader is a scheduler around range requests, so what these check
 * is the schedule: which ranges are asked for, how many at once, at what
 * priority, and what a seek does to the ones already running. Correctness of
 * the bytes is checked too, because an off-by-one in the slot arithmetic would
 * show up nowhere else until playback was already corrupt.
 */
class PikPakStreamReaderTest {
    private val unit = 64L * 1024

    private fun payload(size: Int) = ByteArray(size) { (it * 31 % 251).toByte() }

    private fun reader(
        source: FakeRangeSource,
        size: Long,
        concurrency: Int = 4,
        readAhead: Long = 1L * 1024 * 1024,
        cap: Long = 2L * 1024 * 1024,
        wideThreshold: Long = 256L * 1024,
        parentContext: CoroutineContext = EmptyCoroutineContext,
        role: StreamRole = StreamRole.FOREGROUND,
        foreground: ForegroundStreams? = null,
    ) = PikPakStreamReader(
        source = source,
        size = size,
        concurrency = concurrency,
        // No dispatcher of its own: the workers land on Dispatchers.Default
        // and the test coroutine on runBlocking's loop. Nothing here needs
        // them kept apart now that nothing blocks a thread.
        parentCoroutineContext = parentContext,
        blockSize = unit,
        readAheadBytes = readAhead,
        memoryCapBytes = cap,
        wideBlockThresholdBytes = wideThreshold,
        initialRole = role,
        foregroundStreams = foreground,
    )

    /** A read parked at the head. Until something reads there is no read-ahead window, so nothing is fetched. */
    private fun CoroutineScope.startReading(reader: PikPakStreamReader) =
        launch { runCatching { reader.read(ByteArray(1), 0, 1) } }

    private suspend fun readAll(reader: PikPakStreamReader, chunk: Int): ByteArray {
        val out = ByteArray(reader.size.toInt())
        var filled = 0
        while (filled < out.size) {
            val n = reader.read(out, filled, minOf(chunk, out.size - filled))
            if (n == -1) break
            filled += n
        }
        assertEquals(out.size, filled, "the stream ended early")
        return out
    }

    @Test
    fun `a sequential read returns the file over several requests at a time`() = runBlocking<Unit> {
        val content = payload(1024 * 1024 + 12345)
        // A latency the workers can overlap inside. With an instant source the
        // first worker finishes every block before the others are scheduled,
        // and the peak reads 1 however parallel the design is — the test would
        // be measuring the dispatcher, not the reader.
        val source = FakeRangeSource(content, latency = 20.milliseconds)
        val reader = reader(source, content.size.toLong())

        try {
            assertContentEquals(content, readAll(reader, chunk = 8 * 1024))
            assertEquals(content.size.toLong(), reader.deliveredBytes)
        } finally {
            reader.close()
        }

        val peak = source.peakConcurrency()
        assertTrue(peak > 1, "the whole point of the transport is parallel range requests, saw $peak")
        assertTrue(peak <= 4, "never above the connection budget")
    }

    @Test
    fun `the last block stops at the end of the file`() = runBlocking<Unit> {
        // A file whose length is not a multiple of the block size is where a
        // range that runs past the end would show up: the CDN answers it with
        // fewer bytes and the block would look truncated.
        val content = payload((unit * 2 + 7).toInt())
        val source = FakeRangeSource(content)
        val reader = reader(source, content.size.toLong())

        try {
            assertContentEquals(content, readAll(reader, chunk = 4096))
        } finally {
            reader.close()
        }

        val issued = source.requests()
        assertTrue(
            issued.all { it.start + it.length <= content.size },
            "a request ran past the end of the file: $issued",
        )
    }

    @Test
    fun `the block under the read position outranks read-ahead`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        val source = FakeRangeSource(content, latency = 30.seconds)
        val reader = reader(source, content.size.toLong())

        try {
            startReading(reader)
            waitUntil("the first blocks are in flight") { source.requests().size >= 4 }
            val issued = source.requests()
            val head = assertNotNull(issued.firstOrNull { it.start == 0L }, "the head block was never requested")
            val ahead = assertNotNull(issued.firstOrNull { it.start > 0L }, "no read-ahead was issued")
            assertTrue(
                head.priority > ahead.priority,
                "read-ahead at ${ahead.priority} would beat the blocked-on block at ${head.priority}",
            )
        } finally {
            reader.close()
        }
    }

    // Every background request, even the one it is blocked on, must lose to any foreground read-ahead
    @Test
    fun `a background reader asks below every foreground request`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        val source = FakeRangeSource(content, latency = 30.seconds)
        val reader = reader(source, content.size.toLong(), role = StreamRole.BACKGROUND)

        try {
            startReading(reader)
            waitUntil("the first blocks are in flight") { source.requests().size >= 4 }
            val highest = source.requests().maxOf { it.priority }
            assertTrue(
                highest < PikPakStreamReader.READ_AHEAD_PRIORITY,
                "a background request at $highest would beat foreground read-ahead",
            )
        } finally {
            reader.close()
        }
    }

    // Priority cannot take back a slot, so what bounds playback's wait is how many background requests are in flight
    @Test
    fun `a background reader keeps two requests in flight while something plays`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        val source = FakeRangeSource(content, latency = 30.seconds)
        val foreground = ForegroundStreams()
        foreground.enter()
        val reader = reader(source, content.size.toLong(), concurrency = 6, role = StreamRole.BACKGROUND, foreground = foreground)

        try {
            startReading(reader)
            waitUntil("the capped requests are in flight") { source.requests().size >= 2 }
            delay(300.milliseconds)
            val throttled = source.requests()
            assertEquals(2, throttled.size, "only the capped workers may fetch: $throttled")
            assertTrue(throttled.all { it.length == unit }, "throttled requests stay single blocks: $throttled")

            // The last foreground reader leaving lifts the cap without anyone touching this reader
            foreground.leave()
            waitUntil("the whole budget is back in use") { source.requests().size >= 6 }
        } finally {
            reader.close()
        }
    }

    @Test
    fun `promoting a background reader lifts its cap and its priority`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        val source = FakeRangeSource(content, latency = 30.seconds)
        val foreground = ForegroundStreams()
        foreground.enter()
        val reader = reader(source, content.size.toLong(), concurrency = 6, role = StreamRole.BACKGROUND, foreground = foreground)

        try {
            startReading(reader)
            waitUntil("the capped requests are in flight") { source.requests().size >= 2 }
            reader.role = StreamRole.FOREGROUND
            waitUntil("the whole budget is in use") { source.requests().size >= 6 }
            val promoted = source.requests().drop(2)
            assertTrue(
                promoted.all { it.priority >= PikPakStreamReader.READ_AHEAD_PRIORITY },
                "requests after promotion ask on the foreground band: $promoted",
            )
            assertEquals(2, foreground.active.value, "a promoted reader counts as foreground")
        } finally {
            reader.close()
        }
        assertEquals(1, foreground.active.value, "closing a foreground reader leaves the count")
    }

    @Test
    fun `a seek cancels the blocks it left behind and restarts at one block`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        // Long enough that nothing completes on its own during the test, so
        // what the assertions see is the schedule and not a race with it.
        val source = FakeRangeSource(content, latency = 30.seconds)
        val reader = reader(source, content.size.toLong())

        try {
            startReading(reader)
            waitUntil("the first blocks are in flight") { source.requests().size >= 4 }
            val before = source.requests().size

            val target = 6L * 1024 * 1024
            reader.seekTo(target)
            // Both halves, because a cancellation lands before the worker that
            // was freed by it gets back round to claimNext. Waiting only for
            // the cancellations leaves the reissue racing the assertions —
            // which held on the JVM and did not on Kotlin/Native.
            waitUntil("the abandoned blocks are cancelled and the seek reissued") {
                source.cancelled().size >= before && source.requests().size > before
            }

            assertTrue(
                source.cancelled().all { it.start < target },
                "only the blocks the new position does not want may be cancelled",
            )
            val afterSeek = source.requests().drop(before)
            assertTrue(afterSeek.isNotEmpty(), "the seek issued no request")
            assertTrue(
                afterSeek.all { it.start >= target },
                "a request behind the new position survived the seek: $afterSeek",
            )
            // Which of the freed workers records its request first is a race,
            // so the block at the seek target is asserted to be among them
            // rather than to be the first of them.
            val atTarget = afterSeek.firstOrNull { it.start == target }
            assertTrue(atTarget != null, "the block the reader is blocked on was never requested: $afterSeek")
            assertEquals(unit, atTarget.length, "a seek falls back to single-block requests")
        } finally {
            reader.close()
        }
    }

    @Test
    fun `blocks double in size once the near read-ahead is in hand`() = runBlocking<Unit> {
        val content = payload(2 * 1024 * 1024)
        val source = FakeRangeSource(content)
        // One connection makes the request order deterministic, which is what
        // lets the sizes be asserted positionally.
        val reader = reader(source, content.size.toLong(), concurrency = 1)

        try {
            val buffer = ByteArray(1)
            reader.read(buffer, 0, 1)
            waitUntil("enough blocks have been requested") { source.requests().size >= 8 }

            val lengths = source.requests().take(8).map { it.length }
            // The threshold is four blocks' worth ahead of the read position,
            // and the read position sits inside the first block, so covering
            // it takes either four blocks or five. What matters is that the
            // switch happens after the threshold and never reverts.
            assertEquals(List(4) { unit }, lengths.take(4), "the first blocks must be single: $lengths")
            assertEquals(unit * 2, lengths.last(), "blocks never doubled: $lengths")
            val firstDouble = lengths.indexOfFirst { it == unit * 2 }
            assertTrue(
                lengths.drop(firstDouble).all { it == unit * 2 },
                "a single block reappeared after the switch: $lengths",
            )
        } finally {
            reader.close()
        }
    }

    @Test
    fun `a block whose first attempt is rejected is retried and completes`() = runBlocking<Unit> {
        val content = payload((unit * 4).toInt())
        // In production the link refresh happens inside RangeReader, below this
        // class; what is exercised here is that the stream reader gives it the
        // second attempt to do it in.
        val source = FakeRangeSource(content, failFirstAttemptAt = setOf(unit))
        val reader = reader(source, content.size.toLong(), concurrency = 1)

        try {
            assertContentEquals(content, readAll(reader, chunk = 4096))
        } finally {
            reader.close()
        }

        assertEquals(2, source.requests().count { it.start == unit }, "the failed block was not retried")
    }

    // A dead block used to condemn the whole reader and its cache with it, so callers rebuilt readers and fetched everything again
    @Test
    fun `a block that never succeeds fails its reads and leaves the reader usable`() = runBlocking<Unit> {
        val content = payload((unit * 4).toInt())
        val source = FakeRangeSource(content, failAlwaysAt = setOf(unit))
        val reader = reader(source, content.size.toLong(), concurrency = 1)

        try {
            assertNotNull(
                pikPakFailureOf { readAll(reader, chunk = 4096) },
                "a block that can never be fetched let the read through",
            )
            val attemptsBefore = source.requests().count { it.start == unit }
            assertNotNull(pikPakFailureOf { reader.read(ByteArray(16), 0, 16) }, "the dead block read back as data")
            assertTrue(
                source.requests().count { it.start == unit } > attemptsBefore,
                "a later read of the failed block did not try it again",
            )

            reader.seekTo(0)
            val head = ByteArray(16)
            assertEquals(16, reader.read(head, 0, 16), "the reader stopped serving blocks that are fine")
            assertContentEquals(content.copyOfRange(0, 16), head)
        } finally {
            reader.close()
        }
    }

    // mpv opens a second connection to seek while the first is still reading; neither may cancel the other
    @Test
    fun `readers on one cache keep their windows and share what they fetched`() = runBlocking<Unit> {
        val content = payload((unit * 64).toInt())
        val source = FakeRangeSource(content, latency = 20.milliseconds)
        val cache = sharedCache(source, content.size.toLong())
        val first = PikPakStreamReader(cache, ownsCache = false, initialRole = StreamRole.FOREGROUND)
        val second = PikPakStreamReader(cache, ownsCache = false, initialRole = StreamRole.FOREGROUND)

        try {
            first.readAheadLimit = unit * 4
            second.readAheadLimit = unit * 4
            val buffer = ByteArray(unit.toInt())
            assertEquals(buffer.size, first.read(buffer, 0, buffer.size))
            second.seekTo(unit * 40)
            assertEquals(buffer.size, second.read(buffer, 0, buffer.size))
            assertContentEquals(content.copyOfRange((unit * 40).toInt(), (unit * 41).toInt()), buffer)
            waitUntil("both windows are filled") {
                first.readAheadDepthForTest() == unit * 4 && second.readAheadDepthForTest() == unit * 4
            }
            assertTrue(source.cancelled().isEmpty(), "one reader's seek cancelled the other's fetches: ${source.cancelled()}")

            // What the first reader fetched is already there for the second
            val before = source.requests().size
            second.seekTo(unit)
            assertEquals(buffer.size, second.read(buffer, 0, buffer.size))
            assertContentEquals(content.copyOfRange(unit.toInt(), (unit * 2).toInt()), buffer)
            assertTrue(source.requests().drop(before).none { it.start == unit }, "a cached block was fetched again")
        } finally {
            first.close()
            second.close()
            cache.close()
        }
    }

    // A feed gives up on the clips scrolled past; left standing, their fetches were the oldest demand and won every tie
    @Test
    fun `a withdrawn prefetch cancels the fetch only it wanted`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val source = FakeRangeSource(content, latency = 30.seconds)
        val cache = sharedCache(source, content.size.toLong(), concurrency = 1)

        try {
            val warm = cache.warm(listOf(0L until unit), StreamRole.FOREGROUND, null)
            waitUntil("the block is in flight") { source.requests().isNotEmpty() }
            warm.cancel()
            waitUntil("the fetch is cancelled") { source.cancelled().isNotEmpty() }
        } finally {
            cache.close()
        }
    }

    // Piko opens a reader per HTTP request; one the player dropped hung on a block a prefetch still wanted
    @Test
    fun `closing a reader wakes a read parked on a block others still want`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val source = FakeRangeSource(content, latency = 30.seconds)
        val cache = sharedCache(source, content.size.toLong())
        val warm = cache.warm(listOf(0L until unit), StreamRole.FOREGROUND, null)
        val reader = PikPakStreamReader(cache, ownsCache = false, initialRole = StreamRole.FOREGROUND)

        try {
            supervisorScope {
                val read = async { reader.read(ByteArray(16), 0, 16) }
                waitUntil("the block is in flight") { source.requests().isNotEmpty() }
                delay(100.milliseconds)
                reader.close()
                withTimeout(2.seconds) { assertFailsWith<PikPakException> { read.await() } }
            }
        } finally {
            warm.cancel()
            cache.close()
        }
    }

    @Test
    fun `a block store is read before the network and offered what the network delivered`() = runBlocking<Unit> {
        val content = payload((unit * 8).toInt())
        val store = MemoryBlockStore()
        store.write("file", unit * 2, content.copyOfRange((unit * 2).toInt(), (unit * 3).toInt()))
        val source = FakeRangeSource(content)
        val cache = sharedCache(source, content.size.toLong(), store = store)

        try {
            withTimeout(10.seconds) { cache.warm(listOf(0L until unit * 4), StreamRole.FOREGROUND, null).await() }
            assertTrue(source.requests().none { it.start == unit * 2 }, "a stored block was fetched: ${source.requests()}")
            waitUntil("fetched blocks reach the store") { store.offsets("file").containsAll(listOf(0L, unit, unit * 3)) }

            val reader = PikPakStreamReader(cache, ownsCache = false, initialRole = StreamRole.FOREGROUND)
            reader.seekTo(unit * 2)
            val buffer = ByteArray(unit.toInt())
            assertEquals(buffer.size, reader.read(buffer, 0, buffer.size))
            assertContentEquals(content.copyOfRange((unit * 2).toInt(), (unit * 3).toInt()), buffer)
            reader.close()
        } finally {
            cache.close()
        }
    }

    private fun sharedCache(
        source: FakeRangeSource,
        size: Long,
        concurrency: Int = 4,
        store: BlockStore? = null,
    ) = BlockCache(
        source = source,
        size = size,
        concurrency = concurrency,
        parentCoroutineContext = EmptyCoroutineContext,
        blockSize = unit,
        readAheadBytes = 1L * 1024 * 1024,
        memoryCapBytes = 2L * 1024 * 1024,
        wideBlockThresholdBytes = 256L * 1024,
        foregroundStreams = null,
        store = store,
        storeKey = "file",
    )

    private class MemoryBlockStore : BlockStore {
        private val blocks = HashMap<Pair<String, Long>, ByteArray>()
        private val lock = kotlinx.coroutines.sync.Mutex()

        override suspend fun read(file: String, offset: Long, length: Int): ByteArray? =
            lock.withLock { blocks[file to offset]?.takeIf { it.size == length } }

        override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
            lock.withLock { blocks[file to offset] = bytes }
        }

        suspend fun offsets(file: String): Set<Long> = lock.withLock { blocks.keys.filter { it.first == file }.map { it.second }.toSet() }
    }

    @Test
    fun `the cache never exceeds its cap`() = runBlocking<Unit> {
        // Weaker than it looks: a reader whose read-ahead has stalled also
        // stays under its cap. `read-ahead reaches the depth it was given`
        // is the other half.
        val cap = unit * 16
        val content = payload(4 * 1024 * 1024)
        val source = FakeRangeSource(content)
        val reader = reader(source, content.size.toLong(), concurrency = 2, readAhead = unit * 4, cap = cap)

        try {
            val buffer = ByteArray(4096)
            var read = 0L
            while (read < content.size) {
                val n = reader.read(buffer, 0, buffer.size)
                if (n == -1) break
                read += n
                val held = reader.cachedBytesForTest()
                assertTrue(held <= cap, "held $held bytes against a $cap cap")
            }
            assertEquals(content.size.toLong(), read)
        } finally {
            reader.close()
        }
    }

    @Test
    fun `read-ahead reaches the depth it was given`() = runBlocking<Unit> {
        // The cap is scaled from the shipped constants rather than picked,
        // because setting those two back to the same value is the defect this
        // test exists for: makeRoom exempts the whole read-ahead window, so a
        // cap no larger than the window leaves it nothing to evict and
        // read-ahead crawls forward one block per block consumed. The ratio is
        // what carries that into a case small enough to run in a test.
        val window = unit * 8
        val cap = window * PikPakStreamReader.DEFAULT_MEMORY_CAP_BYTES / PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES
        val content = payload((unit * 64).toInt())
        val source = FakeRangeSource(content, latency = 2.milliseconds)
        val reader = reader(source, content.size.toLong(), concurrency = 2, readAhead = window, cap = cap)

        try {
            val buffer = ByteArray(unit.toInt())
            repeat(4) { round ->
                assertEquals(buffer.size, reader.read(buffer, 0, buffer.size), "short read in round $round")
            }
            waitUntil("read-ahead covers the whole window") { reader.readAheadDepthForTest() == window }
        } finally {
            reader.close()
        }
    }

    /**
     * The measurement that says read-ahead was too deep.
     *
     * A read that waits says the window was too small, and nothing said it was
     * too large, so the depth could only be set by guessing. Read-ahead that
     * guessed wrong is indistinguishable from read-ahead that guessed right
     * until the block is dropped unread, so that is the moment to count it.
     */
    @Test
    fun `blocks evicted before anyone reads them are counted as waste`() = runBlocking<Unit> {
        val window = unit * 4
        val cap = window + unit * (2 * 1 + 1)
        val content = payload((unit * 64).toInt())
        val source = FakeRangeSource(content, latency = 2.milliseconds)
        val reader = reader(source, content.size.toLong(), concurrency = 1, readAhead = window, cap = cap)

        try {
            val buffer = ByteArray(unit.toInt())
            assertEquals(buffer.size, reader.read(buffer, 0, buffer.size), "short read at the head")
            waitUntil("read-ahead has filled the window") { reader.readAheadDepthForTest() == window }
            assertEquals(0, reader.wastedBytes, "nothing has been dropped yet")

            // Far enough that none of what was fetched is in the new window,
            // then read on: eviction only happens when a worker wants room the
            // cap will not give, so the blocks left behind are dropped by the
            // fetches that follow the seek, not by the seek itself.
            reader.seekTo(unit * 32)
            repeat(6) { round ->
                assertEquals(buffer.size, reader.read(buffer, 0, buffer.size), "short read in round $round")
            }
            waitUntil("the abandoned blocks are evicted") { reader.wastedBytes > 0 }


            // How many of the abandoned blocks get evicted depends on how much room the
            // fetches after the seek actually needed, which is the scheduler's business and
            // differs between platforms. What must hold is that the blocks left behind are
            // counted as waste rather than as anything else, in whole blocks, and never
            // more than read-ahead was holding.
            assertTrue(
                reader.wastedBytes % unit == 0L && reader.wastedBytes <= window,
                "waste is whole unread blocks, at most one window of them, saw ${reader.wastedBytes}",
            )
        } finally {
            reader.close()
        }
    }

    @Test
    fun `a fetch abandoned before its body runs releases its slots and wakes its waiters`() = runBlocking<Unit> {
        // A seek can cancel the lazy fetch job after it is registered and
        // before start() runs it, and then the fetch body never executes. The
        // window is too narrow to hit on purpose through seekTo, so the
        // cleanup is driven directly. That every other test in this file now
        // passes a completed fetch through the same call is the other half of
        // the check: the cleanup must be a no-op after a success.
        val content = payload((unit * 8).toInt())
        val source = FakeRangeSource(content, latency = 30.seconds)
        val reader = reader(source, content.size.toLong(), concurrency = 1)

        try {
            // Wait for the one worker to be parked inside its own fetch before
            // taking any count. Until then it is still racing claimNext, and
            // the slot it takes between the snapshot below and the abandon
            // makes the arithmetic wrong — which is exactly how this failed on
            // Kotlin/Native while passing on the JVM.
            startReading(reader)
            waitUntil("the worker is parked in a fetch") { source.requests().isNotEmpty() }

            val fetch = assertNotNull(reader.claimForTest(), "there was nothing to claim")
            val claimed = reader.inFlightSlotsForTest()

            reader.abandonForTest(fetch)

            assertTrue(fetch.done.isCompleted, "a reader parked on the abandoned fetch would never wake")
            assertEquals(
                claimed - fetch.slotCount,
                reader.inFlightSlotsForTest(),
                "the abandoned fetch kept its slots, so claimNext can never take them again",
            )

            // A second pass stands in for the worker's finally running over a
            // fetch some other path already released.
            reader.abandonForTest(fetch)
            assertEquals(
                claimed - fetch.slotCount,
                reader.inFlightSlotsForTest(),
                "abandoning twice is not idempotent",
            )
        } finally {
            reader.close()
        }
    }

    @Test
    fun `a position still reads correctly after seeking away and back`() = runBlocking<Unit> {
        // Seeking away cancels the fetches for the position seeked from, and
        // seeking back has to be able to claim those slots again. A leaked
        // claim shows up here as a read that never returns. The head-tail-head
        // pattern is also what mpv does when it opens a Matroska file, so this
        // is the case the LRU cache exists for.
        val content = payload((unit * 16).toInt())
        val source = FakeRangeSource(content, latency = 2.milliseconds)
        val reader = reader(source, content.size.toLong(), concurrency = 4)

        try {
            val buffer = ByteArray(1024)
            repeat(100) { round ->
                val pos = (round % 6) * unit
                reader.seekTo(content.size - unit)
                reader.seekTo(pos)
                val n = reader.read(buffer, 0, buffer.size)
                assertEquals(buffer.size, n, "short read at $pos in round $round")
                assertContentEquals(
                    content.copyOfRange(pos.toInt(), pos.toInt() + buffer.size),
                    buffer,
                    "wrong bytes at $pos in round $round",
                )
            }
        } finally {
            reader.close()
        }
    }

    // A feed warms dozens of files this way; read-ahead from offset zero on each would take the account's budget
    @Test
    fun `an unread reader fetches only what it is asked to prefetch`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        val source = FakeRangeSource(content, latency = 2.milliseconds)
        val reader = reader(source, content.size.toLong())

        try {
            delay(200.milliseconds)
            assertEquals(emptyList(), source.requests(), "a reader nobody reads from fetched on its own")

            val tail = content.size - 2 * unit until content.size.toLong()
            withTimeout(10.seconds) { reader.prefetch(listOf(tail)).await() }
            delay(200.milliseconds)
            val issued = source.requests()
            assertTrue(issued.isNotEmpty() && issued.all { it.start >= tail.first }, "fetched outside the prefetch: $issued")
            assertEquals(0L, reader.position, "a prefetch moved the read position")

            val buffer = ByteArray(1024)
            reader.seekTo(tail.first)
            assertEquals(buffer.size, reader.read(buffer, 0, buffer.size))
            assertContentEquals(content.copyOfRange(tail.first.toInt(), tail.first.toInt() + buffer.size), buffer)
        } finally {
            reader.close()
        }
    }

    // mpv reads the head while the tail index is on its way; the head's seeks must not take the tail down
    @Test
    fun `a seek does not cancel a prefetch`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        val source = FakeRangeSource(content, latency = 300.milliseconds)
        val reader = reader(source, content.size.toLong())

        try {
            startReading(reader)
            val tail = content.size - unit until content.size.toLong()
            val pending = reader.prefetch(listOf(tail), priority = PikPakStreamReader.BLOCKING_PRIORITY + 1)
            waitUntil("the tail is in flight") { source.requests().any { it.start == tail.first } }
            reader.seekTo(unit * 20)

            withTimeout(10.seconds) { pending.await() }
            assertTrue(source.cancelled().none { it.start == tail.first }, "the seek cancelled the prefetch")
            val tailRequest = source.requests().first { it.start == tail.first }
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY + 1, tailRequest.priority, "the prefetch lost the priority it asked for")
        } finally {
            reader.close()
        }
    }

    // assertFailsWith takes a non-suspending block, which none of these are.
    private suspend fun pikPakFailureOf(body: suspend () -> Unit): PikPakException? =
        try {
            body()
            null
        } catch (e: PikPakException) {
            e
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

    /**
     * The workers can stop without anyone calling close: the caller cancels
     * the scope it handed in. A read parked on a fetch that will now never
     * land has to surface rather than hang — and no reader code runs at that
     * moment to notice, which is why the job's completion has to do it.
     */
    @Test
    fun `cancelling the parent job wakes a parked read`() = runBlocking<Unit> {
        val content = payload(512 * 1024)
        val source = FakeRangeSource(content, latency = 30.seconds)
        val parent = Job()
        val reader = reader(source, content.size.toLong(), parentContext = parent)

        // supervisorScope, not plain async: a failing async cancels its parent
        // before await() can hand the exception over, and the assertion would
        // never see it.
        supervisorScope {
            val read = async { reader.read(ByteArray(4096), 0, 4096) }
            waitUntil("a request is in flight") { source.requests().isNotEmpty() }

            parent.cancel()

            assertFailsWith<PikPakException> { read.await() }
        }
    }
}
