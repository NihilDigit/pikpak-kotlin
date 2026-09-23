package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test

/**
 * Where the account's quota actually went, per top-level folder.
 *
 * `/drive/v1/about` gives one number and PikPak reports every folder's own
 * size as zero, so the only way to attribute usage is to walk. Relevant to a
 * consumer deciding whether to keep the file objects it creates: the answer
 * shows up here as a working folder holding more than the user's own content.
 *
 * Opt in with PIKPAK_PROBE=1. Read-only.
 */
class DriveUsageProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val report = StringBuilder()
    private var entriesWalked = 0

    private fun log(line: String) {
        println("[usage] $line")
        report.appendLine(line)
    }

    @Test
    fun `attribute quota usage to folders`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val q = client.getQuota().quota
            log("quota: usage ${gib(q.usageBytes)} of ${gib(q.limitBytes)}, trash ${gib(q.usageInTrash.toLongOrNull() ?: 0L)}")
            log("")

            val root = client.listFiles(parentId = "")
            var attributed = 0L
            val totals = mutableListOf<Triple<String, Long, Int>>()

            for (entry in root) {
                if (!entry.isFolder()) {
                    val size = entry.size.toLongOrNull() ?: 0L
                    attributed += size
                    totals += Triple(entry.name, size, 1)
                    continue
                }
                val acc = Accumulator()
                walk(client, entry.id, depth = 0, acc)
                attributed += acc.bytes
                totals += Triple(entry.name + "/", acc.bytes, acc.files)
            }

            log("--- top level, largest first ---")
            for ((name, bytes, files) in totals.sortedByDescending { it.second }) {
                log("%12s  %6d files  %s".format(gib(bytes), files, name))
            }
            log("")
            log("attributed ${gib(attributed)} of ${gib(q.usageBytes)} reported used")
            log("unattributed ${gib(q.usageBytes - attributed)} (trash, shares, or below the walk cap)")
            log("entries walked: $entriesWalked (cap $ENTRY_CAP)")

            // The working folder in detail: this is what a cleanup would remove.
            val working = root.firstOrNull { it.isFolder() && it.name == "Animeko-Playing" }
            if (working != null) {
                log("")
                log("--- Animeko-Playing, per bucket ---")
                val buckets = client.listFiles(parentId = working.id)
                val rows = buckets.map { b ->
                    if (b.isFolder()) {
                        val acc = Accumulator()
                        walk(client, b.id, depth = 0, acc)
                        Triple(b.name + "/", acc.bytes, acc.files)
                    } else {
                        Triple(b.name, b.size.toLongOrNull() ?: 0L, 1)
                    }
                }
                for ((name, bytes, files) in rows.sortedByDescending { it.second }) {
                    log("%12s  %6d files  %s".format(gib(bytes), files, name))
                }
                log("total ${gib(rows.sumOf { it.second })} in ${rows.size} buckets")
            }
        } finally {
            client.close()
            File("build").mkdirs()
            File("build/drive-usage-probe.txt").writeText(report.toString())
            println("[usage] report written to build/drive-usage-probe.txt")
        }
    }

    private class Accumulator {
        var bytes = 0L
        var files = 0
    }

    private suspend fun walk(client: PikPakClient, folderId: String, depth: Int, acc: Accumulator) {
        if (depth > MAX_DEPTH || entriesWalked > ENTRY_CAP) return
        val children = runCatching { client.listFiles(parentId = folderId) }.getOrElse { return }
        entriesWalked += children.size
        for (child in children) {
            if (child.isFolder()) {
                walk(client, child.id, depth + 1, acc)
            } else {
                acc.bytes += child.size.toLongOrNull() ?: 0L
                acc.files++
            }
        }
    }

    private fun FileStat.isFolder(): Boolean = kind.endsWith("folder", ignoreCase = true)

    private fun gib(bytes: Long): String = "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    private companion object {
        const val MAX_DEPTH = 6
        const val ENTRY_CAP = 40000
    }
}
