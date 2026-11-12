package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.ActiveResponseGuard
import io.github.nihildigit.pikpak.internal.DemandWithdrawnException
import io.github.nihildigit.pikpak.internal.ForegroundStreams
import io.github.nihildigit.pikpak.internal.TransferScheduler
import io.github.nihildigit.pikpak.internal.TransportMemoryBudget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPlanAndOwnershipTest {
    private val blockSize = 256L * 1024

    @Test
    fun `Scenario A1 - short-read disconnect keeps single-block FinishInFlight and next short-read resumes with zero HTTP restarts`() = runTest {
        val fileSize = 4 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 17 % 251).toByte() }
        val starts = mutableListOf<Pair<Long, Long>>()
        val releaseRestOfBlock0 = CompletableDeferred<Unit>()
        var block0Cancelled = false

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                starts += start to length
                if (start == 0L) {
                    val ch = ByteChannel(autoFlush = true)
                    val writer = launch {
                        // Deliver first 32 KiB immediately, then hold remainder of the 256 KiB block.
                        ch.writeFully(payload, 0, 32 * 1024)
                        releaseRestOfBlock0.await()
                        ch.writeFully(payload, 32 * 1024, length.toInt())
                        ch.flushAndClose()
                    }
                    try {
                        block(ch)
                    } finally {
                        if (!releaseRestOfBlock0.isCompleted) block0Cancelled = true
                        writer.cancel()
                    }
                } else {
                    block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                }
            }
        }

        val scheduler = TransferScheduler(4, 4, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 4,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 4 * blockSize,
            memoryCapBytes = 16 * blockSize,
            scheduler = scheduler,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(ConsumerIntent(epoch = 0L, service = ServiceIntent.WAITING))
        try {
            // HTTP Range #1: bytes=0-8191 (8 KiB short probe), then client disconnects.
            val t1 = cache.beginRead(owner, startOffset = 0L, endExclusive = 8192L)
            val buf1 = ByteArray(8192)
            assertEquals(8192, t1.readSome(buf1, 0, buf1.size))
            assertContentEquals(payload.copyOfRange(0, 8192), buf1)
            t1.endRead(allowFinishInFlight = true)
            runCurrent()

            assertFalse(block0Cancelled, "single in-flight 256 KiB block transitions to FinishInFlight instead of self-cancelling")
            assertEquals(listOf(0L to blockSize), starts, "strictly 1 single-block HTTP request issued, 0 extension to block 1")
            val planAfterDisconnect = cache.planForTest()
            assertTrue(planAfterDisconnect.required.isEmpty())
            assertTrue(planAfterDisconnect.lookAhead.isEmpty())
            assertEquals(1, planAfterDisconnect.finishInFlight.size)
            assertEquals(0, planAfterDisconnect.finishInFlight.single().slot)
            assertEquals(BlockCache.FINISH_IN_FLIGHT_PRIORITY, planAfterDisconnect.finishInFlight.single().priority)

            // HTTP Range #2: bytes=8192-24575 (16 KiB) arrives immediately and reads from buffered prefix with 0 new HTTP requests!
            val t2 = cache.beginRead(owner, startOffset = 8192L, endExclusive = 24576L)
            val buf2 = ByteArray(16384)
            assertEquals(16384, t2.readSome(buf2, 0, buf2.size))
            assertContentEquals(payload.copyOfRange(8192, 24576), buf2)
            assertEquals(listOf(0L to blockSize), starts, "second short-read reused in-flight prefix with 0 HTTP restarts")
            t2.endRead(allowFinishInFlight = true)
            runCurrent()

            // Let block 0 finish naturally; verify it commits 256 KiB and never extends to block 1.
            releaseRestOfBlock0.complete(Unit)
            runCurrent()
            assertEquals(blockSize, cache.cachedBytesForTest())
            assertEquals(listOf(0L to blockSize), starts, "FinishInFlight never extends to the next block")
            assertEquals(0, cache.inFlightSlotsForTest())
        } finally {
            releaseRestOfBlock0.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Scenario A2 - FinishInFlight issues zero retries on mid-block error and preserves partial prefix`() = runTest {
        val fileSize = 2 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 29 % 251).toByte() }
        val starts = mutableListOf<Long>()
        val failRemainder = CompletableDeferred<Unit>()

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                starts += start
                if (starts.size == 1) {
                    val ch = ByteChannel(autoFlush = true)
                    val writer = launch {
                        ch.writeFully(payload, 0, 16 * 1024)
                        failRemainder.await()
                        ch.cancel(PikPakException(-1, "simulated mid-block reset"))
                    }
                    try {
                        block(ch)
                    } finally {
                        writer.cancel()
                    }
                } else {
                    block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                }
            }
        }

        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 2,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(ConsumerIntent(service = ServiceIntent.WAITING, bufferBudget = BufferBudget.NONE))
        try {
            val t1 = cache.beginRead(owner, 0L, 8192L)
            val first8k = ByteArray(8192)
            assertEquals(8192, t1.readSome(first8k, 0, first8k.size))
            t1.endRead(allowFinishInFlight = true)
            runCurrent()

            // Trigger network failure while in FinishInFlight: must NOT retry and must preserve 16 KiB partial!
            failRemainder.complete(Unit)
            runCurrent()
            assertEquals(listOf(0L), starts, "FinishInFlight must perform zero retries on failure")
            assertEquals(16 * 1024, cache.partialBytesForTest(0), "16 KiB valid prefix preserved in partials")

            // Next Required read at 8192 serves 8192..16384 from partials and resumes HTTP from exact byte 16384!
            val t2 = cache.beginRead(owner, 8192L, 32768L)
            val next24k = ByteArray(24576)
            var filled = 0
            while (filled < next24k.size) {
                val n = t2.readSome(next24k, filled, next24k.size - filled)
                assertTrue(n > 0)
                filled += n
            }
            assertContentEquals(payload.copyOfRange(8192, 32768), next24k)
            assertEquals(listOf(0L, 16384L), starts, "resumed cleanly from preserved partial offset 16384")
            t2.endRead(allowFinishInFlight = false)
        } finally {
            failRemainder.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Scenario A3 - single-worker high-priority Required preempts FinishInFlight in 0ms while preserving partials`() = runTest {
        val fileSize = 8 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 13 % 251).toByte() }
        val starts = mutableListOf<Long>()
        var fifCancelledAtMs: Long? = null

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                starts += start
                if (start == 0L && starts.count { it == 0L } == 1) {
                    val ch = ByteChannel(autoFlush = true)
                    val writer = launch {
                        ch.writeFully(payload, 0, 32 * 1024)
                        awaitCancellation()
                    }
                    try {
                        block(ch)
                    } finally {
                        fifCancelledAtMs = testScheduler.currentTime
                        writer.cancel()
                    }
                } else {
                    block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                }
            }
        }

        val scheduler = TransferScheduler(1, 1, BandwidthLimiter(), testScheduler.timeSource, concurrencyMode = ConcurrencyMode.Serial)
        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 1, // Strict single-worker!
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = scheduler,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(ConsumerIntent(service = ServiceIntent.WAITING))
        try {
            val t1 = cache.beginRead(owner, 0L, 8192L)
            assertEquals(8192, t1.readSome(ByteArray(8192), 0, 8192))
            t1.endRead(allowFinishInFlight = true)
            runCurrent()
            assertEquals(1, cache.planForTest().finishInFlight.size)

            advanceTimeBy(50)
            val preemptStartMs = testScheduler.currentTime
            val jumpOffset = 5 * blockSize
            val t2 = cache.beginRead(owner, jumpOffset, jumpOffset + 4096L)
            val jumpBuf = ByteArray(4096)
            val readJob = async { t2.readSome(jumpBuf, 0, jumpBuf.size) }
            runCurrent()

            assertTrue(readJob.isCompleted, "single worker must preempt FinishInFlight immediately with 0ms grace")
            assertEquals(4096, readJob.await())
            assertEquals(preemptStartMs, fifCancelledAtMs, "FinishInFlight cancelled at exact 0ms virtual delta")
            assertEquals(0L, testScheduler.currentTime - preemptStartMs)
            assertContentEquals(payload.copyOfRange(jumpOffset.toInt(), (jumpOffset + 4096).toInt()), jumpBuf)
            assertEquals(32 * 1024, cache.partialBytesForTest(0), "32 KiB partial prefix retained across preemption")
            t2.endRead(allowFinishInFlight = false)
        } finally {
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Scenario A4 - withdrawing or closing one owner cancels its exclusive claims in 0ms without disturbing same-block shared owner or download`() = runTest {
        val fileSize = 12 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 7 % 251).toByte() }
        val cancelledOffsets = mutableListOf<Long>()
        val holdRestOfSlot0 = CompletableDeferred<Unit>()
        val holdOwner1ExclusiveSlot = CompletableDeferred<Unit>()
        val holdOwner2Slot = CompletableDeferred<Unit>()
        var startsAt0 = 0

        val store = object : DurableBlockStore {
            val lock = Mutex()
            val written = mutableSetOf<Long>()
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = ranges
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                lock.withLock { written += offset }
            }
        }

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                var completed = false
                try {
                    when (start) {
                        0L -> {
                            startsAt0++
                            val ch = ByteChannel(autoFlush = true)
                            val writer = launch {
                                // Deliver 16 KiB into shared slot 0, then stall remainder of slot 0.
                                ch.writeFully(payload, 0, 16 * 1024)
                                holdRestOfSlot0.await()
                                ch.writeFully(payload, 16 * 1024, length.toInt())
                                ch.flushAndClose()
                            }
                            try {
                                val res = block(ch)
                                completed = true
                                res
                            } finally {
                                writer.cancel()
                            }
                        }
                        6 * blockSize -> {
                            holdOwner1ExclusiveSlot.await()
                            val res = block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                            completed = true
                            res
                        }
                        4 * blockSize -> {
                            holdOwner2Slot.await()
                            val res = block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                            completed = true
                            res
                        }
                        else -> {
                            val res = block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                            completed = true
                            res
                        }
                    }
                } finally {
                    if (!completed) cancelledOffsets += start
                }
            }
        }

        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 8,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            store = store,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 20 * blockSize,
            timeSource = testScheduler.timeSource,
        )
        val owner1 = TransferDemand(ConsumerIntent(service = ServiceIntent.WAITING, bufferBudget = BufferBudget(maxReadAheadBytes = blockSize)))
        val owner2 = TransferDemand(ConsumerIntent(service = ServiceIntent.PLAYING, bufferBudget = BufferBudget(maxReadAheadBytes = blockSize)))
        try {
            // Durable download shares slot 0 AND downloads 8..10.
            val download = cache.warm(
                listOf(0L until blockSize, 8 * blockSize until 10 * blockSize),
                StreamRole.BACKGROUND,
                null,
                durable = true,
            )
            // Both owner1 and owner2 contend on the SAME partially-received slot 0 (16 KiB buffered, waiting at 16..20 KiB).
            val t1Shared = cache.beginRead(owner1, 0L, 20 * 1024L)
            assertEquals(16 * 1024, t1Shared.readSome(ByteArray(16 * 1024), 0, 16 * 1024))
            val r1Shared = async { runCatching { t1Shared.readSome(ByteArray(4096), 0, 4096) } }

            val t2Shared = cache.beginRead(owner2, 16 * 1024L, 20 * 1024L)
            val sharedOut = ByteArray(4096)
            val r2Shared = async { t2Shared.readSome(sharedOut, 0, sharedOut.size) }

            // owner1 also holds an exclusive stalled fetch at 6 * blockSize; owner2 holds 4 * blockSize.
            val t1Exclusive = cache.beginRead(owner1, 6 * blockSize)
            val r1Exclusive = async { runCatching { t1Exclusive.readSome(ByteArray(64), 0, 64) } }
            val t2Other = cache.beginRead(owner2, 4 * blockSize)
            val r2Other = async { t2Other.readSome(ByteArray(64), 0, 64) }
            runCurrent()

            // Close owner1: withdraws owner1 in 0ms, cancels ONLY owner1's exclusive fetch (6 * blockSize),
            // and leaves shared partially-received slot 0 (0L), owner2 (4 * blockSize), and durable download untouched!
            val closeAt = testScheduler.currentTime
            owner1.close()
            runCurrent()
            assertEquals(closeAt, testScheduler.currentTime)
            assertTrue(r1Shared.await().isFailure)
            assertTrue(r1Exclusive.await().isFailure)
            assertEquals(
                listOf(6 * blockSize),
                cancelledOffsets,
                "only owner1's exclusive fetch cancelled; shared slot 0 & owner2 & download untouched",
            )

            holdRestOfSlot0.complete(Unit)
            holdOwner2Slot.complete(Unit)
            runCurrent()
            assertEquals(4096, r2Shared.await())
            assertContentEquals(payload.copyOfRange(16 * 1024, 20 * 1024), sharedOut)
            assertEquals(64, r2Other.await())
            download.await()
            assertEquals(1, startsAt0, "shared slot 0 completed on its single in-flight fetch without restart")
            assertTrue(store.written.containsAll(setOf(0L, 8 * blockSize, 9 * blockSize)), "durable download blocks persisted including shared slot 0")
            assertTrue((4 * blockSize) in store.written, "shared stream block also opportunistically persisted")
            assertFalse((6 * blockSize) in store.written, "cancelled owner1 exclusive block never persisted")
            assertFalse(cancelledOffsets.contains(0L))
            assertFalse(cancelledOffsets.contains(4 * blockSize))
            t2Shared.endRead(allowFinishInFlight = false)
            t2Other.endRead(allowFinishInFlight = false)
        } finally {
            holdRestOfSlot0.complete(Unit)
            holdOwner1ExclusiveSlot.complete(Unit)
            holdOwner2Slot.complete(Unit)
            owner1.close()
            owner2.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Scenario B - cold head and tail moov probes stay Required-only, WAITING sequential read grows boundedly, PLAYING aggregates budget across N tickets, and hr-seek epoch bump preempts in 0ms`() = runTest {
        val fileSize = 64 * blockSize // 16 MiB
        val payload = ByteArray(fileSize.toInt()) { (it * 23 % 251).toByte() }
        val issued = mutableListOf<Triple<Long, Long, Int>>()

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T {
                issued += Triple(start, length, priority)
                return block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
            }
        }

        val budgetCap = 12L * 1024 * 1024
        val memoryBudget = TransportMemoryBudget(budgetCap)
        val scheduler = TransferScheduler(4, 4, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 4,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 16 * blockSize,
            memoryCapBytes = 32 * blockSize,
            scheduler = scheduler,
            memoryBudget = memoryBudget,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 8 * blockSize),
            ),
        )
        try {
            // 1. Cold open in WAITING: head probe (0..16 KiB) + tail moov probe (last 64 KiB) keep 2 independent lanes and 0 LookAhead!
            val headTicket = cache.beginRead(owner, 0L, 16 * 1024L)
            val tailOffset = fileSize - 64 * 1024L
            val tailTicket = cache.beginRead(owner, tailOffset, fileSize)

            assertEquals(16 * 1024, headTicket.readSome(ByteArray(16 * 1024), 0, 16 * 1024))
            assertEquals(64 * 1024, tailTicket.readSome(ByteArray(64 * 1024), 0, 64 * 1024))
            assertEquals(2, cache.laneCountForTest(owner))
            runCurrent()
            assertTrue(cache.planForTest().lookAhead.isEmpty(), "head + tail probes in WAITING emit zero LookAhead")
            assertEquals(2, issued.size, "only the 1 head block and 1 tail block were fetched")
            headTicket.endRead(allowFinishInFlight = false)
            tailTicket.endRead(allowFinishInFlight = false)

            // 2. Resume jump to 8 * blockSize in WAITING:
            issued.clear()
            val resumeOffset = 8 * blockSize
            val resumeTicket = cache.beginRead(owner, resumeOffset, fileSize)
            val halfBlock = ByteArray((blockSize / 2).toInt())
            assertEquals(halfBlock.size, resumeTicket.readSome(halfBlock, 0, halfBlock.size))
            runCurrent()
            assertTrue(cache.planForTest().lookAhead.isEmpty(), "< 1 block read at jump offset stays Required-only")
            assertEquals(1, issued.size)

            // Cross 1 full block (256 KiB) sequentially while STILL in WAITING -> bounded LookAhead (2 blocks = 512 KiB) activates!
            assertEquals(halfBlock.size, resumeTicket.readSome(halfBlock, 0, halfBlock.size))
            val planAfter1Block = cache.planForTest()
            assertEquals(1, planAfter1Block.lookAhead.size, "crossing 256 KiB sequentially in WAITING unlocks bounded LookAhead")
            val la1 = planAfter1Block.lookAhead.single()
            val la1SpanBlocks = la1.lastSlotInclusive - la1.firstSlot + 1
            assertEquals(2, la1SpanBlocks, "WAITING LookAhead starts at bounded 2 blocks (512 KiB)")

            // Read a 2nd full block in WAITING -> bounded LookAhead grows to 4 blocks (1 MiB), capped <= 8 blocks (2 MiB)!
            val fullBlock = ByteArray(blockSize.toInt())
            var got = 0
            while (got < fullBlock.size) got += resumeTicket.readSome(fullBlock, got, fullBlock.size - got)
            val la2 = cache.planForTest().lookAhead.single()
            assertEquals(4, la2.lastSlotInclusive - la2.firstSlot + 1, "WAITING LookAhead grows progressively to 4 blocks (1 MiB)")

            // 3. Transition to PLAYING with 4-block (1 MiB) owner budget, and open 3 concurrent tickets on the SAME owner:
            owner.submitIntent(
                ConsumerIntent(
                    epoch = 0L,
                    service = ServiceIntent.PLAYING,
                    bufferBudget = BufferBudget(maxReadAheadBytes = 4 * blockSize),
                ),
            )
            // Make two extra lanes sequential so 3 lanes qualify simultaneously on one owner.
            val extra1 = cache.beginRead(owner, 24 * blockSize, fileSize)
            val extra2 = cache.beginRead(owner, 40 * blockSize, fileSize)
            for (t in listOf(extra1, extra2)) {
                var r = 0
                while (r < fullBlock.size) r += t.readSome(fullBlock, r, fullBlock.size - r)
            }
            val multiLanePlan = cache.planForTest()
            assertTrue(multiLanePlan.lookAhead.size >= 2)
            val totalLookAheadBlocks = multiLanePlan.lookAhead.sumOf { it.lastSlotInclusive - it.firstSlot + 1 }
            assertTrue(
                totalLookAheadBlocks <= 4,
                "3 concurrent tickets on 1 owner must divide owner budget (<= 4 blocks), never multiply (got $totalLookAheadBlocks)",
            )

            // 4. Interactive hr-seek bumps epoch 0 -> 1:
            val seekEpoch = 1L
            assertFalse(
                owner.reportPlaybackBuffer(PlaybackBufferSnapshot(generation = 99L, bufferedDuration = 5.seconds, sampledAt = testScheduler.timeSource.markNow())),
                "mismatched generation rejected",
            )
            assertTrue(
                owner.submitIntent(
                    ConsumerIntent(
                        epoch = seekEpoch,
                        service = ServiceIntent.WAITING,
                        bufferBudget = BufferBudget(maxReadAheadBytes = 8 * blockSize),
                    ),
                ),
            )
            runCurrent()
            assertTrue(cache.planForTest().lookAhead.isEmpty(), "epoch bump immediately clears prior epoch LookAhead")
            assertTrue(cache.planForTest().finishInFlight.isEmpty(), "epoch bump immediately clears prior epoch FinishInFlight")
            assertFalse(
                owner.reportPlaybackBuffer(PlaybackBufferSnapshot(generation = 0L, bufferedDuration = 5.seconds, sampledAt = testScheduler.timeSource.markNow())),
                "stale generation 0 rejected after bump to epoch 1",
            )

            val hrSeekTicket = cache.beginRead(owner, 52 * blockSize, fileSize)
            val seekBytes = ByteArray(4096)
            assertEquals(4096, hrSeekTicket.readSome(seekBytes, 0, seekBytes.size))
            assertContentEquals(payload.copyOfRange((52 * blockSize).toInt(), (52 * blockSize + 4096).toInt()), seekBytes)

            resumeTicket.endRead(allowFinishInFlight = false)
            extra1.endRead(allowFinishInFlight = false)
            extra2.endRead(allowFinishInFlight = false)
            hrSeekTicket.endRead(allowFinishInFlight = false)
            assertTrue(memoryBudget.peakBytes <= budgetCap)
        } finally {
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
        assertEquals(0L, memoryBudget.usedBytes)
    }

    @Test
    fun `Stage 4 - preemption and withdrawal record distinct reasons in 0ms and never penalize HostHealth while byte deadline stays monotonic`() = runTest {
        val url = "https://dl-test.mypikpak.com/media.mkv?sign=stage4"
        val holdStream = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val rangeHeader = request.headers[HttpHeaders.Range] ?: "bytes=0-65535"
                        val start = rangeHeader.substringAfter("bytes=").substringBefore('-').toLong()
                        if (start < 2 * blockSize) {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                runCatching {
                                    ch.writeFully(ByteArray(1024) { 1 })
                                    holdStream.await()
                                    ch.close()
                                }
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-${start + blockSize - 1}/${8 * blockSize}"),
                                    HttpHeaders.ContentLength to listOf(blockSize.toString()),
                                ),
                            )
                        } else {
                            respond(
                                ByteReadChannel(ByteArray(blockSize.toInt()) { 9 }),
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-${start + blockSize - 1}/${8 * blockSize}"),
                                    HttpHeaders.ContentLength to listOf(blockSize.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "stage4",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            accountConnectionBudget = 1,
            timeSource = testScheduler.timeSource,
        )
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(client, { url }, connectionBudget = 1, onAttempt = { attempts += it })
        val cache = BlockCache(
            source = reader,
            size = 8 * blockSize,
            concurrency = 1,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )

        val ownerA = client.openTransferDemand(ConsumerIntent(service = ServiceIntent.PAUSED, bufferBudget = BufferBudget.NONE))
        val ownerB = client.openTransferDemand(ConsumerIntent(service = ServiceIntent.WAITING, bufferBudget = BufferBudget.NONE))
        try {
            // 1. Start low-priority ownerA read at 4096L (inside slot 0 after 1024B arrived), then close ownerA -> Withdrawn in 0ms (no 80ms drain grace!) and HostHealth stays clean.
            val tA = cache.beginRead(ownerA, 4096L, 8192L)
            val rA = async { runCatching { tA.readSome(ByteArray(4096), 0, 4096) } }
            runCurrent()
            val withdrawTime = testScheduler.currentTime
            ownerA.close()
            runCurrent()
            assertEquals(withdrawTime, testScheduler.currentTime, "withdrawal skips 80ms socket drain grace")
            assertTrue(rA.await().isFailure)
            val withdrawnAttempt = assertNotNull(attempts.lastOrNull())
            assertEquals(RangeAttempt.Outcome.Cancelled, withdrawnAttempt.outcome)
            assertEquals(RangeAttempt.EndReason.Withdrawn, withdrawnAttempt.endReason)
            assertFalse(client.hostHealth.isBad(url), "owner withdrawal must never penalize HostHealth")

            // 2. Start background warm at blockSize, then preempt with high-priority ownerB WAITING at 4 * blockSize -> Preempted in 0ms, HostHealth clean!
            val warm = cache.warm(listOf(blockSize until 2 * blockSize), StreamRole.BACKGROUND, null)
            runCurrent()
            val preemptTime = testScheduler.currentTime
            val tB = cache.beginRead(ownerB, 4 * blockSize, 4 * blockSize + 4096)
            val out = ByteArray(4096)
            assertEquals(4096, tB.readSome(out, 0, out.size))
            assertEquals(preemptTime, testScheduler.currentTime, "priority preemption skips 80ms drain grace and completes in 0ms")
            val preemptedAttempt = attempts.first { it.endReason == RangeAttempt.EndReason.Preempted }
            assertEquals(RangeAttempt.Outcome.Cancelled, preemptedAttempt.outcome)
            assertFalse(client.hostHealth.isBad(url), "priority preemption must never penalize HostHealth")
            tB.endRead(allowFinishInFlight = false)
            ownerB.close()
            runCurrent()

            // 3. Monotonic ByteDeadline check: a later larger buffer sample on the same stalled slot never pushes its deadline later.
            val deadlineOwner = client.openTransferDemand(
                ConsumerIntent(
                    epoch = 0L,
                    service = ServiceIntent.PLAYING,
                    bufferBudget = BufferBudget.NONE,
                    playbackSample = PlaybackBufferSnapshot(0L, 300.milliseconds, testScheduler.timeSource.markNow()),
                ),
            )
            val tD = cache.beginRead(deadlineOwner, blockSize + 4096, blockSize + 8192)
            val rD = async { runCatching { tD.readSome(ByteArray(4096), 0, 4096) } }
            runCurrent()
            val initialDeadline = assertNotNull(cache.planForTest().required.single { it.owner === deadlineOwner }.deadlineAt)
            advanceTimeBy(50)
            deadlineOwner.reportPlaybackBuffer(PlaybackBufferSnapshot(0L, 5.seconds, testScheduler.timeSource.markNow()))
            runCurrent()
            val updatedDeadline = assertNotNull(cache.planForTest().required.single { it.owner === deadlineOwner }.deadlineAt)
            assertEquals(initialDeadline, updatedDeadline, "byte deadline must stay monotonic and never extend on optimistic heartbeats")

            rD.cancel()
            tD.endRead(allowFinishInFlight = false)
            deadlineOwner.close()
            warm.cancel()
            tB.endRead(allowFinishInFlight = false)
        } finally {
            holdStream.complete(Unit)
            ownerA.close()
            ownerB.close()
            cache.close()
            cache.awaitClosedForTest()
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `TransferDemand - interleaved and concurrent publication never regresses intentFlow or overwrites closed priority`() = runTest {
        val demand = TransferDemand(ConsumerIntent(epoch = 1L, service = ServiceIntent.PLAYING))
        val observedEpochs = mutableListOf<Long>()
        val observedPriorities = mutableListOf<Int>()
        var intentSubscribed = 0
        var prioSubscribed = 0
        val intentJob = launch(start = CoroutineStart.UNDISPATCHED) {
            demand.intentFlow
                .onSubscription { intentSubscribed++ }
                .collect { observedEpochs += it.epoch }
        }
        val prioJob = launch(start = CoroutineStart.UNDISPATCHED) {
            demand.priorityFlow
                .onSubscription { prioSubscribed++ }
                .collect { observedPriorities += it }
        }
        try {
            assertEquals(1, intentSubscribed, "genuine StateFlow must invoke intentFlow.onSubscription")
            assertEquals(1, prioSubscribed, "genuine StateFlow must invoke priorityFlow.onSubscription")

            // 1. Deterministic interleaved contention: epoch=5 lands mid-transform of epoch=1.
            var calls = 0
            assertTrue(
                demand.updateIntent { current ->
                    calls++
                    if (calls == 1) {
                        assertEquals(1L, current.epoch)
                        assertTrue(demand.submitIntent(ConsumerIntent(epoch = 5L, service = ServiceIntent.WAITING)))
                        // Returning epoch=1 here would regress if CAS failed to retry; updateIntent must retry on epoch=5!
                        current.copy(service = ServiceIntent.PREFETCH)
                    } else {
                        assertEquals(5L, current.epoch)
                        current.copy(service = ServiceIntent.PLAYING)
                    }
                },
            )
            runCurrent()
            assertEquals(2, calls)
            assertEquals(5L, demand.intent.epoch)
            assertEquals(5L, demand.intentFlow.value.epoch)
            assertEquals(ServiceIntent.PLAYING.priority, demand.priority)
            assertEquals(ServiceIntent.PLAYING.priority, demand.priorityFlow.value)
            for (i in 1 until observedEpochs.size) {
                assertTrue(observedEpochs[i] >= observedEpochs[i - 1], "intentFlow epoch regressed: $observedEpochs")
            }

            // 2. Deterministic interleaved close(): close() lands mid-transform; priority must stay Int.MIN_VALUE.
            assertFalse(
                demand.updateIntent { current ->
                    demand.close()
                    current.copy(epoch = 99L, service = ServiceIntent.WAITING)
                },
            )
            runCurrent()
            assertTrue(demand.isClosed)
            assertEquals(5L, demand.intent.epoch)
            assertEquals(5L, demand.intentFlow.value.epoch)
            assertEquals(Int.MIN_VALUE, demand.priority)
            assertEquals(Int.MIN_VALUE, demand.priorityFlow.value)
            assertEquals(Int.MIN_VALUE, observedPriorities.last())
        } finally {
            intentJob.cancel()
            prioJob.cancel()
            demand.close()
        }

        // 3. Multi-coroutine race across Dispatchers.Default: monotonic epoch + sticky Int.MIN_VALUE on close().
        withContext(Dispatchers.Default) {
            repeat(24) {
                val shared = TransferDemand(ConsumerIntent(epoch = 0L, service = ServiceIntent.WAITING))
                val done = CompletableDeferred<Unit>()
                val observer = async {
                    var maxIntentEpoch = 0L
                    var maxFlowEpoch = 0L
                    var sawClosed = false
                    var sawFlowClosed = false
                    while (!done.isCompleted) {
                        if (shared.isClosed) sawClosed = true
                        val e1 = shared.intent.epoch
                        val e2 = shared.intentFlow.value.epoch
                        assertTrue(e1 >= maxIntentEpoch, "intent.epoch regressed from $maxIntentEpoch to $e1")
                        assertTrue(e2 >= maxFlowEpoch, "intentFlow.value.epoch regressed from $maxFlowEpoch to $e2")
                        maxIntentEpoch = e1
                        maxFlowEpoch = e2
                        if (sawClosed) {
                            assertEquals(Int.MIN_VALUE, shared.priority)
                        }
                        if (shared.priorityFlow.value == Int.MIN_VALUE) {
                            sawFlowClosed = true
                        }
                        if (sawFlowClosed) {
                            assertEquals(Int.MIN_VALUE, shared.priorityFlow.value)
                        }
                    }
                }
                val writers = (1..4).map { w ->
                    launch {
                        for (step in 1..120) {
                            val epoch = (step * 4 + w).toLong()
                            val svc = ServiceIntent.entries[(step + w) % ServiceIntent.entries.size]
                            shared.submitIntent(ConsumerIntent(epoch = epoch, service = svc))
                            shared.updateIntent { cur -> cur.copy(service = svc) }
                            shared.reportPlaybackBuffer(
                                PlaybackBufferSnapshot(epoch, 250.milliseconds, kotlin.time.TimeSource.Monotonic.markNow()),
                            )
                            if (w == 4 && step == 90) shared.close()
                        }
                    }
                }
                writers.forEach { it.join() }
                shared.close()
                done.complete(Unit)
                observer.await()
                assertTrue(shared.isClosed)
                assertEquals(Int.MIN_VALUE, shared.priority)
                assertEquals(Int.MIN_VALUE, shared.priorityFlow.value)
                assertEquals(shared.intent, shared.intentFlow.value)
            }
        }
    }

    @Test
    fun `Scenario A5 - disconnecting ReadTicket or entering FinishInFlight during retry backoff issues zero follow-up HTTP requests`() = runTest {
        val url = "https://dl-test.mypikpak.com/backoff.mkv?sign=a5"
        val fileSize = 4 * blockSize
        val dropFirstAttempt = CompletableDeferred<Unit>()
        var httpRequests = 0

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        httpRequests++
                        val range = (request.headers[HttpHeaders.Range] ?: "bytes=0-${blockSize - 1}").removePrefix("bytes=")
                        val start = range.substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        val len = (end - start + 1).toInt()
                        if (httpRequests == 1 || httpRequests == 2) {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                ch.writeFully(ByteArray(16 * 1024) { 6 })
                                if (httpRequests == 1) dropFirstAttempt.await()
                                ch.cancel(PikPakException(-1, "simulated mid-body socket reset"))
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        } else {
                            respond(
                                ByteReadChannel(ByteArray(len) { 6 }),
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "a5",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            timeSource = testScheduler.timeSource,
        )
        val reader = RangeReader(client, { url }, connectionBudget = 2)
        val cache = BlockCache(
            source = reader,
            size = fileSize,
            concurrency = 2,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )
        val owner = client.openTransferDemand(ConsumerIntent(service = ServiceIntent.WAITING, bufferBudget = BufferBudget.NONE))
        try {
            // 1. Ticket reads 16 KiB from attempt #1, then attempt #1 drops and enters RangeReader retry backoff.
            val t1 = cache.beginRead(owner, 0L, 32 * 1024L)
            assertEquals(16 * 1024, t1.readSome(ByteArray(16 * 1024), 0, 16 * 1024))
            val waitMore = async { runCatching { t1.readSome(ByteArray(8192), 0, 8192) } }
            runCurrent()
            dropFirstAttempt.complete(Unit)
            runCurrent()
            assertEquals(1, httpRequests, "attempt #1 failed and RangeReader is now suspended in retry backoff")

            // Disconnect ticket with allowFinishInFlight=true DURING retry backoff: must NOT retain FinishInFlight or issue request #2!
            waitMore.cancel()
            t1.endRead(allowFinishInFlight = true)
            runCurrent()
            assertTrue(cache.planForTest().finishInFlight.isEmpty(), "Fetch sitting in retry backoff must not become FinishInFlight")
            assertEquals(16 * 1024, cache.partialBytesForTest(0), "16 KiB partial prefix preserved")
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(1, httpRequests, "zero follow-up HTTP requests after disconnecting during retry backoff")

            // 2. Direct FetchClaimControl check: flipping finishInFlightOnly=true mid-backoff aborts immediately in 0ms with 0 retries!
            val ctrl = FetchClaimControl(
                priorityFlow = MutableStateFlow(PikPakStreamReader.STREAMING_PRIORITY),
                deadlineFlow = MutableStateFlow(null),
            )
            val directRead = async {
                runCatching {
                    withContext(ctrl) {
                        reader.read(blockSize, blockSize, PikPakStreamReader.STREAMING_PRIORITY) { ch ->
                            val buf = ByteArray(blockSize.toInt())
                            var total = 0
                            while (total < buf.size) {
                                val n = ch.readAvailable(buf, total, buf.size - total)
                                if (n < 0) break
                                total += n
                            }
                            total
                        }
                    }
                }
            }
            runCurrent()
            assertEquals(2, httpRequests, "direct read attempt #1 failed and entered retry backoff")
            val flipAt = testScheduler.currentTime
            ctrl.finishInFlightOnly = true
            runCurrent()
            assertTrue(directRead.isCompleted && directRead.await().isFailure, "backoff aborted in 0ms when finishInFlightOnly turned true")
            assertEquals(flipAt, testScheduler.currentTime)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(2, httpRequests, "zero follow-up HTTP requests when finishInFlightOnly turned true mid-backoff")
        } finally {
            dropFirstAttempt.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `Scenario B2 - epoch bump cancels prior speculation in 0ms while keeping open tickets alive as Required-only until endRead or owner close`() = runTest {
        val fileSize = 12 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 23 % 251).toByte() }
        val holdLookAhead = CompletableDeferred<Unit>()
        val holdSlot1 = CompletableDeferred<Unit>()
        val holdSlot6Rest = CompletableDeferred<Unit>()
        val holdSlot8Attempt1Rest = CompletableDeferred<Unit>()
        val holdSlot8Attempt2Rest = CompletableDeferred<Unit>()
        val holdSlot10 = CompletableDeferred<Unit>()
        val cancelledSlots = mutableListOf<Int>()
        var slot8Calls = 0

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                val slot = (start / blockSize).toInt()
                when {
                    slot == 1 -> {
                        var completed = false
                        try {
                            holdSlot1.await()
                            val res = block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                            completed = true
                            res
                        } finally {
                            if (!completed && !holdSlot1.isCompleted) cancelledSlots += slot
                        }
                    }
                    slot in 2..5 -> {
                        var completed = false
                        try {
                            holdLookAhead.await()
                            val res = block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                            completed = true
                            res
                        } finally {
                            if (!completed && !holdLookAhead.isCompleted) cancelledSlots += slot
                        }
                    }
                    slot == 6 -> {
                        val ch = ByteChannel(autoFlush = true)
                        var completed = false
                        val writer = launch {
                            runCatching {
                                ch.writeFully(payload, start.toInt(), start.toInt() + 16 * 1024)
                                holdSlot6Rest.await()
                                ch.writeFully(payload, start.toInt() + 16 * 1024, (start + length).toInt())
                                ch.flushAndClose()
                            }
                        }
                        try {
                            val res = block(ch)
                            completed = true
                            res
                        } finally {
                            if (!completed && !holdSlot6Rest.isCompleted) cancelledSlots += slot
                            writer.cancel()
                        }
                    }
                    slot == 8 -> {
                        slot8Calls++
                        val gate = if (slot8Calls == 1) holdSlot8Attempt1Rest else holdSlot8Attempt2Rest
                        val ch = ByteChannel(autoFlush = true)
                        var completed = false
                        val writer = launch {
                            runCatching {
                                val chunk = minOf(16 * 1024, length.toInt())
                                ch.writeFully(payload, start.toInt(), start.toInt() + chunk)
                                gate.await()
                                if (length.toInt() > chunk) {
                                    ch.writeFully(payload, start.toInt() + chunk, (start + length).toInt())
                                }
                                ch.flushAndClose()
                            }
                        }
                        try {
                            val res = block(ch)
                            completed = true
                            res
                        } finally {
                            if (!completed && !gate.isCompleted) cancelledSlots += slot
                            writer.cancel()
                        }
                    }
                    slot == 10 -> {
                        var completed = false
                        try {
                            holdSlot10.await()
                            val res = block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                            completed = true
                            res
                        } finally {
                            if (!completed && !holdSlot10.isCompleted) cancelledSlots += slot
                        }
                    }
                    else -> block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                }
            }
        }

        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 8,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 4 * blockSize,
            memoryCapBytes = 24 * blockSize,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 3 * blockSize),
                playbackSample = PlaybackBufferSnapshot(0L, 400.milliseconds, testScheduler.timeSource.markNow()),
            ),
        )
        try {
            // 1. Epoch 0 setup:
            //    - activeOldTicket reads block 0 sequentially (spawning LookAhead on slots 2..4) and parks in readSome on slot 1.
            //    - fifOldTicket short-reads 8 KiB of slot 6 and disconnects into FinishInFlight.
            //    - spanOldTicket reads 4 KiB of slot 8 and stays idle/open across the epoch bump.
            //    - unregisteredOldTicket is created at epoch 0 before the bump without calling readSome yet.
            val activeOldTicket = cache.beginRead(owner, 0L, 6 * blockSize)
            val block0Buf = ByteArray(blockSize.toInt())
            var read0 = 0
            while (read0 < block0Buf.size) {
                read0 += activeOldTicket.readSome(block0Buf, read0, block0Buf.size - read0)
            }
            assertContentEquals(payload.copyOfRange(0, blockSize.toInt()), block0Buf)

            val fifOldTicket = cache.beginRead(owner, 6 * blockSize, 6 * blockSize + 8192L)
            assertEquals(8192, fifOldTicket.readSome(ByteArray(8192), 0, 8192))
            fifOldTicket.endRead(allowFinishInFlight = true)

            val spanOldTicket = cache.beginRead(owner, 8 * blockSize, 9 * blockSize)
            assertEquals(4096, spanOldTicket.readSome(ByteArray(4096), 0, 4096))

            val unregisteredOldTicket = cache.beginRead(owner, 7 * blockSize, 7 * blockSize + 4096L)

            val parkedRead = async {
                val out = ByteArray(4096)
                val n = activeOldTicket.readSome(out, 0, out.size)
                n to out
            }
            runCurrent()
            assertTrue(cache.planForTest().lookAhead.isNotEmpty(), "epoch 0 LookAhead active before seek")
            assertTrue(6 in cache.planForTest().finishInFlight.map { it.slot }, "epoch 0 FinishInFlight active on slot 6")

            // 2. Intra-session hr-seek bumps epoch 0 -> 1 BEFORE HTTP client closes old sockets:
            val bumpAt = testScheduler.currentTime
            assertTrue(
                owner.submitIntent(
                    ConsumerIntent(
                        epoch = 1L,
                        service = ServiceIntent.WAITING,
                        bufferBudget = BufferBudget(maxReadAheadBytes = 3 * blockSize),
                    ),
                ),
            )
            runCurrent()
            assertEquals(bumpAt, testScheduler.currentTime, "epoch bump reconciles in 0ms")

            val planAfterBump = cache.planForTest()
            assertEquals(0, cache.laneCountForTest(owner), "all prior-epoch ReadLanes pruned in 0ms")
            assertTrue(planAfterBump.lookAhead.isEmpty(), "all prior-epoch LookAhead pruned in 0ms")
            assertTrue(planAfterBump.finishInFlight.isEmpty(), "all prior-epoch FinishInFlight pruned in 0ms")
            assertTrue(cancelledSlots.any { it in 2..5 }, "in-flight LookAhead fetches cancelled in 0ms")
            assertTrue(6 in cancelledSlots, "in-flight epoch-0 FinishInFlight on slot 6 cancelled in 0ms")
            assertFalse(1 in cancelledSlots, "slot 1 actively awaited by activeOldTicket.readSome must NOT be severed!")

            // activeOldTicket emits Required ONLY for single block slot 1 with zero deadline episode:
            val reqAfterBump = planAfterBump.required.single()
            assertEquals(1, reqAfterBump.slot)
            assertNull(reqAfterBump.deadlineAt, "older-epoch Required claim must not arm new-epoch deadline episode")
            assertFalse(parkedRead.isCompleted, "parked readSome stays alive instead of throwing DemandWithdrawnException")

            // Deliver slot 1 -> activeOldTicket.readSome completes cleanly with exact bytes (no premature EOF for reconnect=0)!
            holdSlot1.complete(Unit)
            runCurrent()
            val (n1, bytes1) = parkedRead.await()
            assertEquals(4096, n1)
            assertContentEquals(payload.copyOfRange(blockSize.toInt(), blockSize.toInt() + 4096), bytes1)
            activeOldTicket.endRead(allowFinishInFlight = false)

            // Pre-opened unregisteredOldTicket (epoch 0 < 1) also serves Required-only single block with 0 lane/LookAhead/FinishInFlight:
            val preBuf = ByteArray(4096)
            assertEquals(4096, unregisteredOldTicket.readSome(preBuf, 0, preBuf.size))
            assertContentEquals(payload.copyOfRange((7 * blockSize).toInt(), (7 * blockSize + 4096).toInt()), preBuf)
            assertEquals(0, cache.laneCountForTest(owner), "older-epoch ticket never creates or grows a ReadLane")
            unregisteredOldTicket.endRead(allowFinishInFlight = true)
            runCurrent()
            assertTrue(cache.planForTest().finishInFlight.isEmpty(), "older-epoch ticket never seeds FinishInFlight")
            assertIs<PikPakException>(runCatching { unregisteredOldTicket.readSome(ByteArray(16), 0, 16) }.exceptionOrNull())

            // 3. New-epoch (epoch 1) FinishInFlight on slot 8 is NOT cleared when older-epoch spanOldTicket calls endRead(false):
            val newFifTicket = cache.beginRead(owner, 8 * blockSize + 16 * 1024L, 8 * blockSize + 24 * 1024L)
            assertEquals(8192, newFifTicket.readSome(ByteArray(8192), 0, 8192))
            newFifTicket.endRead(allowFinishInFlight = true)
            runCurrent()
            assertEquals(listOf(8), cache.planForTest().finishInFlight.map { it.slot })

            spanOldTicket.endRead(allowFinishInFlight = false)
            runCurrent()
            assertEquals(
                listOf(8),
                cache.planForTest().finishInFlight.map { it.slot },
                "closing stale-epoch ticket with allowFinishInFlight=false must not evict new-epoch FinishInFlight",
            )

            // 4. Forcible owner.close() (withdrawTransport) STILL aborts open/parked tickets immediately in 0ms with DemandWithdrawnException!
            val parkedAtClose = cache.beginRead(owner, 10 * blockSize, 10 * blockSize + 4096L)
            val closeRead = async { runCatching { parkedAtClose.readSome(ByteArray(64), 0, 64) } }
            runCurrent()
            val closeAt = testScheduler.currentTime
            owner.close()
            runCurrent()
            assertEquals(closeAt, testScheduler.currentTime)
            val closeEx = closeRead.await().exceptionOrNull()
            assertIs<DemandWithdrawnException>(closeEx)
            assertIs<CancellationException>(closeEx)
            assertTrue(10 in cancelledSlots, "owner.close() cancels in-flight fetch in 0ms")
            parkedAtClose.endRead(allowFinishInFlight = false)
        } finally {
            holdLookAhead.complete(Unit)
            holdSlot1.complete(Unit)
            holdSlot6Rest.complete(Unit)
            holdSlot8Attempt1Rest.complete(Unit)
            holdSlot8Attempt2Rest.complete(Unit)
            holdSlot10.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Stage 4 - fired ByteDeadline consumes episode without rearming on heartbeats and clears when Required leaves`() = runTest {
        val url = "https://dl-test.mypikpak.com/deadline.mkv?sign=dl"
        val fileSize = 8 * blockSize
        val holdSlot0Attempt1 = CompletableDeferred<Unit>()
        val holdSlot1 = CompletableDeferred<Unit>()
        var slot0Requests = 0

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val range = (request.headers[HttpHeaders.Range] ?: "bytes=0-${blockSize - 1}").removePrefix("bytes=")
                        val start = range.substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        val len = (end - start + 1).toInt()
                        if (start < blockSize) {
                            slot0Requests++
                            if (slot0Requests == 1) {
                                val ch = ByteChannel(autoFlush = true)
                                launch {
                                    runCatching {
                                        ch.writeFully(ByteArray(1024) { 3 })
                                        holdSlot0Attempt1.await()
                                        ch.close()
                                    }
                                }
                                respond(
                                    ch,
                                    HttpStatusCode.PartialContent,
                                    headersOf(
                                        HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                        HttpHeaders.ContentLength to listOf(len.toString()),
                                    ),
                                )
                            } else {
                                val ch = ByteChannel(autoFlush = true)
                                launch {
                                    ch.writeFully(ByteArray(1024) { 3 })
                                    delay(400.milliseconds)
                                    ch.writeFully(ByteArray(len - 1024) { 3 })
                                    ch.flushAndClose()
                                }
                                respond(
                                    ch,
                                    HttpStatusCode.PartialContent,
                                    headersOf(
                                        HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                        HttpHeaders.ContentLength to listOf(len.toString()),
                                    ),
                                )
                            }
                        } else {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                runCatching {
                                    ch.writeFully(ByteArray(1024) { 8 })
                                    holdSlot1.await()
                                    ch.writeFully(ByteArray(len - 1024) { 8 })
                                    ch.flushAndClose()
                                }
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "dl",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            timeSource = testScheduler.timeSource,
        )
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(client, { url }, connectionBudget = 2, onAttempt = { attempts += it })
        val cache = BlockCache(
            source = reader,
            size = fileSize,
            concurrency = 2,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )

        val owner1 = client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget.NONE,
                playbackSample = PlaybackBufferSnapshot(0L, 200.milliseconds, testScheduler.timeSource.markNow()),
            ),
        )
        val owner2 = client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget.NONE,
                playbackSample = PlaybackBufferSnapshot(0L, 300.milliseconds, testScheduler.timeSource.markNow()),
            ),
        )
        try {
            // Part A: ByteDeadline fires once at 200ms and is NEVER rearmed by subsequent reportPlaybackBuffer heartbeats.
            val warm0 = cache.warm(listOf(0L until blockSize), StreamRole.BACKGROUND, null)
            runCurrent()
            val t1 = cache.beginRead(owner1, 4096L, 8192L)
            val r1 = async {
                val out = ByteArray(4096)
                t1.readSome(out, 0, out.size)
            }
            runCurrent()
            assertNotNull(cache.activeDeadlineForTest(0), "initial ByteDeadline armed on low-priority Fetch")

            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline }, "DemandDeadline fired once at 200ms")
            assertNull(cache.activeDeadlineForTest(0), "consumed ByteDeadline cleared")
            assertEquals(2, slot0Requests)

            // Send repeated heartbeats (both tight and roomy) while attempt #2 is in-flight over 400ms: must NEVER rearm!
            for (sampleDur in listOf(40.milliseconds, 10.milliseconds, 2.seconds)) {
                advanceTimeBy(60)
                assertTrue(owner1.reportPlaybackBuffer(PlaybackBufferSnapshot(0L, sampleDur, testScheduler.timeSource.markNow())))
                runCurrent()
                assertNull(cache.activeDeadlineForTest(0), "consumed episode must never rearm on heartbeat ($sampleDur)")
                assertEquals(1, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline }, "zero DemandDeadline retry storm")
            }

            advanceTimeBy(300)
            runCurrent()
            assertEquals(4096, r1.await())
            warm0.await()
            assertEquals(2, slot0Requests, "attempt #2 completed cleanly without any rearmed deadline interruption")
            t1.endRead(allowFinishInFlight = false)

            // Part B: When Required leaves a shared Fetch, fetch.deadlineFlow is cleared to null immediately.
            val warm1 = cache.warm(listOf(blockSize until 2 * blockSize), StreamRole.BACKGROUND, null)
            runCurrent()
            owner2.reportPlaybackBuffer(PlaybackBufferSnapshot(0L, 300.milliseconds, testScheduler.timeSource.markNow()))
            val t2 = cache.beginRead(owner2, blockSize + 4096L, blockSize + 8192L)
            val r2 = async { runCatching { t2.readSome(ByteArray(4096), 0, 4096) } }
            runCurrent()
            assertNotNull(cache.activeDeadlineForTest(1), "ByteDeadline armed on slot 1 while Required is waiting")

            advanceTimeBy(100)
            t2.endRead(allowFinishInFlight = false)
            owner2.close()
            runCurrent()
            assertTrue(r2.await().isFailure)
            assertNull(cache.activeDeadlineForTest(1), "deadlineFlow must clear to null when no Required claim remains on shared Fetch")

            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline }, "cleared deadline never fires on shared warm")
            holdSlot1.complete(Unit)
            warm1.await()
        } finally {
            holdSlot0Attempt1.complete(Unit)
            holdSlot1.complete(Unit)
            owner1.close()
            owner2.close()
            cache.close()
            cache.awaitClosedForTest()
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `Stage 4 - cross-cache SignedUrlGates preempts FinishInFlight in 0ms when accountConnectionBudget is 1`() = runTest {
        val url1 = "https://dl-test.mypikpak.com/file1.mp4?sign=c1"
        val url2 = "https://dl-test.mypikpak.com/file2.mp4?sign=c2"
        val fileSize = 4 * blockSize
        val holdCache1Body = CompletableDeferred<Unit>()

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val range = (request.headers[HttpHeaders.Range] ?: "bytes=0-${blockSize - 1}").removePrefix("bytes=")
                        val start = range.substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        val len = (end - start + 1).toInt()
                        if (request.url.encodedPath.contains("file1")) {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                runCatching {
                                    ch.writeFully(ByteArray(16 * 1024) { 5 })
                                    holdCache1Body.await()
                                    ch.writeFully(ByteArray(len - 16 * 1024) { 5 })
                                    ch.flushAndClose()
                                }
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        } else {
                            respond(
                                ByteReadChannel(ByteArray(len) { 7 }),
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "crosscache",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            accountConnectionBudget = 1,
            timeSource = testScheduler.timeSource,
        )
        val attempts1 = mutableListOf<RangeAttempt>()
        val attempts2 = mutableListOf<RangeAttempt>()
        val reader1 = RangeReader(client, { url1 }, connectionBudget = 1, onAttempt = { attempts1 += it })
        val reader2 = RangeReader(client, { url2 }, connectionBudget = 1, onAttempt = { attempts2 += it })
        val cache1 = BlockCache(
            source = reader1,
            size = fileSize,
            concurrency = 1,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )
        val cache2 = BlockCache(
            source = reader2,
            size = fileSize,
            concurrency = 1,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )
        val owner1 = client.openTransferDemand(ConsumerIntent(service = ServiceIntent.WAITING, bufferBudget = BufferBudget.NONE))
        val owner2 = client.openTransferDemand(ConsumerIntent(service = ServiceIntent.PREFETCH, bufferBudget = BufferBudget.NONE))
        try {
            // cache1 starts a short read, buffers 16 KiB on the single account connection slot, then disconnects into FinishInFlight (-1).
            val t1 = cache1.beginRead(owner1, 0L, 8192L)
            assertEquals(8192, t1.readSome(ByteArray(8192), 0, 8192))
            t1.endRead(allowFinishInFlight = true)
            runCurrent()
            assertEquals(1, cache1.planForTest().finishInFlight.size)
            assertEquals(1, client.signedUrlGates.inUse)

            advanceTimeBy(50)
            val preemptAt = testScheduler.currentTime
            // cache2 (even at PREFETCH priority 8 >= 0) immediately preempts cache1's FinishInFlight (-1) across SignedUrlGates in 0ms!
            val t2 = cache2.beginRead(owner2, 0L, 4096L)
            val out = ByteArray(4096)
            assertEquals(4096, t2.readSome(out, 0, out.size))
            assertEquals(preemptAt, testScheduler.currentTime, "cross-cache SignedUrlGates preempted FinishInFlight (-1) in 0ms")
            assertContentEquals(ByteArray(4096) { 7 }, out)
            t2.endRead(allowFinishInFlight = false)
            runCurrent()

            assertEquals(16 * 1024, cache1.partialBytesForTest(0), "cache1 preserved 16 KiB partial prefix on cross-cache preemption")
            assertEquals(RangeAttempt.EndReason.Preempted, attempts1.single().endReason)
            assertFalse(client.hostHealth.isBad(url1))
            assertFalse(client.hostHealth.isBad(url2))
        } finally {
            holdCache1Body.complete(Unit)
            owner1.close()
            owner2.close()
            cache1.close()
            cache2.close()
            cache1.awaitClosedForTest()
            cache2.awaitClosedForTest()
            reader1.close()
            reader2.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `Scenario A6 - disconnecting after retry headers arrive before retry first body byte keeps zero FinishInFlight while preserving partial prefix`() = runTest {
        val url = "https://dl-test.mypikpak.com/retry-headers.mkv?sign=a6"
        val fileSize = 4 * blockSize
        val dropAttempt1 = CompletableDeferred<Unit>()
        val attempt2HeadersSent = CompletableDeferred<Unit>()
        val holdAttempt2Body = CompletableDeferred<Unit>()
        var httpRequests = 0

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        httpRequests++
                        val range = (request.headers[HttpHeaders.Range] ?: "bytes=0-${blockSize - 1}").removePrefix("bytes=")
                        val start = range.substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        val len = (end - start + 1).toInt()
                        if (httpRequests == 1) {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                ch.writeFully(ByteArray(16 * 1024) { 4 })
                                dropAttempt1.await()
                                ch.cancel(PikPakException(-1, "simulated mid-body socket reset"))
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        } else {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                attempt2HeadersSent.complete(Unit)
                                holdAttempt2Body.await()
                                runCatching {
                                    ch.writeFully(ByteArray(len) { 4 })
                                    ch.flushAndClose()
                                }
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "a6",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            timeSource = testScheduler.timeSource,
        )
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(client, { url }, connectionBudget = 2, onAttempt = { attempts += it })
        val cache = BlockCache(
            source = reader,
            size = fileSize,
            concurrency = 2,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )
        val owner = client.openTransferDemand(ConsumerIntent(service = ServiceIntent.WAITING, bufferBudget = BufferBudget.NONE))
        try {
            val t1 = cache.beginRead(owner, 0L, 32 * 1024L)
            assertEquals(16 * 1024, t1.readSome(ByteArray(16 * 1024), 0, 16 * 1024))
            val waitMore = async { runCatching { t1.readSome(ByteArray(8192), 0, 8192) } }
            runCurrent()

            // Fail Attempt #1 after 16 KiB, then advance until Attempt #2 answers 206 headers with 0 body bytes.
            dropAttempt1.complete(Unit)
            runCurrent()
            var guard = 0
            while (!attempt2HeadersSent.isCompleted && guard++ < 100) {
                advanceTimeBy(100)
                runCurrent()
            }
            assertTrue(attempt2HeadersSent.isCompleted, "Attempt #2 answered HTTP 206 headers")
            assertEquals(2, httpRequests)
            runCurrent()

            // Disconnect right after Attempt #2 headers arrived (0 body bytes on Attempt #2):
            // must keep ZERO FinishInFlight, cancel Attempt #2 in 0ms, and preserve Attempt #1's 16 KiB partial prefix!
            val disconnectAt = testScheduler.currentTime
            waitMore.cancel()
            t1.endRead(allowFinishInFlight = true)
            runCurrent()

            assertTrue(cache.planForTest().finishInFlight.isEmpty(), "retry exchange with 0 body bytes must never qualify for FinishInFlight")
            assertEquals(disconnectAt, testScheduler.currentTime, "Attempt #2 cancelled in 0ms")
            assertEquals(16 * 1024, cache.partialBytesForTest(0), "Attempt #1's 16 KiB partial prefix preserved in partials")
            assertEquals(1, attempts.count { it.outcome == RangeAttempt.Outcome.Cancelled }, "Attempt #2 cancelled cleanly")

            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(2, httpRequests, "zero follow-up HTTP requests after aborting 0-byte retry exchange")
        } finally {
            dropAttempt1.complete(Unit)
            holdAttempt2Body.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `Stage 4 - concurrent republish of shared-cell ByteDeadline never double-fires DemandDeadline`() = runTest {
        val url = "https://dl-test.mypikpak.com/deadline-race.mkv?sign=dlrace"
        val fileSize = 4 * blockSize
        val holdAttempt1 = CompletableDeferred<Unit>()
        var requests = 0

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        requests++
                        val range = (request.headers[HttpHeaders.Range] ?: "bytes=0-${blockSize - 1}").removePrefix("bytes=")
                        val start = range.substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        val len = (end - start + 1).toInt()
                        if (requests == 1) {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                runCatching {
                                    ch.writeFully(ByteArray(1024) { 2 })
                                    holdAttempt1.await()
                                    ch.close()
                                }
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        } else {
                            val ch = ByteChannel(autoFlush = true)
                            launch {
                                ch.writeFully(ByteArray(1024) { 2 })
                                delay(300.milliseconds)
                                ch.writeFully(ByteArray(len - 1024) { 2 })
                                ch.flushAndClose()
                            }
                            respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "dlrace",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            timeSource = testScheduler.timeSource,
        )
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(client, { url }, connectionBudget = 2, onAttempt = { attempts += it })
        try {
            val sharedConsumed = MutableStateFlow(false)
            val coConsumed = MutableStateFlow(false)
            val baseMark = testScheduler.timeSource.markNow()
            // Simulate reconcileClaimsLocked() building d2 from the same unconsumed episode right before d1.tryConsume() wins.
            val d1 = ByteDeadline(
                slot = 0,
                endExclusive = 8192L,
                at = baseMark + 100.milliseconds,
                consumed = sharedConsumed,
                coConsumed = listOf(coConsumed),
            )
            val d2 = ByteDeadline(
                slot = 0,
                endExclusive = 16384L,
                at = baseMark + 100.milliseconds,
                consumed = sharedConsumed,
                coConsumed = listOf(coConsumed),
            )
            val ctrl = FetchClaimControl(
                priorityFlow = MutableStateFlow(PikPakStreamReader.STREAMING_PRIORITY),
                deadlineFlow = MutableStateFlow(d1),
            )

            val readJob = async {
                withContext(ctrl) {
                    reader.read(0L, blockSize, PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY) { ch ->
                        val out = ByteArray(blockSize.toInt())
                        var total = 0
                        while (total < out.size) {
                            val n = ch.readAvailable(out, total, out.size - total)
                            if (n < 0) break
                            total += n
                        }
                        total
                    }
                }
            }
            runCurrent()
            assertEquals(1, requests)

            // Fire d1 at +100ms: consumes sharedConsumed & coConsumed atomically and triggers Attempt #2.
            advanceTimeBy(100)
            runCurrent()
            assertTrue(sharedConsumed.value)
            assertTrue(coConsumed.value)
            assertEquals(1, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline })
            assertEquals(2, requests)

            // Late publisher writes stale-constructed d2 onto deadlineFlow: sharedConsumed is already true,
            // so demandWatch clears deadlineFlow immediately and never fires a second DemandDeadline!
            ctrl.deadlineFlow.value = d2
            runCurrent()
            assertFalse(d2.tryConsume())
            assertNull(ctrl.deadlineFlow.value, "already-consumed ByteDeadline must clear immediately without re-firing")

            advanceTimeBy(350)
            runCurrent()
            assertEquals(blockSize.toInt(), readJob.await())
            assertEquals(1, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline }, "DemandDeadline never double-fires")
            assertEquals(2, requests)
        } finally {
            holdAttempt1.complete(Unit)
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `Stage 4 - withdrawing owner 1 as its deadline timer expires while owner 2 shares in-flight slot causes zero DemandDeadline cancellations`() = runTest {
        val url = "https://dl-test.mypikpak.com/shared-deadline.mkv?sign=sdl"
        val fileSize = 4 * blockSize
        val holdSlot0 = CompletableDeferred<Unit>()
        var requests = 0

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        requests++
                        val range = (request.headers[HttpHeaders.Range] ?: "bytes=0-${blockSize - 1}").removePrefix("bytes=")
                        val start = range.substringBefore('-').toLong()
                        val end = range.substringAfter('-').toLong()
                        val len = (end - start + 1).toInt()
                        val ch = ByteChannel(autoFlush = true)
                        launch {
                            runCatching {
                                ch.writeFully(ByteArray(1024) { 9 })
                                holdSlot0.await()
                                ch.writeFully(ByteArray(len - 1024) { 9 })
                                ch.flushAndClose()
                            }
                        }
                        respond(
                            ch,
                            HttpStatusCode.PartialContent,
                            headersOf(
                                HttpHeaders.ContentRange to listOf("bytes $start-$end/$fileSize"),
                                HttpHeaders.ContentLength to listOf(len.toString()),
                            ),
                        )
                    }
                },
            ),
        )

        val client = PikPakClient(
            account = "sdl",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            timeSource = testScheduler.timeSource,
        )
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(client, { url }, connectionBudget = 2, onAttempt = { attempts += it })
        val cache = BlockCache(
            source = reader,
            size = fileSize,
            concurrency = 2,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = client.foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 2 * blockSize,
            memoryCapBytes = 8 * blockSize,
            scheduler = client.scheduler,
            memoryBudget = client.transportMemoryBudget,
            timeSource = testScheduler.timeSource,
        )
        val owner1 = client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget.NONE,
                playbackSample = PlaybackBufferSnapshot(0L, 200.milliseconds, testScheduler.timeSource.markNow()),
            ),
        )
        val owner2 = client.openTransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.PLAYING,
                bufferBudget = BufferBudget.NONE,
            ),
        )
        var warm: Deferred<Unit>? = null
        var r1: Deferred<Result<Int>>? = null
        var r2: Deferred<Int>? = null
        try {
            warm = cache.warm(listOf(0L until blockSize), StreamRole.BACKGROUND, null)
            runCurrent()
            assertEquals(1, requests)

            val t1 = cache.beginRead(owner1, 4096L, 8192L)
            r1 = async { runCatching { t1.readSome(ByteArray(4096), 0, 4096) } }
            val t2 = cache.beginRead(owner2, 4096L, 8192L)
            val out2 = ByteArray(4096)
            r2 = async { t2.readSome(out2, 0, out2.size) }
            runCurrent()

            val armedTarget = assertNotNull(cache.activeDeadlineForTest(0), "owner1 armed ByteDeadline on shared slot 0")
            advanceTimeBy(199)
            runCurrent()

            // Advance virtual clock to t=200ms before running tasks scheduled at 200ms, withdraw owner1,
            // AND verify authorizeDeadline rejects the detached target under BlockCache.mutex!
            advanceTimeBy(1)
            owner1.close()
            runCurrent()

            assertTrue(r1.await().isFailure)
            assertFalse(
                cache.authorizeDeadlineForTest(0, armedTarget, 1024L),
                "detached target after owner1 withdrawal must not authorize DemandDeadline",
            )
            assertNull(cache.activeDeadlineForTest(0))
            assertEquals(0, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline }, "zero DemandDeadline cancellations")
            assertEquals(1, requests, "shared in-flight HTTP request continues uninterrupted for owner2")

            holdSlot0.complete(Unit)
            runCurrent()
            assertEquals(4096, r2.await())
            assertContentEquals(ByteArray(4096) { 9 }, out2)
            warm.await()
            assertEquals(1, requests)
            assertEquals(0, attempts.count { it.endReason == RangeAttempt.EndReason.DemandDeadline })
            t1.endRead(allowFinishInFlight = false)
            t2.endRead(allowFinishInFlight = false)
        } finally {
            r1?.cancel()
            r2?.cancel()
            warm?.cancel()
            holdSlot0.complete(Unit)
            owner1.close()
            owner2.close()
            cache.close()
            cache.awaitClosedForTest()
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun `Scenario A7 - concurrent interleaved short-range tickets on cached and dual uncached lanes keep per-lane FinishInFlight without mid-block cancellation`() = runTest {
        val fileSize = 64 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 19 % 251).toByte() }
        val starts = mutableListOf<Pair<Long, Long>>()
        val cancelledStarts = mutableListOf<Long>()
        val holdSlot32 = CompletableDeferred<Unit>()
        val holdSlot10 = CompletableDeferred<Unit>()
        val holdSlot42 = CompletableDeferred<Unit>()

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                starts += start to length
                val gate = when (start) {
                    32 * blockSize -> holdSlot32
                    10 * blockSize -> holdSlot10
                    42 * blockSize -> holdSlot42
                    else -> null
                }
                if (gate != null) {
                    val ch = ByteChannel(autoFlush = true)
                    var completed = false
                    val writer = launch {
                        runCatching {
                            // Deliver 16 KiB immediately, then hold remainder of the 256 KiB block.
                            ch.writeFully(payload, start.toInt(), start.toInt() + 16 * 1024)
                            gate.await()
                            ch.writeFully(payload, start.toInt() + 16 * 1024, (start + length).toInt())
                            ch.flushAndClose()
                        }
                    }
                    try {
                        val res = block(ch)
                        completed = true
                        res
                    } finally {
                        if (!completed && !gate.isCompleted) cancelledStarts += start
                        writer.cancel()
                    }
                } else {
                    block(ByteReadChannel(payload.copyOfRange(start.toInt(), (start + length).toInt())))
                }
            }
        }

        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 4,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 4 * blockSize,
            memoryCapBytes = 16 * blockSize,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(ConsumerIntent(epoch = 0L, service = ServiceIntent.PLAYING))
        try {
            // Pre-populate slot 0 into cache (e.g. from head probe).
            val probe0 = cache.beginRead(owner, 0L, 4096L)
            assertEquals(4096, probe0.readSome(ByteArray(4096), 0, 4096))
            probe0.endRead(allowFinishInFlight = true)
            runCurrent()
            assertEquals(listOf(0L to blockSize), starts)

            // Part 1: Cached-ticket shadowing check — firstAudio (cached slot 0) finishes readSome first and stays open
            // at openTickets[0] while firstVideo (in-flight slot 32) finishes readSome at openTickets[1] before endRead!
            val firstAudio = cache.beginRead(owner, 0L, 8192L)
            val audioBuf = ByteArray(8192)
            assertEquals(8192, firstAudio.readSome(audioBuf, 0, audioBuf.size))
            assertContentEquals(payload.copyOfRange(0, 8192), audioBuf)
            // Keep firstAudio open (!ended && !reading) at openTickets[0]!

            val vOff = 32 * blockSize
            val firstVideo = cache.beginRead(owner, vOff, vOff + 8192L)
            val videoBuf1 = ByteArray(8192)
            assertEquals(8192, firstVideo.readSome(videoBuf1, 0, videoBuf1.size))
            assertContentEquals(payload.copyOfRange(vOff.toInt(), (vOff + 8192).toInt()), videoBuf1)
            runCurrent()

            assertTrue(cancelledStarts.isEmpty(), "cached openTickets[0] must NOT shadow in-flight openTickets[1] on slot 32")
            assertEquals(listOf(32), cache.planForTest().finishInFlight.map { it.slot })

            firstAudio.endRead(allowFinishInFlight = true)
            firstVideo.endRead(allowFinishInFlight = true)
            runCurrent()
            assertTrue(cancelledStarts.isEmpty())
            assertEquals(listOf(32), cache.planForTest().finishInFlight.map { it.slot })

            // Subsequent video short-range read at +8 KiB reuses in-flight Fetch(32) with zero restarts!
            val secondVideo = cache.beginRead(owner, vOff + 8192L, vOff + 16384L)
            val videoBuf2 = ByteArray(8192)
            assertEquals(8192, secondVideo.readSome(videoBuf2, 0, videoBuf2.size))
            assertContentEquals(payload.copyOfRange((vOff + 8192).toInt(), (vOff + 16384).toInt()), videoBuf2)
            secondVideo.endRead(allowFinishInFlight = true)
            holdSlot32.complete(Unit)
            runCurrent()
            assertEquals(listOf(0L to blockSize, vOff to blockSize), starts, "zero mid-block cancellation or re-request on slot 32")

            // Part 2: Dual uncached interleaved lanes (audio at slot 10 + video at slot 42) under ONE owner
            // retain per-slot FinishInFlight simultaneously without overwriting each other!
            val aOff = 10 * blockSize
            val v2Off = 42 * blockSize
            val a1 = cache.beginRead(owner, aOff, aOff + 8192L)
            assertEquals(8192, a1.readSome(ByteArray(8192), 0, 8192))
            a1.endRead(allowFinishInFlight = true)
            runCurrent()

            val v1 = cache.beginRead(owner, v2Off, v2Off + 8192L)
            assertEquals(8192, v1.readSome(ByteArray(8192), 0, 8192))
            v1.endRead(allowFinishInFlight = true)
            runCurrent()

            assertTrue(cancelledStarts.isEmpty(), "interleaved audio/video FinishInFlight must not cancel each other")
            assertEquals(setOf(10, 42), cache.planForTest().finishInFlight.map { it.slot }.toSet())

            val a2 = cache.beginRead(owner, aOff + 8192L, aOff + 16384L)
            val a2Out = ByteArray(8192)
            assertEquals(8192, a2.readSome(a2Out, 0, a2Out.size))
            assertContentEquals(payload.copyOfRange((aOff + 8192).toInt(), (aOff + 16384).toInt()), a2Out)
            a2.endRead(allowFinishInFlight = true)

            val v2 = cache.beginRead(owner, v2Off + 8192L, v2Off + 16384L)
            val v2Out = ByteArray(8192)
            assertEquals(8192, v2.readSome(v2Out, 0, v2Out.size))
            assertContentEquals(payload.copyOfRange((v2Off + 8192).toInt(), (v2Off + 16384).toInt()), v2Out)
            v2.endRead(allowFinishInFlight = true)

            holdSlot10.complete(Unit)
            holdSlot42.complete(Unit)
            runCurrent()
            assertTrue(cancelledStarts.isEmpty())
            assertEquals(
                listOf(0L to blockSize, vOff to blockSize, aOff to blockSize, v2Off to blockSize),
                starts,
                "each block issued strictly once across interleaved short-range reads",
            )
        } finally {
            holdSlot32.complete(Unit)
            holdSlot10.complete(Unit)
            holdSlot42.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Scenario A8 - delayed endRead after withdrawSpeculation never resurrects FinishInFlight and finishInFlightBySlot caps at 4 lanes`() = runTest {
        val fileSize = 20 * blockSize
        val payload = ByteArray(fileSize.toInt()) { (it * 31 % 251).toByte() }
        val cancelledSlots = mutableListOf<Int>()
        val holdRest = CompletableDeferred<Unit>()

        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                val slot = (start / blockSize).toInt()
                val ch = ByteChannel(autoFlush = true)
                var completed = false
                val writer = launch {
                    runCatching {
                        ch.writeFully(payload, start.toInt(), start.toInt() + 16 * 1024)
                        holdRest.await()
                        ch.writeFully(payload, start.toInt() + 16 * 1024, (start + length).toInt())
                        ch.flushAndClose()
                    }
                }
                try {
                    val res = block(ch)
                    completed = true
                    res
                } finally {
                    if (!completed && !holdRest.isCompleted) cancelledSlots += slot
                    writer.cancel()
                }
            }
        }

        val cache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 8,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 4 * blockSize,
            memoryCapBytes = 24 * blockSize,
            timeSource = testScheduler.timeSource,
        )
        val owner = TransferDemand(ConsumerIntent(epoch = 0L, service = ServiceIntent.PLAYING))
        var warm0: Deferred<Unit>? = null
        try {
            // Part 1: Shared warm holds slot 0 alive -> ticket reads 8 KiB -> owner.withdrawSpeculation() ->
            // delayed ticket.endRead(allowFinishInFlight = true) without new reads -> warm cancels -> Fetch(0) must cancel immediately!
            warm0 = cache.warm(listOf(0L until blockSize), StreamRole.BACKGROUND, null)
            val t0 = cache.beginRead(owner, 0L, 8192L)
            assertEquals(8192, t0.readSome(ByteArray(8192), 0, 8192))
            runCurrent()
            assertEquals(listOf(0), cache.planForTest().finishInFlight.map { it.slot })

            owner.withdrawSpeculation()
            runCurrent()
            assertTrue(cache.planForTest().finishInFlight.isEmpty(), "withdrawSpeculation cleared FinishInFlight")
            assertTrue(cancelledSlots.isEmpty(), "background warm still keeps slot 0 Fetch alive")

            // Delayed endRead(allowFinishInFlight = true) with NO post-withdrawal read evidence must NOT resurrect FinishInFlight!
            t0.endRead(allowFinishInFlight = true)
            runCurrent()
            assertTrue(
                cache.planForTest().finishInFlight.isEmpty(),
                "endRead after withdrawSpeculation without fresh read evidence must not resurrect FinishInFlight",
            )

            warm0.cancel()
            runCurrent()
            assertEquals(listOf(0), cancelledSlots, "slot 0 Fetch cancelled in 0ms once warm withdrew")

            // Part 2: 5 distinct uncached short-read slots exceed MAX_LANES_PER_OWNER (4) -> oldest slot (2) evicted & cancelled!
            for (slot in listOf(2, 4, 6, 8, 10)) {
                val off = slot * blockSize
                val t = cache.beginRead(owner, off, off + 8192L)
                assertEquals(8192, t.readSome(ByteArray(8192), 0, 8192))
                t.endRead(allowFinishInFlight = true)
                runCurrent()
            }
            assertEquals(
                listOf(4, 6, 8, 10),
                cache.planForTest().finishInFlight.map { it.slot },
                "finishInFlightBySlot strictly capped at MAX_LANES_PER_OWNER (4), evicting oldest slot 2",
            )
            assertTrue(2 in cancelledSlots, "evicted 5th FinishInFlight slot 2 cancelled immediately")
            assertEquals(16 * 1024, cache.partialBytesForTest(2), "evicted slot 2 retained its 16 KiB partial prefix")
        } finally {
            warm0?.cancel()
            holdRest.complete(Unit)
            owner.close()
            cache.close()
            cache.awaitClosedForTest()
        }
    }

    @Test
    fun `Stage 4 - cross-file 8x multi-block durable download collapses immediately to at most 2 single-block streams when foreground WAITING opens`() = runTest {
        val bgFileSize = 32 * blockSize
        val fgFileSize = 8 * blockSize
        val bgPayload = ByteArray(bgFileSize.toInt()) { (it * 37 % 251).toByte() }
        val fgPayload = ByteArray(fgFileSize.toInt()) { (it * 41 % 251).toByte() }

        val foregroundStreams = ForegroundStreams()
        val scheduler = TransferScheduler(16, 8, BandwidthLimiter(), testScheduler.timeSource)
        val releaseWave1FirstBlock = CompletableDeferred<Unit>()
        val allowSubsequentBgReads = CompletableDeferred<Unit>()

        var activeBgReads = 0
        var peakBgWhileFg = 0
        var preemptedMultiBlock = 0
        var retiredMultiBlockAfterOneBlock = 0
        val bgLengthsWhileFg = mutableListOf<Long>()

        val bgSource = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = coroutineScope {
                val initialMultiBlockWave = !releaseWave1FirstBlock.isCompleted && length > blockSize
                activeBgReads++
                if (!initialMultiBlockWave && foregroundStreams.active.value > 0) {
                    bgLengthsWhileFg += length
                    if (activeBgReads > peakBgWhileFg) peakBgWhileFg = activeBgReads
                }
                var completed = false
                try {
                    if (initialMultiBlockWave) {
                        val ch = ByteChannel(autoFlush = true)
                        var blocksWritten = 0
                        val writer = launch {
                            runCatching {
                                releaseWave1FirstBlock.await()
                                val totalBlocks = (length / blockSize).toInt()
                                for (b in 0 until totalBlocks) {
                                    val chunkStart = start.toInt() + b * blockSize.toInt()
                                    ch.writeFully(bgPayload, chunkStart, chunkStart + blockSize.toInt())
                                    blocksWritten++
                                    // Yield between blocks so BlockCache publishes block 0 and checks stopAtBoundary before block 1!
                                    delay(1.milliseconds)
                                }
                                ch.flushAndClose()
                            }
                        }
                        try {
                            val res = block(ch)
                            completed = true
                            if (blocksWritten == 1 && length > blockSize) {
                                retiredMultiBlockAfterOneBlock++
                            }
                            res
                        } finally {
                            writer.cancel()
                        }
                    } else {
                        allowSubsequentBgReads.await()
                        delay(1.milliseconds)
                        val res = block(ByteReadChannel(bgPayload.copyOfRange(start.toInt(), (start + length).toInt())))
                        completed = true
                        res
                    }
                } finally {
                    if (initialMultiBlockWave && !completed && !releaseWave1FirstBlock.isCompleted) {
                        preemptedMultiBlock++
                    }
                    activeBgReads--
                }
            }
        }

        val fgSource = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = block(ByteReadChannel(fgPayload.copyOfRange(start.toInt(), (start + length).toInt())))
        }

        val storeMutex = Mutex()
        val storedBlocks = HashMap<Long, ByteArray>()
        val bgStore = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? =
                storeMutex.withLock { storedBlocks[offset]?.takeIf { it.size == length } }

            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                storeMutex.withLock { storedBlocks[offset] = bytes.copyOf() }
            }

            override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = storeMutex.withLock {
                ranges.flatMap { range ->
                    (range.first / blockSize..range.last / blockSize)
                        .filter { it * blockSize !in storedBlocks }
                        .map { blk -> maxOf(range.first, blk * blockSize)..minOf(range.last, (blk + 1) * blockSize - 1) }
                }
            }
        }

        val bgCache = BlockCache(
            source = bgSource,
            size = bgFileSize,
            concurrency = 8,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = foregroundStreams,
            store = bgStore,
            storeKey = "bg.mkv",
            blockSize = blockSize,
            readAheadBytes = 4 * blockSize,
            memoryCapBytes = 24 * blockSize,
            scheduler = scheduler,
            timeSource = testScheduler.timeSource,
        )
        val fgCache = BlockCache(
            source = fgSource,
            size = fgFileSize,
            concurrency = 8,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = foregroundStreams,
            blockSize = blockSize,
            readAheadBytes = 4 * blockSize,
            memoryCapBytes = 24 * blockSize,
            scheduler = scheduler,
            timeSource = testScheduler.timeSource,
        )

        var bgDownload: Deferred<Unit>? = null
        val fgOwner = TransferDemand(ConsumerIntent(epoch = 0L, service = ServiceIntent.WAITING))
        try {
            // 1. Start Cache 1 durable download before any foreground playback: 8 workers launch 4-block (1 MiB) streams.
            bgDownload = PikPakFileCache(bgCache).download()
            runCurrent()
            assertEquals(8, activeBgReads, "all 8 background durable workers active in multi-block mode before FG starts")

            // 2. Cache 2 starts a foreground WAITING read:
            //    - Cache 1 immediately preempts 6 excess BG workers in 0ms (8 -> 2 <= BACKGROUND_WORKERS_WHILE_FOREGROUND).
            //    - Cache 2's foreground WAITING read completes immediately in 0ms without starvation!
            val fgStartAt = testScheduler.currentTime
            val fgTicket = fgCache.beginRead(fgOwner, 2 * blockSize, 2 * blockSize + 16 * 1024L)
            val fgOut = ByteArray(16 * 1024)
            assertEquals(16 * 1024, fgTicket.readSome(fgOut, 0, fgOut.size))
            assertContentEquals(fgPayload.copyOfRange((2 * blockSize).toInt(), (2 * blockSize + 16 * 1024).toInt()), fgOut)
            runCurrent()

            assertEquals(fgStartAt, testScheduler.currentTime, "FG WAITING read completed in 0ms without BG starvation")
            assertEquals(1, foregroundStreams.active.value)
            assertEquals(6, preemptedMultiBlock, "6 excess multi-block BG workers preempted immediately in 0ms")
            assertEquals(
                PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND,
                activeBgReads,
                "BG concurrency collapsed immediately from 8 to 2",
            )

            // 3. Let the 2 surviving multi-block BG workers finish their current 256 KiB block, and allow subsequent BG reads:
            //    Both surviving multi-block workers must retire right at the 1-block boundary (never continuing blocks 2..4),
            //    and every new BG request issued while FG is active must be 1 block (256 KiB) at concurrency <= 2!
            releaseWave1FirstBlock.complete(Unit)
            allowSubsequentBgReads.complete(Unit)
            advanceTimeBy(10)
            runCurrent()

            assertEquals(2, retiredMultiBlockAfterOneBlock, "both surviving multi-block BG streams retired at next 256 KiB boundary")
            assertTrue(bgLengthsWhileFg.isNotEmpty(), "throttled BG workers continued making single-block progress")
            assertTrue(
                bgLengthsWhileFg.all { it == blockSize },
                "every BG download request while FG is active must be 1 block ($blockSize), got $bgLengthsWhileFg",
            )
            assertTrue(
                peakBgWhileFg <= PikPakStreamReader.BACKGROUND_WORKERS_WHILE_FOREGROUND,
                "BG concurrency while FG is active exceeded 2: $peakBgWhileFg",
            )

            // 4. Release FG and let BG download finish completely with exact data integrity.
            fgTicket.endRead(allowFinishInFlight = false)
            fgOwner.close()
            advanceTimeBy(6_000)
            runCurrent()
            bgDownload.await()

            val reconstructed = ByteArray(bgFileSize.toInt())
            storeMutex.withLock {
                assertEquals(32, storedBlocks.size)
                for ((off, blk) in storedBlocks) {
                    blk.copyInto(reconstructed, off.toInt())
                }
            }
            assertContentEquals(bgPayload, reconstructed)
        } finally {
            releaseWave1FirstBlock.complete(Unit)
            allowSubsequentBgReads.complete(Unit)
            bgDownload?.cancel()
            fgOwner.close()
            fgCache.close()
            bgCache.close()
            fgCache.awaitClosedForTest()
            bgCache.awaitClosedForTest()
        }
    }

    @Test
    fun `Stage 4 - streamRangeFromUrl and ActiveResponseGuard cancel response body channels across validation and mid-stream failures`() = runTest {
        val openedChannels = mutableListOf<ByteChannel>()
        var mode = 0
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        val ch = ByteChannel(autoFlush = true).also { openedChannels += it }
                        when (mode) {
                            0 -> respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(HttpHeaders.ContentLength to listOf("4096")), // missing Content-Range!
                            )
                            1 -> respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes 4096-8191/16384"), // mismatched start!
                                    HttpHeaders.ContentLength to listOf("4096"),
                                ),
                            )
                            else -> respond(
                                ch,
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes 0-4095/16384"),
                                    HttpHeaders.ContentLength to listOf("4096"),
                                ),
                            )
                        }
                    }
                },
            ),
        )
        val client = PikPakClient(
            account = "leakcheck",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            timeSource = testScheduler.timeSource,
        )
        try {
            mode = 0
            assertFailsWith<PikPakException> {
                client.streamRangeFromUrl("https://dl-test.mypikpak.com/f.mkv", 0L, 4096L) { }
            }
            assertTrue(openedChannels[0].isClosedForWrite, "missing Content-Range response body cancelled immediately")

            mode = 1
            assertFailsWith<PikPakException> {
                client.streamRangeFromUrl("https://dl-test.mypikpak.com/f.mkv", 0L, 4096L) { }
            }
            assertTrue(openedChannels[1].isClosedForWrite, "mismatched Content-Range response body cancelled immediately")

            mode = 2
            assertFailsWith<IllegalStateException> {
                client.streamRangeFromUrl("https://dl-test.mypikpak.com/f.mkv", 0L, 4096L) {
                    throw IllegalStateException("consumer aborted mid-stream")
                }
            }
            assertTrue(openedChannels[2].isClosedForWrite, "mid-stream consumer exception cancelled response body immediately")

            // Direct ActiveResponseGuard lifecycle verification (completed normally vs aborted in flight vs late registration):
            val guard1 = ActiveResponseGuard()
            var closed1 = 0
            var aborted1 = 0
            val e1 = guard1.register(closeIdempotent = { closed1++ }, abortInFlight = { aborted1++ })
            guard1.markCompleted(e1)
            guard1.closeAll()
            assertEquals(1, closed1)
            assertEquals(0, aborted1, "normally completed exchange preserves pooled keep-alive socket without call.cancel()")

            val guard2 = ActiveResponseGuard()
            var closed2 = 0
            var aborted2 = 0
            guard2.register(closeIdempotent = { closed2++ }, abortInFlight = { aborted2++ })
            guard2.closeAll()
            assertEquals(1, closed2)
            assertEquals(1, aborted2, "unfinished exchange both closes response and cancels call")

            var closedLate = 0
            var abortedLate = 0
            guard2.register(closeIdempotent = { closedLate++ }, abortInFlight = { abortedLate++ })
            assertEquals(1, closedLate, "late response arriving after closeAll() is closed immediately")
            assertEquals(1, abortedLate, "late response arriving after closeAll() cancels call immediately")
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun `Stage 6 - WAITING LookAhead ceiling keeps 2 MiB below 3 MiB per sec and ramps up to streamRate on 4K REMUX GOP decode`() = runTest {
        val fileSize = 128 * blockSize // 32 MiB
        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = block(ByteReadChannel(ByteArray(length.toInt()) { 9 }))
        }

        suspend fun readBytesExactly(ticket: ReadTicket, totalBytes: Long) {
            val chunk = ByteArray(minOf(totalBytes, 64L * 1024L).toInt())
            var remaining = totalBytes
            while (remaining > 0L) {
                val ask = minOf(remaining, chunk.size.toLong()).toInt()
                val n = ticket.readSome(chunk, 0, ask)
                assertTrue(n > 0)
                remaining -= n
            }
        }

        // 1. Sub-threshold stream (< 3 MiB/s -> 2 MiB/s): WAITING ceiling stays unchanged at 8 blocks (2 MiB).
        val sdCache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 4,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 48 * blockSize,
            memoryCapBytes = 64 * blockSize,
            mediaBytesPerSecond = 2L * 1024 * 1024, // 2 MiB/s (< 3 MiB/s)
            timeSource = testScheduler.timeSource,
        )
        val sdOwner = TransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 48 * blockSize),
            ),
        )
        val sdOwner2 = TransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 48 * blockSize),
            ),
        )
        try {
            val sdTicket = sdCache.beginRead(sdOwner, 0L, fileSize)
            readBytesExactly(sdTicket, 128L * 1024L)
            assertTrue(sdCache.planForTest().lookAhead.isEmpty(), "< 256 KiB probe emits 0 LookAhead")

            // Advance to 6 * blockSize (1.5 MiB sequential -> 2x = 3 MiB > 2 MiB cap):
            readBytesExactly(sdTicket, 6 * blockSize - 128L * 1024L)
            val sdLa = sdCache.planForTest().lookAhead.single()
            assertEquals(6, sdLa.firstSlot)
            assertEquals(13, sdLa.lastSlotInclusive)
            assertEquals(8, sdLa.lastSlotInclusive - sdLa.firstSlot + 1, "< 3 MiB/s WAITING cap stays 8 blocks (2 MiB)")

            // Owner 1 reports an epoch=0 12 MiB/s sample on the 2 MiB/s cache -> only Owner 1 scales to 48 blocks (12 MiB):
            assertTrue(
                sdOwner.reportPlaybackBuffer(
                    PlaybackBufferSnapshot(
                        generation = 0L,
                        bufferedDuration = 200.milliseconds,
                        sampledAt = testScheduler.timeSource.markNow(),
                        mediaBytesPerSecond = 12L * 1024 * 1024,
                    ),
                ),
            )
            readBytesExactly(sdTicket, 22 * blockSize) // total 28 blocks = 7 MiB (2x = 14 MiB >= 12 MiB)
            val sdOwner1Boosted = sdCache.planForTest().lookAhead.single { it.owner === sdOwner }
            assertEquals(48, sdOwner1Boosted.lastSlotInclusive - sdOwner1Boosted.firstSlot + 1)

            // Sibling Owner 2 on the SAME cache (playbackSample = null) must NOT inherit Owner 1's 12 MiB/s sample:
            val sdTicket2 = sdCache.beginRead(sdOwner2, 0L, fileSize)
            readBytesExactly(sdTicket2, 6 * blockSize)
            val sdOwner2La = sdCache.planForTest().lookAhead.single { it.owner === sdOwner2 }
            assertEquals(6, sdOwner2La.firstSlot)
            assertEquals(13, sdOwner2La.lastSlotInclusive)
            assertEquals(8, sdOwner2La.lastSlotInclusive - sdOwner2La.firstSlot + 1, "sibling owner stays at configured 2 MiB/s (8 blocks)")
            sdTicket2.endRead(allowFinishInFlight = false)

            // Advancing Owner 1 to epoch=1 with playbackSample = null resets Owner 1 back to configured 2 MiB/s (8 blocks):
            sdTicket.endRead(allowFinishInFlight = false)
            assertTrue(
                sdOwner.submitIntent(
                    ConsumerIntent(
                        epoch = 1L,
                        service = ServiceIntent.WAITING,
                        bufferBudget = BufferBudget(maxReadAheadBytes = 48 * blockSize),
                        playbackSample = null,
                    ),
                ),
            )
            val sdEpoch1Ticket = sdCache.beginRead(sdOwner, 0L, fileSize)
            readBytesExactly(sdEpoch1Ticket, 6 * blockSize)
            val sdEpoch1La = sdCache.planForTest().lookAhead.single { it.owner === sdOwner }
            assertEquals(6, sdEpoch1La.firstSlot)
            assertEquals(13, sdEpoch1La.lastSlotInclusive)
            assertEquals(8, sdEpoch1La.lastSlotInclusive - sdEpoch1La.firstSlot + 1, "epoch advance with null sample resets back to configured 2 MiB/s (8 blocks)")
            sdEpoch1Ticket.endRead(allowFinishInFlight = false)
        } finally {
            sdOwner.close()
            sdOwner2.close()
            sdCache.close()
            sdCache.awaitClosedForTest()
        }

        // 2. High-bitrate 4K REMUX (10 MiB/s = 40 blocks/s): 0 on < 256 KiB probe, 1 MiB at 512 KiB, ramping to minOf(perLaneCeiling, streamRate).
        val remuxRate = 10L * 1024 * 1024 // 10 MiB/s = 40 * blockSize
        val remuxCache = BlockCache(
            source = source,
            size = fileSize,
            concurrency = 8,
            parentCoroutineContext = coroutineContext,
            foregroundStreams = null,
            blockSize = blockSize,
            readAheadBytes = 64 * blockSize, // 16 MiB
            memoryCapBytes = 96 * blockSize,
            mediaBytesPerSecond = remuxRate,
            timeSource = testScheduler.timeSource,
        )
        val remuxOwner = TransferDemand(
            ConsumerIntent(
                epoch = 0L,
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget(maxReadAheadBytes = 64 * blockSize),
            ),
        )
        try {
            val remuxTicket = remuxCache.beginRead(remuxOwner, 0L, fileSize)

            // 2a. < 256 KiB probe -> 0 LookAhead:
            readBytesExactly(remuxTicket, 128L * 1024L)
            assertTrue(remuxCache.planForTest().lookAhead.isEmpty(), "4K REMUX < 256 KiB probe still emits 0 LookAhead")

            // 2b. 512 KiB sequential read (2 blocks, Cues / moov scale) -> 1 MiB (4 blocks) LookAhead:
            readBytesExactly(remuxTicket, 2 * blockSize - 128L * 1024L)
            val la512 = remuxCache.planForTest().lookAhead.single()
            assertEquals(2, la512.firstSlot)
            assertEquals(5, la512.lastSlotInclusive)
            assertEquals(4, la512.lastSlotInclusive - la512.firstSlot + 1, "512 KiB sequential probe looks ahead 1 MiB (4 blocks)")

            // 2c. Multi-MiB sequential 4K GOP read (6 MiB = 24 blocks, 2x = 12 MiB) -> ramps to minOf(perLaneCeiling=16 MiB, streamRate=10 MiB) = 10 MiB (40 blocks):
            readBytesExactly(remuxTicket, 22 * blockSize)
            val laFullRamp = remuxCache.planForTest().lookAhead.single()
            assertEquals(24, laFullRamp.firstSlot)
            assertEquals(63, laFullRamp.lastSlotInclusive)
            assertEquals(40, laFullRamp.lastSlotInclusive - laFullRamp.firstSlot + 1, "multi-MiB 4K GOP read ramps WAITING LookAhead to streamRate = 10 MiB (40 blocks)")

            // 2d. Tighter owner ceiling (perLaneCeiling = 6 MiB = 24 blocks < streamRate = 10 MiB) -> bounded by perLaneCeiling:
            assertTrue(
                remuxOwner.updateIntent { cur ->
                    cur.copy(bufferBudget = BufferBudget(maxReadAheadBytes = 24 * blockSize))
                },
            )
            val laBounded = remuxCache.planForTest().lookAhead.single()
            assertEquals(24, laBounded.firstSlot)
            assertEquals(47, laBounded.lastSlotInclusive)
            assertEquals(24, laBounded.lastSlotInclusive - laBounded.firstSlot + 1, "WAITING high-bitrate ramp remains bounded by perLaneCeiling (6 MiB)")

            // 2e. Matching-epoch PlaybackBufferSnapshot.mediaBytesPerSecond (12 MiB/s = 48 blocks) overrides static mediaBytesPerSecond:
            assertTrue(
                remuxOwner.updateIntent { cur ->
                    cur.copy(
                        bufferBudget = BufferBudget(maxReadAheadBytes = 64 * blockSize),
                        playbackSample = PlaybackBufferSnapshot(
                            generation = 0L,
                            bufferedDuration = 200.milliseconds,
                            sampledAt = testScheduler.timeSource.markNow(),
                            mediaBytesPerSecond = 12L * 1024 * 1024,
                        ),
                    )
                },
            )
            // Read 1 more MiB so contiguousBytesRead = 7 MiB (2x = 14 MiB >= 12 MiB):
            readBytesExactly(remuxTicket, 4 * blockSize)
            val laSampleRate = remuxCache.planForTest().lookAhead.single()
            assertEquals(28, laSampleRate.firstSlot)
            assertEquals(75, laSampleRate.lastSlotInclusive)
            assertEquals(48, laSampleRate.lastSlotInclusive - laSampleRate.firstSlot + 1, "matching-epoch playbackSample mediaBytesPerSecond (12 MiB/s) drives WAITING ceiling")
            remuxTicket.endRead(allowFinishInFlight = false)

            // 2f. Advancing remuxOwner to epoch=1 with playbackSample = null resets back to configured 10 MiB/s (40 blocks):
            assertTrue(
                remuxOwner.submitIntent(
                    ConsumerIntent(
                        epoch = 1L,
                        service = ServiceIntent.WAITING,
                        bufferBudget = BufferBudget(maxReadAheadBytes = 64 * blockSize),
                        playbackSample = null,
                    ),
                ),
            )
            val remuxEpoch1Ticket = remuxCache.beginRead(remuxOwner, 0L, fileSize)
            readBytesExactly(remuxEpoch1Ticket, 24 * blockSize)
            val laEpoch1Reset = remuxCache.planForTest().lookAhead.single()
            assertEquals(24, laEpoch1Reset.firstSlot)
            assertEquals(63, laEpoch1Reset.lastSlotInclusive)
            assertEquals(40, laEpoch1Reset.lastSlotInclusive - laEpoch1Reset.firstSlot + 1, "epoch=1 with null sample resets back to configured 10 MiB/s (40 blocks)")
            remuxEpoch1Ticket.endRead(allowFinishInFlight = false)
        } finally {
            remuxOwner.close()
            remuxCache.close()
            remuxCache.awaitClosedForTest()
        }
    }
}
