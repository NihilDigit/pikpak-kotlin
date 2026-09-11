package io.github.nihildigit.pikpak

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
    )

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

    @Test
    fun `a seek cancels the blocks it left behind and restarts at one block`() = runBlocking<Unit> {
        val content = payload(8 * 1024 * 1024)
        // Long enough that nothing completes on its own during the test, so
        // what the assertions see is the schedule and not a race with it.
        val source = FakeRangeSource(content, latency = 30.seconds)
        val reader = reader(source, content.size.toLong())

        try {
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

    @Test
    fun `a block that never succeeds retires the whole reader`() = runBlocking<Unit> {
        // MAX_ATTEMPTS exhausted does not fail one read, it condemns the
        // instance: a player is expected to build a new reader rather than
        // keep asking. Nothing else pins that, and a stray `failure = null`
        // would turn it into an intermittent read error instead.
        val content = payload((unit * 4).toInt())
        val source = FakeRangeSource(content, failAlwaysAt = setOf(unit))
        val reader = reader(source, content.size.toLong(), concurrency = 1)

        try {
            assertNotNull(
                pikPakFailureOf { readAll(reader, chunk = 4096) },
                "a block that can never be fetched let the read through",
            )
            assertNotNull(
                pikPakFailureOf { reader.read(ByteArray(16), 0, 16) },
                "the reader served a read again after it had failed",
            )
            assertEquals(
                PikPakStreamReader.MAX_ATTEMPTS,
                source.requests().count { it.start == unit },
                "the dead block was not attempted exactly MAX_ATTEMPTS times",
            )
        } finally {
            reader.close()
        }
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
