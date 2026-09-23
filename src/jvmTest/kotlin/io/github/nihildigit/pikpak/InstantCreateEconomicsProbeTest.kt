package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a file object created from a gcid actually costs, so a consumer can
 * decide whether to keep one or rebuild it per playback.
 *
 * Three questions, none answerable from the API surface:
 *  1. Does an instant copy consume quota? If it does, a consumer that keeps
 *     its file objects fills the user's drive with content it never uploaded,
 *     and a full drive breaks playback rather than slowing it.
 *  2. Does creating and deleting the same content repeatedly draw a penalty?
 *     A consumer that rebuilds per playback does this tens of times an evening.
 *  3. Does a second instantCreate of one gcid deduplicate? If PikPak returns
 *     the existing object, keeping and rebuilding cost the same and the whole
 *     question is moot.
 *
 * Writes `build/instant-create-probe.txt`. Opt in with PIKPAK_PROBE=1; it
 * creates and deletes real files in the account's drive.
 */
class InstantCreateEconomicsProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[probe] $line")
        report.appendLine(line)
    }

    @Test
    fun `probe what an instant copy costs`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        val created = mutableListOf<String>()
        var folderId: String? = null
        try {
            client.login()
            log("=== instant-create economics, ${Clock.System.now()} ===")

            val q0 = client.getQuota().quota
            log(
                "quota: used ${gib(q0.usageBytes)} of ${gib(q0.limitBytes)}, " +
                        "trash ${gib(q0.usageInTrash.toLongOrNull() ?: 0L)}, free ${gib(q0.remainingBytes)}",
            )

            val (label, magnet) = pickPack(client) ?: run {
                Assumptions.assumeTrue(false, "PikPak indexes none of the sample packs")
                return@runBlocking
            }
            log("pack: $label")

            val resolveMark = TimeSource.Monotonic.markNow()
            val resource = client.resolveMagnet(magnet)!!
            log("resolveMagnet: ${resolveMark.elapsedNow().inWholeMilliseconds} ms, ${resource.files.size} files")

            val target = resource.files
                .filter { it.gcid != null && VIDEO_EXTENSIONS.any { ext -> it.name.endsWith(ext, ignoreCase = true) } }
                .maxByOrNull { it.size }
                ?: resource.files.filter { it.gcid != null }.maxByOrNull { it.size }!!
            log("target: ${target.path} (${gib(target.size)})")
            if (target.size > q0.remainingBytes) {
                log("WARNING: target is larger than the free quota; a create failure below is that, not a bug")
            }

            folderId = client.createFolder(parentId = "", name = "probe-${Clock.System.now().toEpochMilliseconds()}")

            // Q1 --- does one instant copy consume quota?
            log("")
            log("--- Q1: quota effect of one instant copy ---")
            val firstMark = TimeSource.Monotonic.markNow()
            val first = client.instantCreate(target, parentId = folderId)
            val firstMs = firstMark.elapsedNow().inWholeMilliseconds
            created += first
            log("instantCreate: $firstMs ms -> $first")
            val (q1, settleMs) = awaitQuotaChange(client, q0.usageBytes)
            log(
                "usage ${gib(q0.usageBytes)} -> ${gib(q1.usageBytes)} " +
                        "(delta ${gib(q1.usageBytes - q0.usageBytes)}, file ${gib(target.size)}, settled after $settleMs ms)",
            )
            log(
                if (q1.usageBytes - q0.usageBytes >= target.size / 2) "VERDICT: an instant copy consumes quota in full"
                else "VERDICT: an instant copy does NOT consume quota (delta is not the file size)",
            )

            // Q3 --- does a second create of the same gcid deduplicate?
            log("")
            log("--- Q3: a second instantCreate of the same gcid ---")
            val sameNameMark = TimeSource.Monotonic.markNow()
            val sameName = runCatching { client.instantCreate(target, parentId = folderId) }
            sameName.onSuccess { id ->
                created += id
                log("same parent, same name: ${sameNameMark.elapsedNow().inWholeMilliseconds} ms -> $id")
                log(if (id == first) "  same file id: PikPak returned the existing object" else "  NEW file id: a second object exists")
            }.onFailure { log("same parent, same name: failed: ${describe(it)}") }

            val renamedMark = TimeSource.Monotonic.markNow()
            val renamed = runCatching { client.instantCreate(target, parentId = folderId, name = "copy-${target.name}") }
            renamed.onSuccess { id ->
                created += id
                log("same parent, new name: ${renamedMark.elapsedNow().inWholeMilliseconds} ms -> $id")
            }.onFailure { log("same parent, new name: failed: ${describe(it)}") }

            val q2 = client.getQuota().quota
            log("usage after the extra copies: ${gib(q2.usageBytes)} (delta from Q1 ${gib(q2.usageBytes - q1.usageBytes)})")

            // Q1b --- does deleting give the quota back, and does it land in the trash?
            log("")
            log("--- Q1b: deleting the copies ---")
            client.batchDelete(created.toList())
            val cleared = created.toList()
            created.clear()
            val (q3, freeMs) = awaitQuotaChange(client, q2.usageBytes)
            log(
                "batchDelete of ${cleared.size} -> usage ${gib(q2.usageBytes)} -> ${gib(q3.usageBytes)} " +
                        "(settled after $freeMs ms), trash ${gib(q3.usageInTrash.toLongOrNull() ?: 0L)}",
            )
            log(
                if (q3.usageBytes <= q0.usageBytes + target.size / 10) "VERDICT: batchDelete returns the quota"
                else "VERDICT: quota NOT returned by batchDelete",
            )

            // Q2 --- is a create/delete cycle penalised when repeated?
            log("")
            log("--- Q2: $CYCLES create/read/delete cycles ---")
            val cycleMs = mutableListOf<Long>()
            var failures = 0
            repeat(CYCLES) { i ->
                val mark = TimeSource.Monotonic.markNow()
                val outcome = runCatching {
                    val id = client.instantCreate(target, parentId = folderId!!, name = "cycle-$i-${target.name}")
                    val detail = client.getFile(id)
                    check(detail.octetStream.url.isNotEmpty()) { "no download link on cycle $i" }
                    client.batchDelete(listOf(id))
                }
                val ms = mark.elapsedNow().inWholeMilliseconds
                outcome.onSuccess {
                    cycleMs += ms
                    log("cycle $i: $ms ms")
                }.onFailure {
                    failures++
                    log("cycle $i: FAILED after $ms ms: ${describe(it)}")
                }
                delay(CYCLE_GAP)
            }
            if (cycleMs.isNotEmpty()) {
                val head = cycleMs.take(5).average()
                val tail = cycleMs.takeLast(5).average()
                log("first 5 avg ${head.toLong()} ms, last 5 avg ${tail.toLong()} ms, failures $failures")
                log(
                    if (failures == 0 && tail < head * 2) "VERDICT: repeated create/delete draws no visible penalty"
                    else "VERDICT: repetition IS penalised (failures=$failures, ${head.toLong()} -> ${tail.toLong()} ms)",
                )
            }

            val q4 = client.getQuota().quota
            log("")
            log("final usage ${gib(q4.usageBytes)} of ${gib(q4.limitBytes)}, trash ${gib(q4.usageInTrash.toLongOrNull() ?: 0L)}")
        } finally {
            runCatching { if (created.isNotEmpty()) client.batchDelete(created.toList()) }
            runCatching { folderId?.let { client.deleteFile(it) } }
            client.close()
            File("build").mkdirs()
            File("build/instant-create-probe.txt").writeText(report.toString())
            println("[probe] report written to build/instant-create-probe.txt")
        }
    }

    /** The first sample pack PikPak's index knows, so a delisted one does not fail the run. */
    private suspend fun pickPack(client: PikPakClient): Pair<String, String>? {
        for ((label, magnet) in SAMPLE_PACKS) {
            val resolved = runCatching { client.resolveMagnet(magnet) }.getOrNull()
            if (resolved != null) return label to magnet
            log("not indexed, skipping: $label")
        }
        return null
    }

    /**
     * Quota is not read-your-writes: a create that has returned is not yet in
     * `/drive/v1/about`. Polling until it moves is also the measurement of how
     * long that takes, which a UI showing the number has to live with.
     */
    private suspend fun awaitQuotaChange(client: PikPakClient, from: Long): Pair<QuotaInfo, Long> {
        val mark = TimeSource.Monotonic.markNow()
        var last = client.getQuota().quota
        while (last.usageBytes == from && mark.elapsedNow() < QUOTA_SETTLE_TIMEOUT) {
            delay(1000)
            last = client.getQuota().quota
        }
        return last to mark.elapsedNow().inWholeMilliseconds
    }

    private fun describe(t: Throwable): String = when (t) {
        is PikPakException -> "${t::class.simpleName} code=${t.errorCode} http=${t.httpStatus} ${t.message}"
        else -> "${t::class.simpleName}: ${t.message}"
    }

    private fun gib(bytes: Long): String = "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    private companion object {
        const val CYCLES = 20
        val CYCLE_GAP = 500.milliseconds
        val QUOTA_SETTLE_TIMEOUT = 30.seconds
        val VIDEO_EXTENSIONS = listOf(".mkv", ".mp4", ".flac", ".ts")

        /**
         * Real season packs off nyaa, not a Linux ISO: the sizes, the file
         * counts and the nested `SPs/` directories are what a consumer will
         * actually hand to resolveMagnet.
         */
        val SAMPLE_PACKS = listOf(
            "[VCB-Studio] Cyberpunk Edgerunners, 23.5 GiB" to
                    "magnet:?xt=urn:btih:7af771b417c55ebc86caa0cb82cdb7faac90c04c",
            "[VCB-Studio] VIRGIN PUNK Clockwork Girl, 9.5 GiB" to
                    "magnet:?xt=urn:btih:04bcd87b4c1d3874cf104d9f5b3cabddb5d7514d",
            "[Judas] City The Animation S01, 6.4 GiB" to
                    "magnet:?xt=urn:btih:365333205bd36ca419d9247c009f885ab435a1b3",
        )
    }
}
