package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.github.nihildigit.pikpak.internal.TransportMemoryBudget
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TransportFeedbackTest {
    @Test
    fun `eight live multi block ranges drain after budget shrinks to one block and pump`() = runBlocking<Unit> {
        val blockSize = 256 * 1024L
        val content = ByteArray((32 * blockSize).toInt()) { (it % 251).toByte() }
        val allEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lock = Mutex()
        var entered = 0
        val http = HttpClient(MockEngine { request ->
            val range = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-')
            val start = range[0].toInt()
            val end = range[1].toInt()
            val initial = lock.withLock {
                entered++
                if (entered == 8) allEntered.complete(Unit)
                entered <= 8
            }
            if (initial) {
                assertTrue(end - start + 1 > blockSize, "initial ranges must span multiple blocks")
                release.await()
            }
            respond(ByteReadChannel(content.copyOfRange(start, end + 1)), HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${content.size}"))
        })
        val client = PikPakClient("shrink", "unused", httpClient = http, cdnHttpClient = http,
            steerEdgeHosts = false, transportMemoryBudgetBytes = 16 * 1024 * 1024L)
        val source = RangeReader(client, { "https://cdn/file" })
        val commits = mutableMapOf<Long, ByteArray>()
        val counts = mutableMapOf<Long, Int>()
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun missing(file: String, ranges: List<LongRange>) = ranges
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                counts[offset] = (counts[offset] ?: 0) + 1
                commits[offset] = bytes.copyOf()
            }
        }
        val cache = BlockCache(source, content.size.toLong(), 8, coroutineContext, null, store,
            memoryBudget = client.transportMemoryBudget)
        try {
            val download = cache.warm(listOf(0L until content.size.toLong()), StreamRole.BACKGROUND, null, durable = true)
            withTimeout(5000) { allEntered.await() }
            assertEquals(8, source.stats.value.activeReads)
            client.setTransportMemoryBudgetBytes(320 * 1024L)
            release.complete(Unit)
            withTimeout(15000) { download.await() }
            assertEquals(32, commits.size)
            assertTrue(counts.values.all { it == 1 })
            for ((offset, bytes) in commits) assertContentEquals(
                content.copyOfRange(offset.toInt(), offset.toInt() + bytes.size), bytes)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) {
                cache.close()
                cache.awaitClosedForTest()
                source.close()
                client.close()
                http.close()
            }
        }
        assertEquals(0L, client.transportMemoryUsedBytes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `durable promotion joins queued optional writes and retains their array credits`() = runTest {
        val budget = TransportMemoryBudget(256)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val content = ByteArray(256) { it.toByte() }
        val store = object : DurableBlockStore {
            override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = null
            override suspend fun missing(file: String, ranges: List<LongRange>) = ranges
            override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
                calls += offset
                if (calls.size == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                assertContentEquals(content.copyOfRange(offset.toInt(), offset.toInt() + bytes.size), bytes)
            }
        }
        val cache = BlockCache(FakeRangeSource(content), 256, 1, coroutineContext, null, store,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, memoryBudget = budget)
        val other = BlockCache(FakeRangeSource(ByteArray(64)), 64, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 192, memoryCapBytes = 512,
            wideBlockThresholdBytes = 1, memoryBudget = budget)
        try {
            cache.warm(listOf(0L..255L), StreamRole.BACKGROUND, null).await()
            entered.await()
            budget.requestReclaim()
            runCurrent()
            assertEquals(0L, cache.cachedBytesForTest())
            val download = cache.warm(listOf(0L..255L), StreamRole.BACKGROUND, null, durable = true)
            val competing = other.warm(listOf(0L..63L), StreamRole.BACKGROUND, null)
            runCurrent()
            assertFalse(download.isCompleted)
            assertFalse(competing.isCompleted)
            assertEquals(listOf(0L), calls)
            assertEquals(256L, budget.usedBytes, "all four queued or running arrays remain owned after eviction")
            release.complete(Unit)
            download.await()
            val writesAtCompletion = calls.toList()
            competing.await()
            runCurrent()
            assertEquals(writesAtCompletion, calls, "download completion leaves no redundant optional write")
            assertEquals(listOf(0L, 64L, 128L, 192L), calls)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) {
                cache.close()
                other.close()
                cache.awaitClosedForTest()
                other.awaitClosedForTest()
            }
        }
        assertEquals(0L, budget.usedBytes)
    }

    @Test
    fun `client exposes an adjustable shared transport budget`() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { respond("") })
        val client = PikPakClient(
            account = "transport-test",
            password = "unused",
            httpClient = http,
            transportMemoryBudgetBytes = 16L * 1024 * 1024,
        )
        try {
            assertEquals(16L * 1024 * 1024, client.transportMemoryBudgetBytes)
            assertEquals(0L, client.transportMemoryUsedBytes)
            client.setTransportMemoryBudgetBytes(8L * 1024 * 1024)
            assertEquals(8L * 1024 * 1024, client.transportMemoryBudgetBytes)
            assertFailsWith<IllegalArgumentException> { client.setTransportMemoryBudgetBytes(0) }
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun `player feedback validates media time and consumption`() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { respond("") })
        val client = PikPakClient("transport-test", "unused", httpClient = http)
        val cache = client.fileCache(FakeRangeSource(ByteArray(1024)), 1024)
        val stream = cache.openStream()
        try {
            stream.reportPlaybackBuffer(2.seconds, 128_000)
            stream.reportPlaybackBuffer(Duration.ZERO)
            cache.updateMediaBytesPerSecond(256_000)
            cache.updateMediaBytesPerSecond(null)
            assertFailsWith<IllegalArgumentException> { stream.reportPlaybackBuffer((-1).seconds) }
            assertFailsWith<IllegalArgumentException> { stream.reportPlaybackBuffer(Duration.INFINITE) }
            assertFailsWith<IllegalArgumentException> { stream.reportPlaybackBuffer(1.seconds, 0) }
            assertFailsWith<IllegalArgumentException> { cache.updateMediaBytesPerSecond(-1) }
        } finally {
            stream.close()
            cache.close()
            client.close()
            http.close()
        }
    }
}
