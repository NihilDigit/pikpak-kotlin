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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Answers one question: does PikPak cap concurrent range connections per
 * account, or only per signed URL?
 *
 * The per-URL cap is known — a ninth connection to one URL is answered 503.
 * What that leaves open is whether a client may hold 8 on each of several
 * URLs at once, which is what playing one episode while caching others does.
 * If there is no account-wide cap, foreground playback can simply be given
 * more connections instead of being scheduled against the cache downloads.
 *
 * Method: offline-download a season pack (one magnet, many episodes), resolve
 * a signed URL per episode, then walk a ladder of (files x connections per
 * file) and record what the CDN answers. A rejection shows up as a status
 * other than 206 — the counts per status are what the ladder is for, the
 * throughput numbers are secondary.
 *
 * The client-side dispatcher is raised above the largest rung on purpose: with
 * OkHttp's defaults the queue would be the thing being measured.
 *
 * Live test, opt in with `PIKPAK_MULTIFILE_PROBE=1`. Leaves the downloaded
 * pack in place so repeat runs skip the wait; set `PIKPAK_MULTIFILE_CLEANUP=1`
 * to trash it at the end.
 */
class MultiFileConcurrencyProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = env["PIKPAK_MULTIFILE_PROBE"] == "1"
    private val cleanup = env["PIKPAK_MULTIFILE_CLEANUP"] == "1"

    private val magnet = env["PIKPAK_MULTIFILE_MAGNET"]?.takeIf { it.isNotBlank() }
        ?: "magnet:?xt=urn:btih:d13e45db4761ac1e7fca0637287119e5342c75c7"

    /** Where the pack lands. Fixed so a second run reuses the first run's download. */
    private val folderName = env["PIKPAK_MULTIFILE_FOLDER"]?.takeIf { it.isNotBlank() }
        ?: "pikpak-kotlin-multifile-probe"

    private val connectionsPerFile = env["PIKPAK_MULTIFILE_CONNS"]?.toIntOrNull() ?: 8
    private val measureSeconds = env["PIKPAK_MULTIFILE_SECONDS"]?.toIntOrNull() ?: 12
    private val readyTimeoutSeconds = env["PIKPAK_MULTIFILE_READY_SEC"]?.toIntOrNull() ?: 300

    /** File counts to walk, in order. One rung per entry. */
    private val ladder = (env["PIKPAK_MULTIFILE_LADDER"]?.takeIf { it.isNotBlank() } ?: "1,2,4,8,12")
        .split(',').mapNotNull { it.trim().toIntOrNull() }

    /**
     * Idle time before each rung. The CDN keeps counting a connection for a
     * while after the client drops it, so back-to-back rungs make an early one
     * look rejected when it was the previous rung's slots that were still held.
     */
    private val cooldownSeconds = env["PIKPAK_MULTIFILE_COOLDOWN_SEC"]?.toIntOrNull() ?: 30

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[multifile] $line")
        report.append(line).append('\n')
    }

    @Test
    fun `probe account-wide concurrency across several files`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        Assumptions.assumeTrue(enabled, "PIKPAK_MULTIFILE_PROBE != 1")

        val sdk = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val http = rawClient()
        var folderId: String? = null
        try {
            sdk.login()
            folderId = sdk.getOrCreateDeepFolderId(parentId = "", path = folderName)
            log("folder: $folderName ($folderId)")

            val files = ensurePack(sdk, folderId)
            Assumptions.assumeTrue(files.isNotEmpty(), "pack produced no video files within ${readyTimeoutSeconds}s")
            log("episodes: ${files.size}")
            files.forEach { log("  ${mb(it.sizeBytes)} MB  ${it.name}") }
            log("")

            val urls = files.mapNotNull { stat ->
                val detail = sdk.getFile(stat.id)
                detail.downloadUrl?.let { stat.name to it }
            }
            Assumptions.assumeTrue(urls.isNotEmpty(), "no episode produced a downloadUrl")
            log("resolved ${urls.size} signed urls")
            log("hosts: ${urls.map { it.second.substringAfter("://").substringBefore("/") }.distinct()}")
            log("")

            for (fileCount in ladder) {
                if (fileCount > urls.size) {
                    log("skipping rung of $fileCount files, only ${urls.size} resolved")
                    continue
                }
                log("cooling down ${cooldownSeconds}s")
                delay(cooldownSeconds.seconds)
                rung(http, urls, fileCount)
            }
        } finally {
            if (cleanup && folderId != null) {
                runCatching { sdk.batchTrash(listOf(folderId)) }
                    .onFailure { log("cleanup failed: $it") }
            } else if (folderId != null) {
                log("")
                log("left '$folderName' in place; set PIKPAK_MULTIFILE_CLEANUP=1 to trash it")
            }
            http.close()
            sdk.close()
            File("build/multifile-probe-report.txt").apply {
                parentFile.mkdirs()
                writeText(report.toString())
                println("[multifile] report written to $absolutePath")
            }
        }
    }

    /**
     * Submits [magnet] into [folderId] unless the folder already holds video
     * files, then waits for them to show up.
     *
     * PikPak answers a magnet it already has instantly, so the common path
     * costs one request. A pack nobody has uploaded is really fetched from the
     * swarm, which is why this polls rather than assuming.
     */
    private suspend fun ensurePack(sdk: PikPakClient, folderId: String): List<FileStat> {
        videoFiles(sdk, folderId).let { if (it.isNotEmpty()) return it.also { _ -> log("pack already present, skipping submit") } }

        log("submitting magnet")
        when (val result = sdk.createUrlFile(parentId = folderId, url = magnet)) {
            is CreateUrlResult.InstantComplete -> log("  accepted, no task (already in PikPak's cache)")
            is CreateUrlResult.Queued -> log("  queued task ${result.task.id}, phase=${result.task.phase}")
        }

        val started = TimeSource.Monotonic.markNow()
        val deadline = started + readyTimeoutSeconds.seconds
        var lastPhase = ""
        while (deadline.hasNotPassedNow()) {
            val found = videoFiles(sdk, folderId)
            if (found.isNotEmpty()) {
                log("  ready after ${started.elapsedNow().inWholeSeconds}s")
                return found
            }
            val task = runCatching { sdk.listOfflineTasks().tasks.firstOrNull { it.params["parent_id"] == folderId } }
                .getOrNull()
            if (task != null && "${task.phase}/${task.progress}" != lastPhase) {
                lastPhase = "${task.phase}/${task.progress}"
                log("  phase=${task.phase} progress=${task.progress}%")
            }
            delay(3.seconds)
        }
        return emptyList()
    }

    /** Video files anywhere under [parentId], deepest folders included. */
    private suspend fun videoFiles(sdk: PikPakClient, parentId: String): List<FileStat> {
        val out = mutableListOf<FileStat>()
        suspend fun walk(id: String, depth: Int) {
            if (depth < 0) return
            for (entry in sdk.listFiles(id)) {
                if (entry.isFolder) walk(entry.id, depth - 1)
                else if (entry.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS) out += entry
            }
        }
        walk(parentId, 4)
        return out.sortedBy { it.name }
    }

    /**
     * Holds [connectionsPerFile] open-ended ranges on each of the first
     * [fileCount] urls at once and reports what came back.
     *
     * Ranges start at spread offsets so the CDN cannot serve them all from one
     * position, and stay open for [measureSeconds] rather than reading a fixed
     * size: a rejection has to be visible as a status, not as a slow read.
     */
    private suspend fun rung(http: HttpClient, urls: List<Pair<String, String>>, fileCount: Int) {
        val target = urls.take(fileCount)
        val total = fileCount * connectionsPerFile
        log("--- $fileCount files x $connectionsPerFile connections = $total, ${measureSeconds}s")

        val bytes = AtomicLong()
        val statuses = ConcurrentHashMap<Int, AtomicLong>()
        val midReadFailures = AtomicLong()
        val perFile = target.map { AtomicLong() }

        coroutineScope {
            target.flatMapIndexed { fileIndex, (_, url) ->
                (0 until connectionsPerFile).map { connIndex ->
                    async {
                        // Spread the starts so concurrent reads of one file do not overlap.
                        val start = (connIndex.toLong() * 64L shl 20)
                        drain(http, url, start, bytes, perFile[fileIndex], statuses, midReadFailures)
                    }
                }
            }.awaitAll()
        }

        val seen = statuses.entries.sortedBy { it.key }.joinToString(" ") {
            (if (it.key == -1) "handshake-refused" else it.key.toString()) + "x${it.value.get()}"
        }
        val accepted = statuses[206]?.get() ?: 0
        log("  admitted: $accepted of $total")
        log("  outcomes: $seen")
        if (midReadFailures.get() > 0) log("  dropped mid-read (already admitted): ${midReadFailures.get()}")
        log("  per file: ${perFile.joinToString(" ") { rate(it.get()) }} MB/s")
        log("  aggregate: ${rate(bytes.get())} MB/s")
        log("")
    }

    private suspend fun drain(
        client: HttpClient,
        url: String,
        start: Long,
        shared: AtomicLong,
        perFile: AtomicLong,
        statuses: ConcurrentHashMap<Int, AtomicLong>,
        midReadFailures: AtomicLong,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + measureSeconds.seconds
        // Each connection contributes exactly one outcome. A body that dies after the
        // response arrived is not a refusal — it was admitted — so it is counted apart
        // instead of landing in the status table twice.
        var admitted = false
        try {
            client.prepareGet(url) {
                header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
                header(HttpHeaders.Range, "bytes=$start-")
            }.execute { r: HttpResponse ->
                statuses.computeIfAbsent(r.status.value) { AtomicLong() }.incrementAndGet()
                admitted = true
                if (r.status.value != 206) {
                    r.bodyAsChannel().cancel(null)
                    return@execute
                }
                val ch = r.bodyAsChannel()
                val buf = ByteArray(256 * 1024)
                while (deadline.hasNotPassedNow()) {
                    val n = ch.readAvailable(buf)
                    if (n < 0) break
                    shared.addAndGet(n.toLong())
                    perFile.addAndGet(n.toLong())
                }
                ch.cancel(null)
            }
        } catch (e: Exception) {
            if (admitted) {
                midReadFailures.incrementAndGet()
            } else {
                // Refused before a status line: the CDN dropped the TLS handshake rather
                // than answering 503. Same meaning, different layer.
                statuses.computeIfAbsent(-1) { AtomicLong() }.incrementAndGet()
                log("  refused at handshake: ${e::class.simpleName} ${e.message?.take(60)}")
            }
        }
    }

    private fun rate(bytes: Long): String =
        ((bytes.toDouble() / measureSeconds) / (1 shl 20)).let { "%.2f".format(it) }

    private fun mb(bytes: Long): String = "%.0f".format(bytes.toDouble() / (1 shl 20))

    private fun rawClient() = HttpClient(OkHttp) {
        engine {
            config {
                protocols(listOf(Protocol.HTTP_1_1))
                // Above the largest rung, so the ladder measures the CDN and not OkHttp's queue.
                dispatcher(Dispatcher().apply { maxRequests = 256; maxRequestsPerHost = 256 })
                connectionPool(ConnectionPool(256, 5, TimeUnit.MINUTES))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = 10L * 60 * 1000
        }
        expectSuccess = false
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "flv", "wmv", "webm", "rmvb", "ts", "m2ts")
    }
}
