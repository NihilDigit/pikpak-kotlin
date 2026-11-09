package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DemandTransportTest {
    @Test fun shrinkingReadAheadLetsTheConsumedHeadBlockFinish() = runTest {
        val suffix = CompletableDeferred<Unit>()
        val starts = mutableListOf<Long>()
        var interrupted = false
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                starts += start
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(16) { 3 })
                    suffix.await()
                    channel.writeFully(ByteArray(length.toInt() - 16) { 3 })
                    channel.flushAndClose()
                }
                try { block(channel) } finally {
                    if (!suffix.isCompleted) interrupted = true
                    writer.cancel()
                }
            }
        }
        val cache = BlockCache(source, 256, 1, coroutineContext, null, blockSize = 64, readAheadBytes = 192, memoryCapBytes = 1024)
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        try {
            assertEquals(4, cache.read(cursor, ByteArray(4), 0, 4))
            cache.setReadAhead(cursor, 0)
            runCurrent()
            assertFalse(interrupted, "an idle retained reader still owns its partially consumed head")
            suffix.complete(Unit)
            runCurrent()
            cache.closeCursor(cursor)
            cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null).await()
            assertEquals(listOf(0L), starts)
            assertEquals(64L, cache.deliveredBytes)
        } finally { suffix.complete(Unit); cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun cachedSubBlockReadWakesNewTailOfReadAheadWindow() = runTest {
        val source = FakeRangeSource(ByteArray(2048))
        val cache = BlockCache(source, 2048, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 4096, timeSource = testScheduler.timeSource)
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        try {
            cache.read(cursor, ByteArray(64), 0, 64)
            runCurrent()
            cache.seek(cursor, 511)
            runCurrent()
            cache.read(cursor, ByteArray(1), 0, 1)
            runCurrent()
            assertEquals(512L, cursor.position)
            val previous = source.requests().size
            cache.read(cursor, ByteArray(1), 0, 1)
            runCurrent()
            assertTrue(source.requests().drop(previous).any { it.start <= 704 && it.start + it.length > 704 },
                "an in-block read can still expose a new uncached tail block")
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun concurrentWarmMembershipChangesDoNotKillWorkers() = runBlocking {
        val errors = kotlinx.coroutines.flow.MutableStateFlow<List<Throwable>>(emptyList())
        val handler = CoroutineExceptionHandler { _, error ->
            while (true) {
                val previous = errors.value
                if (errors.compareAndSet(previous, previous + error)) break
            }
        }
        val cache = BlockCache(FakeRangeSource(ByteArray(8192)), 8192, 8, Dispatchers.Default + handler, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 16384)
        try {
            withTimeout(15_000) {
                coroutineScope {
                    repeat(8) { worker -> launch(Dispatchers.Default) {
                        repeat(256) { index ->
                            val start = ((index + worker) % 128) * 64L
                            val warm = cache.warm(listOf(start until start + 64), StreamRole.FOREGROUND, PikPakStreamReader.WARM_PRIORITY)
                            yield()
                            warm.cancelAndJoin()
                        }
                    } }
                }
                cache.warm(listOf(0L..8191L), StreamRole.FOREGROUND, PikPakStreamReader.WARM_PRIORITY).await()
            }
            assertTrue(errors.value.isEmpty(), errors.value.joinToString { it.toString() })
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun promotedBackgroundReaderCanReadUnderItsOwnUrgentLease() = runTest {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no network") })
        val client = PikPakClient("mock", "pw", httpClient = http, sessionStore = InMemorySessionStore())
        val owner = client.openTransferDemand(PikPakStreamReader.WARM_PRIORITY)
        val cache = client.fileCache(FakeRangeSource(ByteArray(512 * 1024) { 7 }), 512L * 1024, "promotion", coroutineContext = coroutineContext)
        val reader = cache.openStream(owner, StreamRole.BACKGROUND)
        reader.readAheadLimit = 0
        try {
            val bytes = ByteArray(256 * 1024)
            assertEquals(bytes.size, reader.read(bytes, 0, bytes.size))
            owner.updatePriority(PikPakStreamReader.BLOCKING_PRIORITY)
            val next = async { reader.read(bytes, 0, bytes.size) }
            runCurrent()
            assertTrue(next.isCompleted, "promotion must not classify its own reader as blocked background work")
            assertEquals(bytes.size, next.await())
        } finally { reader.close(); owner.close(); cache.close(); client.close(); http.close() }
    }

    @Test fun demandPrefetchPreservesExplicitWarmPriorityAndCanBePromoted() = runTest {
        val priorities = mutableListOf<Int>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
                priorities += priority
                return block(ByteReadChannel(ByteArray(length.toInt())))
            }
        }
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no network") })
        val client = PikPakClient("mock", "pw", httpClient = http, sessionStore = InMemorySessionStore())
        val owner = client.openTransferDemand(PikPakStreamReader.WARM_PRIORITY)
        val cache = client.fileCache(source, 512L * 1024, "priority", coroutineContext = coroutineContext)
        try {
            cache.prefetch(listOf(0L..63L), owner, StreamRole.BACKGROUND, PikPakStreamReader.WARM_PRIORITY - 1).await()
            assertEquals(PikPakStreamReader.WARM_PRIORITY - 1, priorities.single())
            owner.updatePriority(PikPakStreamReader.BLOCKING_PRIORITY)
            cache.prefetch(listOf(262144L..262207L), owner, StreamRole.BACKGROUND, PikPakStreamReader.WARM_PRIORITY - 1).await()
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, priorities.last())
        } finally { owner.close(); cache.close(); client.close(); http.close() }
    }

    @Test fun idleDownloadWorkerDoesNotDuplicateTheTailOfAnAcceptedRange() = runTest {
        val starts = mutableListOf<Long>()
        val release = CompletableDeferred<Unit>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                starts.add(start)
                if (start != 0L) {
                    delay(1)
                    return@coroutineScope block(ByteReadChannel(ByteArray(length.toInt())))
                }
                val channel = ByteChannel(autoFlush = true)
                val writer = launch {
                    channel.writeFully(ByteArray(64))
                    release.await()
                    channel.writeFully(ByteArray(length.toInt() - 64))
                    channel.flushAndClose()
                }
                try { block(channel) } finally { writer.cancel() }
            }
        }
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun missing(file: String, ranges: List<LongRange>) = ranges
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) = Unit
        }
        val scheduler = TransferScheduler(2, 2, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(source, 512, 2, coroutineContext, null, store, blockSize = 64,
            readAheadBytes = 192, memoryCapBytes = 1024, scheduler = scheduler)
        try {
            val download = cache.warm(listOf(0L..511L), StreamRole.BACKGROUND, null, durable = true)
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0L, 256L), starts, "idle capacity must not split an already issued HTTP range")
            release.complete(Unit)
            download.await()
        } finally { release.complete(Unit); cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun blockingReadPreemptsSameFileSpeculationOutsideItsRange() = runTest {
        val starts = mutableListOf<Long>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
                starts.add(start)
                if (starts.size == 1) return block(ByteChannel(autoFlush = true))
                return block(ByteReadChannel(ByteArray(length.toInt()) { 4 }))
            }
        }
        val scheduler = TransferScheduler(1, 1, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(source, 256, 1, coroutineContext, null, blockSize = 64,
            readAheadBytes = 192, memoryCapBytes = 512, scheduler = scheduler)
        try {
            val warm = cache.warm(listOf(0L..127L), StreamRole.BACKGROUND, null)
            runCurrent()
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            cache.seek(cursor, 192)
            val read = async { cache.read(cursor, ByteArray(1), 0, 1) }
            runCurrent()
            assertTrue(read.isCompleted)
            assertEquals(1, read.await())
            assertEquals(listOf(0L, 192L), starts.take(2))
            warm.await()
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun waitingForMemoryPriorityDoesNotEvictUnrelatedCachedBytes() = runTest {
        val memory = TransportMemoryBudget(128)
        fun newCache() = BlockCache(FakeRangeSource(ByteArray(64)), 64, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512, memoryBudget = memory)
        val first = newCache()
        val second = newCache()
        try {
            first.warm(listOf(0L..63L), StreamRole.BACKGROUND, null).await()
            val urgent = memory.registerWaiter(100)
            val pending = second.warm(listOf(0L..63L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(64L, first.cachedBytesForTest())
            memory.unregisterWaiter(urgent)
            pending.await()
            assertEquals(64L, first.cachedBytesForTest())
            assertEquals(128L, memory.usedBytes)
        } finally {
            first.close(); second.close()
            first.awaitClosedForTest(); second.awaitClosedForTest()
        }
        assertEquals(0L, memory.usedBytes)
    }

    @Test fun hungerPreemptsEvenANearlyCompleteDownloadAndResumesItsPrefixImmediately() = runTest {
        val scheduler = TransferScheduler(8, 8, BandwidthLimiter(), testScheduler.timeSource)
        val starts = mutableListOf<Long>()
        var interrupted = false
        val content = ByteArray(64) { it.toByte() }
        val written = mutableListOf<ByteArray>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                starts.add(start)
                if (starts.size > 1) return@coroutineScope block(ByteReadChannel(content.copyOfRange(start.toInt(), 64)))
                val channel = ByteChannel(autoFlush = true)
                val writer = launch { channel.writeFully(content, 0, 48); awaitCancellation() }
                try { block(channel) } finally { interrupted = true; writer.cancel() }
            }
        }
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun missing(file: String, ranges: List<LongRange>) = ranges
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) { written.add(bytes.copyOf()) }
        }
        val cache = BlockCache(source, 64, 1, coroutineContext, null, store, blockSize = 64,
            readAheadBytes = 192, memoryCapBytes = 512, scheduler = scheduler, timeSource = testScheduler.timeSource)
        val player = scheduler.register {}
        try {
            val download = cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null, durable = true)
            runCurrent()
            scheduler.reportDemand(player, CacheDemand(foreground = true, hungry = true))
            runCurrent()
            assertTrue(interrupted)
            assertEquals(listOf(0L), starts)
            scheduler.unregister(player)
            runCurrent()
            download.await()
            assertEquals(listOf(0L, 48L), starts)
            assertContentEquals(content, written.single())
            assertEquals(0L, testScheduler.currentTime)
        } finally { scheduler.unregister(player); cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun memoryWaiterPriorityDoesNotLeakAfterWithdrawal() {
        val memory = TransportMemoryBudget(64)
        val foreground = memory.registerWaiter(100)
        assertFalse(memory.tryAcquire(64, 1))
        assertTrue(memory.tryAcquire(64, 100))
        memory.release(64)
        memory.unregisterWaiter(foreground)
        assertTrue(memory.tryAcquire(64, 1))
        memory.release(64)
        assertEquals(0L, memory.usedBytes)
    }

    @Test fun preparationLeaseCanFetchItsOwnDependenciesAndCloseWithoutAClockTick() = runTest {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no network") })
        val client = PikPakClient("mock", "pw", httpClient = http, sessionStore = InMemorySessionStore())
        val background = client.scheduler.register {}
        client.scheduler.reportDemand(background, CacheDemand(background = true))
        val first = client.openTransferDemand()
        val second = client.openTransferDemand()
        val cache = client.fileCache(FakeRangeSource(ByteArray(64) { 9 }), 64, "lease", coroutineContext = coroutineContext)
        try {
            assertEquals(0, client.scheduler.workersFor(background, true))
            cache.prefetch(listOf(0L..63L), first).await()
            first.close()
            assertEquals(0, client.scheduler.workersFor(background, true), "another owner is still urgent")
            second.close()
            assertEquals(8, client.scheduler.workersFor(background, true))
            first.close()
            assertEquals(8, client.scheduler.workersFor(background, true))
        } finally { cache.close(); first.close(); second.close(); client.close(); http.close() }
    }

    @Test fun closedOwnerWithdrawsQueuedAndActiveRequests() = runTest {
        val admission = SignedUrlGates(1)
        val first = TransferDemand(10)
        val second = TransferDemand(1)
        val running = async(first) {
            try { admission.withSlot("https://cdn/a") { awaitCancellation() } }
            catch (_: CancellationException) { }
        }
        runCurrent()
        var entered = false
        val queued = async(second) {
            try { admission.withSlot("https://cdn/b") { entered = true } }
            catch (_: CancellationException) { }
        }
        runCurrent()
        second.close(); runCurrent(); queued.await()
        assertFalse(entered)
        first.close(); runCurrent(); running.await()
        assertEquals(1, admission.withSlot("https://cdn/c") { 1 })
    }

    @Test fun staleFeedbackCannotRestoreAnEarlierGenerationOrRefreshItsSampleTime() = runTest {
        val cache = BlockCache(FakeRangeSource(ByteArray(64)), 64, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512, timeSource = testScheduler.timeSource)
        try {
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            val sampled = testScheduler.timeSource.markNow()
            advanceTimeBy(1000)
            cache.reportPlaybackBuffer(cursor, PlaybackBufferSnapshot(2, 2.seconds, sampled))
            assertEquals(1.seconds, cursor.bufferedDuration - cursor.feedbackAt!!.elapsedNow())
            cache.reportPlaybackBuffer(cursor, PlaybackBufferSnapshot(1, 20.seconds, testScheduler.timeSource.markNow()))
            assertEquals(1.seconds, cursor.bufferedDuration - cursor.feedbackAt!!.elapsedNow())
            cache.reportPlaybackBuffer(cursor, PlaybackBufferSnapshot(3, null, testScheduler.timeSource.markNow()))
            assertNull(cursor.feedbackAt)
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun partialPrefixSurvivesCancellationWithinTheSameMemoryBudget() = runTest {
        val starts = mutableListOf<Long>()
        val data = ByteArray(64) { it.toByte() }
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                starts.add(start)
                if (starts.size > 1) return@coroutineScope block(ByteReadChannel(data.copyOfRange(start.toInt(), (start + length).toInt())))
                val channel = ByteChannel(autoFlush = true)
                val writer = launch { channel.writeFully(data, 0, 32); awaitCancellation() }
                try { block(channel) } finally { writer.cancel() }
            }
        }
        val memory = TransportMemoryBudget(64)
        val cache = BlockCache(source, 64, 1, coroutineContext, null, blockSize = 64,
            readAheadBytes = 192, memoryCapBytes = 512, memoryBudget = memory)
        try {
            val warm = cache.warm(listOf(0L..63L), StreamRole.BACKGROUND, null)
            runCurrent()
            warm.cancelAndJoin()
            runCurrent()
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            val result = ByteArray(64)
            var count = 0
            while (count < 64) count += cache.read(cursor, result, count, 64 - count)
            assertContentEquals(data, result)
            assertEquals(listOf(0L, 32L), starts)
            assertEquals(64L, memory.peakBytes)
        } finally { cache.close(); cache.awaitClosedForTest() }
        assertEquals(0L, memory.usedBytes)
    }

    @Test fun blockingTailDoesNotWaitForItsStalledPredecessor() = runTest {
        val starts = mutableListOf<Long>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
                starts.add(start)
                if (start == 0L) return block(ByteChannel(autoFlush = true))
                return block(ByteReadChannel(ByteArray(length.toInt()) { 7 }))
            }
        }
        val cache = BlockCache(source, 256, 1, coroutineContext, null, blockSize = 64,
            readAheadBytes = 192, memoryCapBytes = 512)
        try {
            val warm = cache.warm(listOf(0L..191L), StreamRole.BACKGROUND, null)
            runCurrent()
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            cache.seek(cursor, 64)
            val result = ByteArray(64)
            val read = async { cache.read(cursor, result, 0, 64) }
            runCurrent()
            assertTrue(read.isCompleted)
            assertEquals(64, read.await())
            assertContentEquals(ByteArray(64) { 7 }, result)
            assertEquals(listOf(0L, 64L), starts.take(2))
            warm.cancelAndJoin()
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun blockedFileDoesNotReserveAccountCapacity() = runTest {
        val admission = SignedUrlGates(2)
        val file = Any()
        val release = CompletableDeferred<Unit>()
        val first = async(FileAdmission(file, 1)) { admission.withSlot("https://cdn/a") { release.await() } }
        runCurrent()
        val blocked = async(FileAdmission(file, 1)) { admission.withSlot("https://cdn/a") {} }
        runCurrent()
        val other = async(FileAdmission(Any(), 1)) { admission.withSlot("https://cdn/b") { 42 } }
        runCurrent()
        assertEquals(42, other.await())
        assertFalse(blocked.isCompleted)
        release.complete(Unit)
        first.await(); blocked.await()
    }

    @Test fun urgentPreemptionWaitsOnlyForActualExchangeCleanup() = runTest {
        val admission = SignedUrlGates(1)
        val cleanup = CompletableDeferred<Unit>()
        var cancelled = false
        val background = async {
            try { admission.withSlot("https://cdn/a", 1) {
                try { awaitCancellation() } finally {
                    cancelled = true
                    withContext(NonCancellable) { cleanup.await() }
                }
            } } catch (_: TransferPreempted) { }
        }
        runCurrent()
        val foreground = async { admission.withSlot("https://cdn/b", 100) { 7 } }
        runCurrent()
        assertTrue(cancelled)
        assertFalse(foreground.isCompleted)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(7, foreground.await())
        background.await()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(9, admission.withSlot("https://cdn/a", 1) { 9 })
    }

    @Test fun queuedDemandCanBePromotedInPlace() = runTest {
        val admission = SignedUrlGates(1)
        val low = async {
            try { admission.withSlot("https://cdn/a", 10) { awaitCancellation() } }
            catch (_: TransferPreempted) { }
        }
        runCurrent()
        val owner = TransferDemand(1)
        val promoted = async(owner) { admission.withSlot("https://cdn/b") { 3 } }
        runCurrent()
        assertFalse(promoted.isCompleted)
        owner.updatePriority(100)
        runCurrent()
        assertEquals(3, promoted.await())
        low.await()
    }

    @Test fun nearCompleteRangesNeverExtendBodySilence() {
        for (received in listOf(0L, 1L, 800L, 999L))
            assertEquals(2.seconds, RangeReader.tailSilenceDeadline(2.seconds, received, 1000))
    }

    @Test fun multiBlockFetchDoesNotCorruptLaterPartialSlot() = runTest {
        val data = ByteArray(192) { (it + 1).toByte() }
        var seedPartial = true
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                if (seedPartial && start == 64L) {
                    val channel = ByteChannel(autoFlush = true)
                    val writer = launch { channel.writeFully(data, 64, 32); awaitCancellation() }
                    try { return@coroutineScope block(channel) } finally { writer.cancel() }
                }
                block(ByteReadChannel(data.copyOfRange(start.toInt(), (start + length).toInt())))
            }
        }
        val memory = TransportMemoryBudget(1024)
        val scheduler = TransferScheduler(2, 2, BandwidthLimiter(), testScheduler.timeSource)
        val cache = BlockCache(source, 192, 1, coroutineContext, null, blockSize = 64,
            readAheadBytes = 192, memoryCapBytes = 1024, scheduler = scheduler, memoryBudget = memory)
        try {
            val warm1 = cache.warm(listOf(64L..127L), StreamRole.BACKGROUND, null)
            runCurrent()
            warm1.cancelAndJoin()
            runCurrent()
            seedPartial = false
            cache.warm(listOf(0L..191L), StreamRole.FOREGROUND, null).await()
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            val out = ByteArray(192)
            var read = 0
            while (read < 192) read += cache.read(cursor, out, read, 192 - read)
            assertContentEquals(data, out)
        } finally { cache.close(); cache.awaitClosedForTest() }
    }

    @Test fun obsoletePartialsAreReclaimedWhenMemoryCapIsFull() = runTest {
        val data = ByteArray(192) { (it + 1).toByte() }
        var seedPartials = true
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T = coroutineScope {
                if (seedPartials && start >= 64L) {
                    val channel = ByteChannel(autoFlush = true)
                    val writer = launch { channel.writeFully(data, start.toInt(), 32); awaitCancellation() }
                    try { return@coroutineScope block(channel) } finally { writer.cancel() }
                }
                block(ByteReadChannel(data.copyOfRange(start.toInt(), (start + length).toInt())))
            }
        }
        val memory = TransportMemoryBudget(192)
        val cache = BlockCache(source, 192, 1, coroutineContext, null, blockSize = 64,
            readAheadBytes = 64, memoryCapBytes = 256, memoryBudget = memory)
        try {
            for (offset in listOf(64L..127L, 128L..191L)) {
                val warm = cache.warm(listOf(offset), StreamRole.BACKGROUND, null)
                runCurrent()
                warm.cancelAndJoin()
                runCurrent()
            }
            seedPartials = false
            cache.warm(listOf(0L..63L), StreamRole.FOREGROUND, null).await()
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setReadAhead(cursor, 0)
            val out = ByteArray(64)
            var read = 0
            while (read < 64) read += cache.read(cursor, out, read, 64 - read)
            assertContentEquals(data.copyOfRange(0, 64), out)
        } finally { cache.close(); cache.awaitClosedForTest() }
    }
}
