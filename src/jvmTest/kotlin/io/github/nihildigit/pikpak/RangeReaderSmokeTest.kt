package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import org.junit.jupiter.api.Assumptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * End-to-end check of the pieces animeko's playback path depends on, against
 * the live account. Self-skips without `.env` credentials.
 *
 * Every HTTP exchange goes through one OkHttp interceptor so the test can
 * assert on traffic the SDK is supposed to avoid — a second token grant, a 503
 * reaching the caller — rather than only on return values.
 */
class RangeReaderSmokeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val folder = env["PIKPAK_SMOKE_FOLDER"] ?: "Animeko-Playing"

    /**
     * The release the smoke reads from. A magnet rather than a file id so the
     * test can set itself up on any account: it is submitted once, and every
     * later run finds the finished task by info hash and reuses its file.
     * Override with `PIKPAK_SMOKE_MAGNET` to read from something else.
     */
    private val magnet = env["PIKPAK_SMOKE_MAGNET"]?.takeIf { it.startsWith("magnet:") } ?: TestFixtures.ARCH_ISO_MAGNET
    private val infoHash = magnet.substringAfter("urn:btih:").substringBefore('&').lowercase()

    private val paths = ConcurrentHashMap<String, AtomicInteger>()
    private val statuses = ConcurrentHashMap<Int, AtomicInteger>()

    private fun countingClient(budget: Int): HttpClient = HttpClient(OkHttp) {
        engine {
            addInterceptor { chain ->
                paths.computeIfAbsent(chain.request().url.encodedPath) { AtomicInteger() }.incrementAndGet()
                val response = chain.proceed(chain.request())
                statuses.computeIfAbsent(response.code) { AtomicInteger() }.incrementAndGet()
                response
            }
            config {
                protocols(listOf(Protocol.HTTP_1_1))
                connectionPool(ConnectionPool(budget * 2, 5, TimeUnit.MINUTES))
                dispatcher(
                    Dispatcher().apply {
                        maxRequestsPerHost = budget
                        maxRequests = budget * 4
                    },
                )
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }

    private fun count(path: String) = paths[path]?.get() ?: 0

    @Test
    fun `smoke the range reader against the live cdn`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val budget = 8
        val http = countingClient(budget)
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            connectionBudget = budget,
        )
        var uploaded: String? = null
        try {
            // 1. A second login must not go back to the token endpoint.
            client.login()
            val tokensAfterFirst = count("/v1/auth/token") + count("/v1/auth/signin")
            client.login()
            assertEquals(
                tokensAfterFirst,
                count("/v1/auth/token") + count("/v1/auth/signin"),
                "the second login must reuse the in-memory session",
            )
            println("[smoke] auth calls after two logins: signin=${count("/v1/auth/signin")} token=${count("/v1/auth/token")}")

            val parentId = client.getOrCreateDeepFolderId("", folder)
            val target = resolveRelease(client, parentId)
            val detail = client.getFile(target.id)
            val url = detail.downloadUrl!!
            println("[smoke] file=${detail.name} size=${detail.sizeBytes} links=${detail.links.keys}")
            println("[smoke] expiresAt=${detail.octetStream.expiresAt} queryKeys=${queryKeys(url)}")
            Assumptions.assumeTrue(detail.sizeBytes >= 64L * 1024 * 1024, "release is under 64 MB, too small to time")

            // 2. Eight concurrent reads must all land, with no 503 reaching us.
            val readerBudget = budget
            val reader = client.rangeReader(target.id, connectionBudget = readerBudget)
            val before503 = statuses[503]?.get() ?: 0
            val chunk = 256L * 1024
            val results = coroutineScope {
                (0 until 8).map { i ->
                    async { reader.readBytes(i * chunk, chunk, priority = if (i == 0) 10 else 0).size }
                }.awaitAll()
            }
            assertTrue(results.all { it == chunk.toInt() }, "every concurrent read must deliver a full chunk")
            val seen503 = (statuses[503]?.get() ?: 0) - before503
            println("[smoke] 8 concurrent reads OK; wire 503s during the burst = $seen503; stats=${reader.stats.value}")

            // 3. Throughput: one connection against eight, same file, disjoint
            // offsets so neither run is served from a warm edge cache.
            val single = timeRead(reader, offset = 16L shl 20, total = 8L shl 20, parts = 1)
            val fanned = timeRead(reader, offset = 64L shl 20, total = 32L shl 20, parts = 8)
            println("[smoke] throughput: 1 conn = ${"%.2f".format(single)} MB/s, 8 conn = ${"%.2f".format(fanned)} MB/s, x${"%.1f".format(fanned / single)}")
            // The stats count the 503s that reached the reader; the ones the HTTP layer retried
            // underneath are on each RangeAttempt, for a run that needs them.
            println("[smoke] reader stats: ${reader.stats.value}")
            // Per-connection throughput is bounded by the round trip, not by a
            // server-side rate limit: a distant route gives well under 1 MB/s on
            // one connection and scales linearly with fan-out, while a short one
            // saturates the line on a single connection and leaves fan-out
            // nothing to win. Demanding a multiple unconditionally makes this
            // test assert the route rather than the reader, and fail on the good
            // one. So the multiple is only required where the reader is supposed
            // to help; elsewhere the bar is that fanning out does not cost.
            if (single < FAN_OUT_USEFUL_BELOW_MB_S) {
                assertTrue(
                    fanned > single * 2,
                    "on a round-trip-bound route eight connections must beat one by a wide margin: " +
                        "$fanned vs $single MB/s",
                )
            } else {
                // On a saturated route the two runs differ by network noise more
                // than by fan-out: they run seconds apart, over different byte
                // counts, against an edge whose own load is moving. The same
                // machine produced both 1.3x faster and 1.7x slower within an
                // hour. Nothing finer than "fan-out did not collapse" is
                // measurable here, and asserting finer only produces a test that
                // fails on a good link.
                assertTrue(
                    fanned >= single * 0.5,
                    "this route saturates on one connection, so only a collapse is detectable here: " +
                        "$fanned vs $single MB/s",
                )
            }
            reader.close()

            // 4. A tampered signature must be recovered from via the provider.
            val tampered = tamperExpiry(url)
            Assumptions.assumeTrue(tampered != null, "download URL has no recognizable expiry parameter")
            var handedStale = false
            val refreshingReader = RangeReader(
                client = client,
                urlProvider = { request ->
                    if (!handedStale) {
                        handedStale = true
                        println("[smoke] handing out the tampered URL first")
                        tampered!!
                    } else {
                        println("[smoke] provider asked again: $request")
                        client.getFile(target.id).downloadUrl!!
                    }
                },
                connectionBudget = 2,
            )
            val recovered = refreshingReader.readBytes(0, 64 * 1024)
            assertEquals(64 * 1024, recovered.size)
            assertEquals(1, refreshingReader.stats.value.urlRefreshes, "exactly one refresh")
            println("[smoke] recovered ${recovered.size} bytes after one refresh; stats=${refreshingReader.stats.value}")
            refreshingReader.close()

            // 5. parallelDownloadFromUrl still writes a whole small file.
            // Downloading a 1.4 GB episode four ways proves nothing the small
            // case does not, and costs minutes; upload a scratch file when the
            // folder holds nothing small.
            val existingSmall = client.listFiles(parentId).filter { it.isFile && it.sizeBytes in 1L..(8L * 1024 * 1024) }
                .minByOrNull { it.sizeBytes }
            val smallId = existingSmall?.id ?: uploadScratchFile(client, parentId).also { uploaded = it }
            // A just-uploaded file has no signed link until PikPak finishes
            // ingesting it, so getFile can legitimately answer with none.
            var smallDetail = client.getFile(smallId)
            repeat(10) {
                if (smallDetail.downloadUrl != null) return@repeat
                delay(2.seconds)
                smallDetail = client.getFile(smallId)
            }
            assertTrue(smallDetail.downloadUrl != null, "no download link for $smallId after 20s")
            val dest = Path(SystemFileSystem.resolve(Path("build")).toString(), "smoke-$smallId.bin")
            SystemFileSystem.delete(dest, mustExist = false)
            val written = client.fileHandle(smallDetail).use { handle ->
                handle.downloadTo(dest = dest, totalSize = smallDetail.sizeBytes, concurrency = 4)
            }
            val onDisk = SystemFileSystem.metadataOrNull(dest)?.size ?: -1
            println("[smoke] downloadTo ${smallDetail.name}: returned=$written onDisk=$onDisk expected=${smallDetail.sizeBytes}")
            assertEquals(smallDetail.sizeBytes, written)
            assertEquals(smallDetail.sizeBytes, onDisk)
            SystemFileSystem.delete(dest, mustExist = false)

            println("[smoke] status histogram: ${statuses.mapValues { it.value.get() }}")
        } finally {
            runCatching { uploaded?.let { client.batchTrash(listOf(it)) } }
            client.close()
            http.close()
        }
    }

    /**
     * The video file the magnet produced, submitting it if this account has
     * never fetched it. Finished tasks are matched by info hash in the task
     * list, because PikPak has no lookup by URL and the file's name is
     * whatever the torrent called it.
     */
    private suspend fun resolveRelease(client: PikPakClient, parentId: String): FileStat {
        // The default `with=reference_resource` overlays the output file's
        // state, so a task whose file was since deleted reads as ERROR here
        // and is not reused. Another client can still delete the file between
        // this listing and getFile, hence the 404 fallback.
        val done = client.listOfflineTasks(phaseFilter = TaskPhase.COMPLETE).tasks
            .firstOrNull { task -> task.params.values.any { it.lowercase().contains(infoHash) } && task.fileId.isNotEmpty() }
        val reused = done?.let { task ->
            try {
                client.getFile(task.fileId)
            } catch (e: PikPakException) {
                if (e.httpStatus == 404) null else throw e
            }
        }
        val produced = reused ?: client.getFile(submitAndAwait(client, parentId))
        println("[smoke] task output ${produced.name} kind=${produced.kind} (${if (reused != null) "reused task ${done!!.id}" else "fresh task"})")
        val video = if (produced.kind == FileKind.FOLDER) {
            client.listFiles(produced.id).filter { it.isFile }.maxByOrNull { it.sizeBytes }
                ?: error("task folder ${produced.name} holds no files")
        } else {
            FileStat(id = produced.id, name = produced.name, kind = produced.kind, size = produced.size)
        }
        return video
    }

    private suspend fun submitAndAwait(client: PikPakClient, parentId: String): String {
        val task = when (val result = client.createUrlFile(parentId, magnet)) {
            is CreateUrlResult.Queued -> result.task
            is CreateUrlResult.InstantComplete ->
                return result.file?.id ?: error("instant complete without a file node: ${result.raw}")
        }
        println("[smoke] submitted task ${task.id} phase=${task.phase} fileId=${task.fileId}")
        val deadline = TimeSource.Monotonic.markNow() + 10.minutes
        var latest = task
        while (latest.phase !in TaskPhase.TERMINAL) {
            check(deadline.hasNotPassedNow()) { "task ${task.id} still ${latest.phase} after 10 minutes" }
            delay(3.seconds)
            latest = client.getTask(task.id)
            println("[smoke] task ${latest.id} phase=${latest.phase} progress=${latest.progress} message=${latest.message}")
        }
        check(latest.phase == TaskPhase.COMPLETE) { "task ${task.id} ended in ${latest.phase}: ${latest.message}" }
        check(latest.fileId.isNotEmpty()) { "task ${task.id} completed without a file id" }
        return latest.fileId
    }

    /** Reads [total] bytes from [offset] as [parts] concurrent equal ranges and returns MB/s. */
    private suspend fun timeRead(reader: RangeReader, offset: Long, total: Long, parts: Int): Double {
        val partSize = total / parts
        val started = TimeSource.Monotonic.markNow()
        val delivered = coroutineScope {
            (0 until parts).map { i ->
                async {
                    var n = 0L
                    reader.read(offset + i * partSize, partSize) { channel ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read == -1) break
                            n += read
                        }
                    }
                    n
                }
            }.awaitAll().sum()
        }
        val seconds = started.elapsedNow().inWholeMilliseconds / 1000.0
        assertEquals(total, delivered, "timed read must deliver every byte")
        return delivered / (1024.0 * 1024.0) / seconds
    }

    /** Uploads 2 MiB of deterministic bytes and returns the new file id. */
    private suspend fun uploadScratchFile(client: PikPakClient, parentId: String): String {
        val local = Path(SystemFileSystem.resolve(Path("build")).toString(), "smoke-source.bin")
        SystemFileSystem.delete(local, mustExist = false)
        SystemFileSystem.sink(local).buffered().use { sink ->
            val block = ByteArray(64 * 1024) { (it % 251).toByte() }
            repeat(32) { sink.write(block, 0, block.size) }
        }
        val result = client.upload(parentId, local)
        SystemFileSystem.delete(local, mustExist = false)
        println("[smoke] uploaded scratch file ${result.fileId} instant=${result.instantUpload}")
        return result.fileId
    }

    private fun queryKeys(url: String): List<String> =
        url.substringAfter('?', "").split('&').mapNotNull { it.substringBefore('=').takeIf { k -> k.isNotEmpty() } }

    /** Rewrites whichever expiry parameter the signed URL carries so the CDN rejects it. */
    private fun tamperExpiry(url: String): String? {
        for (key in listOf("expire", "e", "exp", "t")) {
            val marker = "$key="
            val at = url.indexOf("?$marker").takeIf { it >= 0 }?.plus(1)
                ?: url.indexOf("&$marker").takeIf { it >= 0 }?.plus(1)
                ?: continue
            val valueStart = at + marker.length
            val valueEnd = url.indexOf('&', valueStart).takeIf { it >= 0 } ?: url.length
            val original = url.substring(valueStart, valueEnd)
            val broken = original.dropLast(1) + if (original.last() == '0') '1' else '0'
            return url.substring(0, valueStart) + broken + url.substring(valueEnd)
        }
        return null
    }

    private companion object {
        /**
         * Single-connection throughput below which the route is taken to be
         * round-trip-bound and fan-out is expected to multiply it. Measured
         * points either side: 0.94 MB/s on a route that scaled linearly to the
         * 8-connection cap, 4.7 MB/s on one that gained about 30 percent.
         */
        const val FAN_OUT_USEFUL_BELOW_MB_S = 2.0
    }
}
