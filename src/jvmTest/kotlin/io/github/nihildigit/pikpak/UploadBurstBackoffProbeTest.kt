package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.AttemptRetries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.write
import org.junit.jupiter.api.Assumptions
import java.util.UUID
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** Live destructive probe, strictly confined to one newly created directory. */
class UploadBurstBackoffProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }

    @Test
    fun `measure small upload bursts and pending visibility`() = runBlocking<Unit> {
        Assumptions.assumeTrue(env["PIKPAK_PROBE"] == "1", "requires PIKPAK_PROBE=1")
        val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
        val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, rateLimiter = RateLimiter(64, 60.0))
        try {
            client.login()
            val folderName = "pikpak-kotlin-upload-burst-probe-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val folderId = client.createFolder("", folderName)
            println("[burst] ROOT name=$folderName id=$folderId totalBytes=18800")
            try {
                val groups = listOf(Triple("A", 1, 4), Triple("B", 3, 12), Triple("C", 6, 18),
                    Triple("D", 12, 24), Triple("E", 24, 36))
                groups.forEachIndexed { index, (name, concurrency, count) ->
                    if (index > 0) delay(3000)
                    val subFolder = client.createFolder(folderId, "group-$name-c$concurrency-n$count")
                    runGroup(client, subFolder, name, concurrency, count)
                }
            } finally {
                withContext(NonCancellable) {
                    // Never enumerate or clean unrelated account contents, including the trash.
                    client.deleteFile(folderId)
                    var gone = false
                    val deletionClock = TimeSource.Monotonic.markNow()
                    while (!gone && deletionClock.elapsedNow().inWholeMilliseconds < 15_000) {
                        try { client.getFile(folderId) } catch (e: PikPakException) {
                            if (e.httpStatus != 404) throw e
                            gone = true
                        }
                        if (!gone) delay(500)
                    }
                    check(gone) { "probe root still exists after 15s: $folderId" }
                    println("[burst] CLEANUP permanentDelete=success root404=true id=$folderId")
                }
            }
        } finally {
            client.close()
        }
    }

    private class Sample(val name: String) {
        var id: String? = null
        var startMs: Long? = null
        var ossMs: Long? = null
        var completedAtMs: Long? = null
        var failure: String? = null
        val startRetries = AttemptRetries()
        val ossRetries = AttemptRetries()
    }

    private suspend fun runGroup(client: PikPakClient, folder: String, group: String, concurrency: Int, count: Int): Unit = coroutineScope {
        // Construct and hash before timing; UUIDs force new GCIDs rather than instant uploads.
        val inputs = (0 until count).map { i ->
            val uuid = UUID.randomUUID().toString()
            val name = if (i % 2 == 0) "source-$i.magnet" else ".piko-vault-$i.json"
            val text = if (i % 2 == 0) "magnet:?xt=urn:btih:${uuid.replace("-", "")}00000000&dn=$uuid\n"
                else "{\"probe\":\"$uuid\",\"source\":\"upload-burst-$group-$i\"}\n"
            val payload = text.padEnd(199, ' ').plus("\n").encodeToByteArray()
            val hash = PikPakHash.fromSource(Buffer().apply { write(payload) }, payload.size.toLong())
            Triple(Sample(name), payload, hash)
        }
        val gate = Semaphore(concurrency)
        val clock = TimeSource.Monotonic.markNow()
        fun now() = clock.elapsedNow().inWholeMilliseconds
        coroutineScope {
            inputs.map { (sample, payload, hash) -> async {
                gate.withPermit {
                    var stage = "start"
                    try {
                        withTimeout(120_000) {
                            val start = now()
                            val pending = try {
                                withContext(sample.startRetries) {
                                    client.startUpload(folder, sample.name, payload.size.toLong(), hash)
                                }
                            } finally { sample.startMs = now() - start }
                            check(pending is UploadStart.Pending) { "unexpected instant upload for unique content" }
                            sample.id = pending.session.fileId
                            stage = "oss"
                            val oss = now()
                            try {
                                withContext(sample.ossRetries) {
                                    client.continueUpload(pending.session, open = { offset ->
                                        check(offset == 0L)
                                        Buffer().apply { write(payload) }
                                    }, freshSession = true)
                                }
                                sample.completedAtMs = now()
                            } finally { sample.ossMs = now() - oss }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Avoid logging signed URLs, credentials or response bodies.
                        sample.failure = "$stage:${e.javaClass.simpleName}:http=${(e as? PikPakException)?.httpStatus}:code=${(e as? PikPakException)?.errorCode}"
                    }
                    println("[burst] FILE group=$group name=${sample.name} id=${sample.id} startMs=${sample.startMs} ossMs=${sample.ossMs} completedAtMs=${sample.completedAtMs} failure=${sample.failure} startRetry=${retries(sample.startRetries)} ossRetry=${retries(sample.ossRetries)}")
                }
            } }.awaitAll()
        }
        val wall = now()
        val samples = inputs.map { it.first }
        val successful = samples.filter { it.completedAtMs != null }
        val completed = mutableMapOf<String, Long>()
        val pendingSeen = mutableSetOf<String>()
        val detailChecked = mutableSetOf<String>()
        var phases = emptyMap<String, String>()
        var pollIndex = 0
        val pollingRetries = AttemptRetries()
        val detailJobs = mutableListOf<Job>()
        val detailRetries = mutableListOf<AttemptRetries>()
        while (true) {
            val requestAt = now() - wall
            phases = withContext(pollingRetries) { client.listFiles(folder) }.associate { it.id to it.phase }
            val observedAt = now()
            val elapsed = observedAt - wall
            successful.forEach { sample ->
                val id = sample.id!!
                if (phases[id] == TaskPhase.PENDING) pendingSeen += id
                if (phases[id] == TaskPhase.COMPLETE) completed.putIfAbsent(id, observedAt - sample.completedAtMs!!)
            }
            val complete = successful.count { phases[it.id] == TaskPhase.COMPLETE }
            val pending = successful.count { phases[it.id] == TaskPhase.PENDING }
            val missing = successful.count { it.id !in phases }
            println("[burst] POLL group=$group index=$pollIndex requestAtMs=$requestAt observedAtMs=$elapsed complete=$complete pending=$pending missing=$missing other=${successful.size - complete - pending - missing}")
            if (pollIndex == 0) println("[burst] INITIAL group=$group complete=$complete pending=$pending missing=$missing responseMs=$elapsed")
            successful.filter { phases[it.id] == TaskPhase.PENDING && observedAt - it.completedAtMs!! > 5000 && it.id !in detailChecked }.forEach {
                detailChecked += it.id!!
                val retries = AttemptRetries()
                detailRetries += retries
                val listPhase = phases[it.id]
                // Do not stall the 500ms list polling cadence behind per-file detail requests.
                detailJobs += launch {
                    val detail = withContext(retries) { client.getFile(it.id!!) }
                    println("[burst] DETAIL group=$group name=${it.name} lagMs=${now() - it.completedAtMs!!} listPhase=$listPhase detailPhase=${detail.phase}")
                }
            }
            if (complete == successful.size || elapsed >= 30_000) break
            delay(minOf(500L, 30_000 - elapsed))
            pollIndex++
        }
        successful.forEach {
            println("[burst] VISIBILITY group=$group name=${it.name} sawPending=${it.id in pendingSeen} completeLagMs=${completed[it.id]} finalPhase=${phases[it.id] ?: "MISSING"}")
        }
        detailJobs.joinAll()
        val allRetries = samples.flatMap { listOf(it.startRetries, it.ossRetries) } + pollingRetries + detailRetries
        println("[burst] SUMMARY group=$group concurrency=$concurrency count=$count success=${successful.size} failures=${samples.count { it.failure != null }} uploadWallMs=$wall startMs=${stats(samples.mapNotNull { it.startMs })} ossCompleteMs=${stats(samples.mapNotNull { it.ossMs })} completeLagMs=${stats(completed.values.toList())} pendingFlipLagMs=${stats(completed.filterKeys { it in pendingSeen }.values.toList())} finalPending=${successful.count { phases[it.id] == TaskPhase.PENDING }} finalMissing=${successful.count { it.id !in phases }} retries429=${allRetries.sumOf { it.rateLimited }} retries5xx=${allRetries.sumOf { it.serverErrors }} retriesTransport=${allRetries.sumOf { it.transport }} retryWaitMs=${allRetries.sumOf { it.waited.inWholeMilliseconds }}")
        // Visibility failures are observations, not test failures; unexpected instant uploads/errors are failures.
        assertEquals(count, successful.size, "group $group uploads failed")
    }

    private fun retries(r: AttemptRetries) = "429:${r.rateLimited}/5xx:${r.serverErrors}/transport:${r.transport}/waitMs:${r.waited.inWholeMilliseconds}"

    private fun stats(values: List<Long>): String {
        if (values.isEmpty()) return "n=0/P50=NA/P90=NA/Max=NA"
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)]
        return "n=${sorted.size}/P50=${percentile(0.5)}/P90=${percentile(0.9)}/Max=${sorted.last()}"
    }
}
