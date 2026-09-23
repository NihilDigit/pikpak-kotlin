package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Whether a file created from a gcid consumes storage quota.
 *
 * The single-file run could not tell a real delta from a stale reading:
 * `/drive/v1/about` lags a write by more than the file was worth. So this one
 * creates [FILE_COUNT] files of *distinct* content at once and watches the
 * number for [WATCH] before deleting them, which puts the expected delta an
 * order of magnitude above the noise.
 *
 * The answer decides whether a consumer may keep its file objects. If quota is
 * consumed, keeping them fills the user's drive with content they never
 * uploaded, and a full drive fails the next create rather than slowing it.
 *
 * Opt in with PIKPAK_PROBE=1. Creates and then deletes real files.
 */
class InstantCreateQuotaProbeTest {

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
        println("[quota] $line")
        report.appendLine(line)
    }

    @Test
    fun `does an instant copy consume quota`() = runBlocking {
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
            log("=== does an instant copy consume quota, ${Clock.System.now()} ===")

            // Settle first: a reading taken right after any earlier activity is
            // the thing that made the single-file run unreadable.
            log("settling for ${SETTLE.inWholeSeconds}s before the baseline")
            delay(SETTLE)
            val base = client.getQuota().quota
            log("baseline: usage ${gib(base.usageBytes)}, free ${gib(base.remainingBytes)}")

            val resource = client.resolveMagnet(PACK_MAGNET)
            Assumptions.assumeTrue(resource != null, "PikPak does not index the sample pack")

            val targets = resource!!.files
                .filter { it.gcid != null && it.name.endsWith(".mkv", ignoreCase = true) }
                .distinctBy { it.gcid }
                .sortedByDescending { it.size }
                .take(FILE_COUNT)
            Assumptions.assumeTrue(targets.size >= 2, "not enough indexed episodes to measure")

            val expected = targets.sumOf { it.size }
            log("creating ${targets.size} files of distinct content, ${gib(expected)} total")
            check(expected < base.remainingBytes) { "not enough free quota to run safely" }

            folderId = client.createFolder(parentId = "", name = "quota-probe-${Clock.System.now().toEpochMilliseconds()}")
            val createMark = TimeSource.Monotonic.markNow()
            for (t in targets) {
                created += client.instantCreate(t, parentId = folderId)
            }
            log("created in ${createMark.elapsedNow().inWholeMilliseconds} ms")

            log("")
            log("--- usage while the copies exist ---")
            val watchMark = TimeSource.Monotonic.markNow()
            var peak = base.usageBytes
            while (watchMark.elapsedNow() < WATCH) {
                val now = client.getQuota().quota
                if (now.usageBytes > peak) peak = now.usageBytes
                log(
                    "t+%4ds  usage %s  delta %s".format(
                        watchMark.elapsedNow().inWholeSeconds,
                        gib(now.usageBytes),
                        gib(now.usageBytes - base.usageBytes),
                    ),
                )
                delay(POLL)
            }

            val observed = peak - base.usageBytes
            log("")
            log("peak delta ${gib(observed)} against ${gib(expected)} created")
            log(
                when {
                    observed >= expected / 2 -> "VERDICT: an instant copy CONSUMES quota"
                    observed <= expected / 10 -> "VERDICT: an instant copy does NOT consume quota"
                    else -> "VERDICT: inconclusive, delta is neither the file size nor zero"
                },
            )

            log("")
            log("--- deleting ---")
            client.batchDelete(created.toList())
            created.clear()
            val deleteMark = TimeSource.Monotonic.markNow()
            while (deleteMark.elapsedNow() < WATCH) {
                val now = client.getQuota().quota
                log(
                    "t+%4ds  usage %s  delta from baseline %s".format(
                        deleteMark.elapsedNow().inWholeSeconds,
                        gib(now.usageBytes),
                        gib(now.usageBytes - base.usageBytes),
                    ),
                )
                delay(POLL)
            }
            val end = client.getQuota().quota
            log("")
            log("trash after delete: ${gib(end.usageInTrash.toLongOrNull() ?: 0L)} (was ${gib(base.usageInTrash.toLongOrNull() ?: 0L)})")
        } finally {
            runCatching { if (created.isNotEmpty()) client.batchDelete(created.toList()) }
            runCatching { folderId?.let { client.deleteFile(it) } }
            client.close()
            File("build").mkdirs()
            File("build/instant-create-quota-probe.txt").writeText(report.toString())
            println("[quota] report written to build/instant-create-quota-probe.txt")
        }
    }

    private fun gib(bytes: Long): String = "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    private companion object {
        const val FILE_COUNT = 6
        val SETTLE = 30.seconds
        val WATCH = 150.seconds
        val POLL = 15.seconds

        /** [VCB-Studio] Cyberpunk Edgerunners, 23.5 GiB, 191 files. */
        const val PACK_MAGNET = "magnet:?xt=urn:btih:7af771b417c55ebc86caa0cb82cdb7faac90c04c"
    }
}
