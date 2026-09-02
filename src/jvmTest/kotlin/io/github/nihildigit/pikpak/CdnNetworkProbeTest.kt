package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Measures PikPak CDN behaviour against a signed direct link. Not a regression
 * test: it needs `PIKPAK_PROBE=1` in `.env` in addition to credentials, and
 * writes a report to `build/cdn-probe-report.txt`.
 *
 * Uses the SDK only for login, file discovery and `getFile`. Every CDN request
 * goes through a dedicated OkHttp client pinned to HTTP/1.1 with the
 * per-host limit raised, so the numbers reflect the CDN and the link, not the
 * SDK's rate limiter or OkHttp's default `maxRequestsPerHost = 5`.
 *
 * Optional `.env` knobs:
 *  - PIKPAK_PROBE_FILE_ID   use this file instead of auto-picking the largest
 *  - PIKPAK_PROBE_FOLDER    root path for auto-pick (default: drive root)
 *  - PIKPAK_PROBE_SECONDS   per-measurement duration (default 15)
 *  - PIKPAK_PROBE_IDLE_SEC  idle-connection test duration (default 90, 0 skips)
 *  - PIKPAK_PROBE_MAX_CONC  highest concurrency level to test (default 8)
 */
class CdnNetworkProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = env["PIKPAK_PROBE"] == "1"
    private val measureSeconds = env["PIKPAK_PROBE_SECONDS"]?.toIntOrNull() ?: 15
    private val idleSeconds = env["PIKPAK_PROBE_IDLE_SEC"]?.toIntOrNull() ?: 90
    private val maxConcurrency = env["PIKPAK_PROBE_MAX_CONC"]?.toIntOrNull() ?: 8

    private val report = StringBuilder()
    private fun log(line: String) {
        println("[probe] $line")
        report.append(line).append('\n')
    }

    @Test
    fun `probe cdn behaviour`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        Assumptions.assumeTrue(enabled, "PIKPAK_PROBE != 1")

        val sdk = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val h1 = rawClient(listOf(Protocol.HTTP_1_1))
        val h2 = rawClient(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        try {
            sdk.login()
            val target = env["PIKPAK_PROBE_FILE_ID"]?.takeIf { it.isNotBlank() }?.let { sdk.getFile(it) }
                ?: pickLargestFile(sdk)
            Assumptions.assumeTrue(target != null, "no file found")
            val url = target!!.downloadUrl
            Assumptions.assumeTrue(url != null, "file has no downloadUrl")
            log("file: ${target.name}  size=${mb(target.sizeBytes)} MB  id=${target.id}")
            log("url host: ${url!!.substringAfter("://").substringBefore("/")}")
            log("url expire param: ${url.substringAfter("expire=", "").substringBefore("&")}")
            File("build/cdn-probe-url.txt").apply { parentFile.mkdirs() }.writeText(url)
            log("")

            val etag = probeHeaders(h1, url)
            probeProtocol(h2, url)
            probeExpiredUrl(h1, url)
            if (etag != null) probeIfRange(h1, url, etag)
            log("")

            val size = target.sizeBytes
            if (env["PIKPAK_PROBE_LIMIT"] == "1") {
                probeConcurrencyLimit(h1, sdk, url, size)
                return@runBlocking
            }
            measureSingle(h1, url)
            var n = 2
            while (n <= maxConcurrency) {
                measureConcurrentOpenEnded(h1, url, size, n)
                n *= 2
            }
            measureChunked(h1, url, size, workers = 4, chunkBytes = 4L shl 20)
            measureChunked(h1, url, size, workers = 4, chunkBytes = 1L shl 20)
            measureFirstByteLatency(h1, url, size)
            if (idleSeconds > 0) probeIdleConnection(h1, url)
        } finally {
            h1.close()
            h2.close()
            sdk.close()
            val out = File("build/cdn-probe-report.txt")
            out.parentFile.mkdirs()
            out.writeText(report.toString())
            println("[probe] report written to ${out.absolutePath}")
        }
    }

    // ---- discovery -------------------------------------------------------

    private suspend fun pickLargestFile(sdk: PikPakClient): FileDetail? {
        var best: FileStat? = null
        suspend fun walk(parentId: String, depth: Int) {
            if (depth < 0) return
            val entries = sdk.listFiles(parentId)
            for (e in entries) {
                if (e.isFile && e.sizeBytes > (best?.sizeBytes ?: 0L)) best = e
                if (e.isFolder) walk(e.id, depth - 1)
            }
        }
        val rootPath = env["PIKPAK_PROBE_FOLDER"]?.takeIf { it.isNotBlank() }
        walk(rootPath?.let { sdk.getPathFolderId(it) } ?: "", 3)
        return best?.let { sdk.getFile(it.id) }
    }

    // ---- header / protocol probes ---------------------------------------

    private suspend fun probeHeaders(client: HttpClient, url: String): String? {
        val resp = client.prepareGet(url) {
            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { r ->
            log("1-byte range: HTTP ${r.status.value} via ${r.version}")
            for (name in listOf(
                "Accept-Ranges", "Content-Range", "Content-Length", "Content-Type", "ETag",
                "Last-Modified", "Cache-Control", "Server", "Connection", "Keep-Alive", "X-Cache", "Via", "Age",
            )) {
                r.headers[name]?.let { log("  $name: $it") }
            }
            r.bodyAsChannel().cancel(null)
            r.headers["ETag"]
        }
        return resp
    }

    private suspend fun probeProtocol(client: HttpClient, url: String) {
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { r ->
            log("with h2 offered: negotiated ${r.version}")
            r.bodyAsChannel().cancel(null)
        }
    }

    private suspend fun probeExpiredUrl(client: HttpClient, url: String) {
        if ("expire=" !in url) {
            log("expired-url probe: no expire param, skipped")
            return
        }
        val tampered = url.replace(Regex("expire=\\d+"), "expire=1000000000")
        client.prepareGet(tampered) {
            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { r ->
            val body = runCatching { readUpTo(r.bodyAsChannel(), 300) }.getOrDefault("")
            log("tampered expire: HTTP ${r.status.value}  body=${body.replace('\n', ' ').take(200)}")
        }
    }

    private suspend fun probeIfRange(client: HttpClient, url: String, etag: String) {
        suspend fun go(ifRange: String, label: String) {
            client.prepareGet(url) {
                header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
                header(HttpHeaders.Range, "bytes=1000-1999")
                header(HttpHeaders.IfRange, ifRange)
            }.execute { r ->
                log("If-Range $label: HTTP ${r.status.value}  Content-Length=${r.headers["Content-Length"]}")
                r.bodyAsChannel().cancel(null)
            }
        }
        go(etag, "matching etag")
        go("\"bogus-etag\"", "bogus etag")
    }

    // ---- throughput ------------------------------------------------------

    private suspend fun measureSingle(client: HttpClient, url: String) {
        log("--- single connection, open-ended range from 0, ${measureSeconds}s")
        val counter = AtomicLong()
        val ttfb = AtomicLong(-1)
        coroutineScope {
            val sampler = async { sample(counter) }
            drain(client, url, start = 0, end = null, counter = counter, ttfb = ttfb, seconds = measureSeconds)
            sampler.cancel(null)
        }
        log("  ttfb=${ttfb.get()} ms  avg=${rate(counter.get())} MB/s")
    }

    private suspend fun measureConcurrentOpenEnded(client: HttpClient, url: String, size: Long, n: Int) {
        log("--- $n connections, open-ended ranges at spread offsets, ${measureSeconds}s")
        val total = AtomicLong()
        val perConn = List(n) { AtomicLong() }
        coroutineScope {
            val sampler = async { sample(total) }
            (0 until n).map { i ->
                async {
                    val ttfb = AtomicLong(-1)
                    drain(client, url, start = size / n * i, end = null, counter = perConn[i], ttfb = ttfb, seconds = measureSeconds, shared = total)
                    ttfb.get()
                }
            }.awaitAll().also { log("  ttfb per conn: ${it.joinToString(" ")} ms") }
            sampler.cancel(null)
        }
        log("  per conn: ${perConn.joinToString(" ") { rate(it.get()) }} MB/s")
        log("  aggregate=${rate(total.get())} MB/s")
    }

    /** Emulates a chunk scheduler: [workers] coroutines each pull successive closed ranges. */
    private suspend fun measureChunked(client: HttpClient, url: String, size: Long, workers: Int, chunkBytes: Long) {
        log("--- $workers workers, ${chunkBytes shr 20} MB closed ranges, ${measureSeconds}s")
        val total = AtomicLong()
        val requests = AtomicLong()
        val ttfbSum = AtomicLong()
        val next = AtomicLong(0)
        val started = TimeSource.Monotonic.markNow()
        val deadline = started + measureSeconds.seconds
        coroutineScope {
            val sampler = async { sample(total) }
            (0 until workers).map {
                async {
                    while (deadline.hasNotPassedNow()) {
                        val start = next.getAndAdd(chunkBytes)
                        if (start >= size) break
                        val end = minOf(start + chunkBytes, size) - 1
                        val ttfb = AtomicLong(-1)
                        drain(client, url, start, end, AtomicLong(), ttfb, seconds = Int.MAX_VALUE, shared = total)
                        requests.incrementAndGet()
                        ttfbSum.addAndGet(ttfb.get())
                    }
                }
            }.awaitAll()
            sampler.cancel(null)
        }
        // In-flight chunks finish after the deadline, so divide by real elapsed time.
        val elapsedSec = started.elapsedNow().inWholeMilliseconds / 1000.0
        val reqs = requests.get()
        log("  requests=$reqs  mean ttfb=${if (reqs > 0) ttfbSum.get() / reqs else -1} ms  aggregate=${"%.2f".format(total.get() / 1048576.0 / elapsedSec)} MB/s over ${"%.1f".format(elapsedSec)}s")
    }

    private suspend fun measureFirstByteLatency(client: HttpClient, url: String, size: Long) {
        log("--- first-byte latency, 10 sequential 64 KB requests at random offsets")
        val samples = mutableListOf<Long>()
        repeat(10) {
            val start = (Math.random() * (size - 65536)).toLong()
            val ttfb = AtomicLong(-1)
            drain(client, url, start, start + 65535, AtomicLong(), ttfb, seconds = Int.MAX_VALUE)
            samples += ttfb.get()
        }
        log("  ttfb ms: ${samples.joinToString(" ")}  median=${samples.sorted()[samples.size / 2]}")
    }

    private suspend fun probeIdleConnection(client: HttpClient, url: String) {
        log("--- idle connection: read 1 MB, stop reading for ${idleSeconds}s, resume")
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            header(HttpHeaders.Range, "bytes=0-")
        }.execute { r ->
            val ch = r.bodyAsChannel()
            val buf = ByteArray(256 * 1024)
            var got = 0L
            while (got < 1L shl 20) {
                val n = ch.readAvailable(buf)
                if (n < 0) break
                got += n
            }
            log("  read ${got shr 10} KB, now idle")
            delay(idleSeconds.seconds)
            val mark = TimeSource.Monotonic.markNow()
            val resumed = runCatching {
                var more = 0L
                while (more < 8L shl 20) {
                    val n = ch.readAvailable(buf)
                    if (n < 0) break
                    more += n
                }
                more
            }
            resumed.onSuccess { log("  resumed after idle: read ${it shr 10} KB more in ${mark.elapsedNow().inWholeMilliseconds} ms") }
                .onFailure { log("  resume after idle FAILED: ${it::class.simpleName}: ${it.message}") }
            ch.cancel(null)
        }
    }

    // ---- concurrency limit -----------------------------------------------

    /**
     * Finds where the CDN starts answering 503, and whether the limit is
     * shared across two different files' URLs (per IP) or per URL.
     */
    private suspend fun probeConcurrencyLimit(client: HttpClient, sdk: PikPakClient, url: String, size: Long) {
        suspend fun open(urls: List<Pair<String, Long>>, perUrl: Int): Pair<Int, Int> = coroutineScope {
            val results = urls.flatMap { (u, sz) ->
                (0 until perUrl).map { i ->
                    async {
                        client.prepareGet(u) {
                            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
                            header(HttpHeaders.Range, "bytes=${sz / perUrl * i}-")
                        }.execute { r ->
                            val code = r.status.value
                            if (code == 206) {
                                val ch = r.bodyAsChannel()
                                val buf = ByteArray(64 * 1024)
                                val deadline = TimeSource.Monotonic.markNow() + 4.seconds
                                while (deadline.hasNotPassedNow() && ch.readAvailable(buf) >= 0) Unit
                                ch.cancel(null)
                            } else r.bodyAsChannel().cancel(null)
                            code
                        }
                    }
                }
            }.awaitAll()
            results.count { it == 206 } to results.count { it != 206 }
        }
        for (n in listOf(8, 9, 10, 12, 14)) {
            val (ok, bad) = open(listOf(url to size), n)
            log("$n connections on one url: ok=$ok rejected=$bad")
            delay(3000)
        }
        val other = pickLargestFileExcluding(sdk, url)
        if (other?.downloadUrl == null) {
            log("no second file for per-url test")
            return
        }
        log("second file: ${other.name} host=${other.downloadUrl!!.substringAfter("://").substringBefore("/")}")
        for (perUrl in listOf(6, 8)) {
            val (ok, bad) = open(listOf(url to size, other.downloadUrl!! to other.sizeBytes), perUrl)
            log("$perUrl per url x 2 urls (${perUrl * 2} total): ok=$ok rejected=$bad")
            delay(3000)
        }
    }

    private suspend fun pickLargestFileExcluding(sdk: PikPakClient, excludeUrl: String): FileDetail? {
        val candidates = mutableListOf<FileStat>()
        suspend fun walk(parentId: String, depth: Int) {
            if (depth < 0) return
            for (e in sdk.listFiles(parentId)) {
                if (e.isFile) candidates += e
                if (e.isFolder) walk(e.id, depth - 1)
            }
        }
        val rootPath = env["PIKPAK_PROBE_FOLDER"]?.takeIf { it.isNotBlank() }
        walk(rootPath?.let { sdk.getPathFolderId(it) } ?: "", 3)
        val fileIdInUrl = excludeUrl.substringAfter("fid=", "").substringBefore("&")
        return candidates.sortedByDescending { it.sizeBytes }
            .firstOrNull { it.id != fileIdInUrl && it.sizeBytes > 100L shl 20 }
            ?.let { sdk.getFile(it.id) }
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Streams one range into [counter] (and [shared]) for at most [seconds],
     * recording time-to-first-byte in [ttfb]. Returns bytes read.
     */
    private suspend fun drain(
        client: HttpClient,
        url: String,
        start: Long,
        end: Long?,
        counter: AtomicLong,
        ttfb: AtomicLong,
        seconds: Int,
        shared: AtomicLong? = null,
    ): Long {
        val mark = TimeSource.Monotonic.markNow()
        val deadline = mark + seconds.seconds
        return client.prepareGet(url) {
            header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
            header(HttpHeaders.Range, if (end == null) "bytes=$start-" else "bytes=$start-$end")
        }.execute { r: HttpResponse ->
            if (r.status.value != 206) {
                log("  unexpected HTTP ${r.status.value} for range $start-${end ?: ""}")
                r.bodyAsChannel().cancel(null)
                return@execute 0L
            }
            val ch = r.bodyAsChannel()
            val buf = ByteArray(256 * 1024)
            var first = true
            while (deadline.hasNotPassedNow()) {
                val n = ch.readAvailable(buf)
                if (n < 0) break
                if (first) {
                    ttfb.set(mark.elapsedNow().inWholeMilliseconds)
                    first = false
                }
                counter.addAndGet(n.toLong())
                shared?.addAndGet(n.toLong())
            }
            ch.cancel(null)
            counter.get()
        }
    }

    /** Prints a per-second throughput line so ramp-up and caps are visible. */
    private suspend fun sample(counter: AtomicLong) {
        var last = 0L
        val line = StringBuilder("  per-second MB/s:")
        try {
            repeat(measureSeconds) {
                delay(1000)
                val now = counter.get()
                line.append(' ').append("%.1f".format((now - last) / 1048576.0))
                last = now
            }
        } finally {
            log(line.toString())
        }
    }

    private suspend fun readUpTo(ch: ByteReadChannel, n: Int): String {
        val buf = ByteArray(n)
        val got = ch.readAvailable(buf)
        ch.cancel(null)
        return if (got > 0) String(buf, 0, got) else ""
    }

    private fun rate(bytes: Long) = "%.2f".format(bytes / 1048576.0 / measureSeconds)
    private fun mb(bytes: Long) = "%.1f".format(bytes / 1048576.0)

    private fun rawClient(protocols: List<Protocol>) = HttpClient(OkHttp) {
        engine {
            config {
                protocols(protocols)
                dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 })
                connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = 10L * 60 * 1000
        }
        expectSuccess = false
    }
}
