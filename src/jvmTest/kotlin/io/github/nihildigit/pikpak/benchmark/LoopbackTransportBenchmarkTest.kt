package io.github.nihildigit.pikpak.benchmark

import com.sun.net.httpserver.HttpServer
import io.github.nihildigit.pikpak.*
import io.github.nihildigit.pikpak.internal.defaultCdnHttpClient
import io.github.nihildigit.pikpak.simulation.MIB
import io.github.nihildigit.pikpak.simulation.content
import io.github.nihildigit.pikpak.simulation.verifyContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.TimeSource

/** Real HTTP/1.1, default OkHttp transport, production cache/controller and real disk writes.
 * No environment gate: correctness is a test; wall-clock throughput is reported, not a CI SLO.
 */
class LoopbackTransportBenchmarkTest {
    @Test fun realHttpAndDisk128MiBUnder16MiBCacheBudget() = runBlocking<Unit> {
        val size = 128 * MIB
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val rejected = AtomicInteger()
        val requests = AtomicInteger()
        val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val executor = Executors.newFixedThreadPool(12)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        try {
        server.executor = executor
        server.createContext("/content") { exchange ->
            val running = active.incrementAndGet()
            peak.accumulateAndGet(running, ::maxOf)
            requests.incrementAndGet()
            connections += exchange.remoteAddress.port
            var counted = true
            try {
                if (running > 8) {
                    rejected.incrementAndGet()
                    exchange.sendResponseHeaders(503, -1)
                } else {
                    check(exchange.protocol == "HTTP/1.1")
                    val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(exchange.requestHeaders.getFirst("Range"))!!
                    var position = range.groupValues[1].toLong()
                    val end = minOf(range.groupValues[2].toLongOrNull() ?: (size - 1), size - 1)
                    exchange.responseHeaders.add("Content-Range", "bytes $position-$end/$size")
                    exchange.sendResponseHeaders(206, end - position + 1)
                    exchange.responseBody.use { output ->
                        while (position <= end) {
                            val count = minOf(128 * 1024L, end - position + 1).toInt()
                            val bytes = content(position, count)
                            if (position + count > end) {
                                // The client can admit its next request as soon as the last byte
                                // arrives, before this handler has closed its response.
                                output.write(bytes, 0, count - 1)
                                active.decrementAndGet()
                                counted = false
                                output.write(bytes[count - 1].toInt())
                            } else output.write(bytes)
                            position += count
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // A cache may intentionally retire a response at a published block boundary.
                // Missing data is detected independently by the final disk integrity pass.
            } finally {
                if (counted) active.decrementAndGet()
                exchange.close()
            }
        }
        server.start()
        val directory = Path.of("build", "transport-benchmark")
        Files.createDirectories(directory)
        val output = Files.createTempFile(directory, "loopback-", ".bin")
        try {
        FileDisk(output).use { disk ->
        val api = HttpClient(MockEngine { error("loopback benchmark must not call account APIs") })
        val cdn = defaultCdnHttpClient(8)
        val client = PikPakClient("loopback", { "unused" }, sessionStore = InMemorySessionStore(),
            retryPolicy = RetryPolicy.None, httpClient = api, cdnHttpClient = cdn,
            accountConnectionBudget = 8, steerEdgeHosts = false, transportMemoryBudgetBytes = 16 * MIB)
        val source = RangeReader(client, { "http://127.0.0.1:${server.address.port}/content?signature=loopback" })
        val cache = BlockCache(source, size, 8, coroutineContext, client.foregroundStreams, disk,
            blockSize = 256 * 1024, readAheadBytes = 4 * MIB, memoryCapBytes = 16 * MIB,
            scheduler = client.scheduler, memoryBudget = client.transportMemoryBudget)
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcBefore = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
        val started = TimeSource.Monotonic.markNow()
        try {
            withTimeout(120_000) {
                cache.warm(listOf(0L until size), StreamRole.BACKGROUND, null, durable = true).await()
            }
            disk.sync()
            val seconds = started.elapsedNow().inWholeNanoseconds / 1e9
            assertTrue(disk.missing("", listOf(0L until size)).isEmpty())
            var position = 0L
            while (position < size) {
                val bytes = assertNotNull(disk.read("", position, 256 * 1024))
                verifyContent(position, bytes)
                position += bytes.size
            }
            assertEquals(size, Files.size(output))
            assertTrue(client.transportMemoryPeakBytes <= 16 * MIB)
            assertTrue(peak.get() <= 8)
            assertEquals(0, rejected.get())
            println("LOOPBACK size=$size seconds=$seconds MBps=${size / seconds / 1e6} " +
                "peakSdkBuffer=${client.transportMemoryPeakBytes} requests=${requests.get()} " +
                "sockets=${connections.size} peakRequests=${peak.get()} " +
                "gcMs=${gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) } - gcBefore} file=$output")
        } finally {
            withContext(NonCancellable) {
                try {
                    cache.close(); source.close(); client.close()
                    withTimeout(5000) { cache.awaitClosedForTest() }
                    assertEquals(0L, client.transportMemoryUsedBytes)
                } finally {
                    try { cdn.close() } finally { api.close() }
                }
            }
        }
        }
        } finally { Files.deleteIfExists(output) }
        } finally {
            try { server.stop(0) } finally { executor.shutdownNow() }
        }
    }

    /** Positional writes and a committed bitmap; preallocated holes never count as present. */
    private class FileDisk(path: Path) : DurableBlockStore, AutoCloseable {
        private val file = RandomAccessFile(path.toFile(), "rw")
        private val lock = Mutex()
        private val committed = mutableMapOf<Long, Int>()
        override suspend fun write(file: String, offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
            lock.withLock {
                this@FileDisk.file.seek(offset)
                this@FileDisk.file.write(bytes)
                committed[offset] = bytes.size
            }
        }
        override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
            lock.withLock {
                if (committed[offset] != length) null else ByteArray(length).also {
                    this@FileDisk.file.seek(offset)
                    this@FileDisk.file.readFully(it)
                }
            }
        }
        override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = lock.withLock {
            buildList {
                for (range in ranges) {
                    var position = range.first
                    while (position <= range.last) {
                        val count = minOf(256 * 1024L, range.last - position + 1).toInt()
                        if (committed[position] != count) add(position until position + count)
                        position += count
                    }
                }
            }
        }
        suspend fun sync() = withContext(Dispatchers.IO) { lock.withLock { file.fd.sync() } }
        override fun close() { file.close() }
    }
}
