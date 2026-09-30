package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Whether a gcid can still be instant-created a while after the last object holding it was
 * deleted, which is what every rebuild of a leased handle depends on.
 *
 * The question is open. rclone's issue tracker reports the server's record of a deleted
 * file's hash changing within the hour, and the SDK once dropped leases over it; 1.3.0 brought
 * them back without measuring it. This leases the Arch ISO's gcid, lets the delete land, waits,
 * and instant-creates the gcid again, then reads a range from the new object to check it serves
 * the same content.
 *
 * Opt in with PIKPAK_LEASE_PROBE=1; PIKPAK_LEASE_PROBE_WAIT_MIN sets the wait (70 by default,
 * past the hour the report names). Writes `build/lease-rebuild-probe.txt`.
 */
class LeaseRebuildProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() }
    private val enabled = (env["PIKPAK_LEASE_PROBE"] ?: System.getenv("PIKPAK_LEASE_PROBE")) == "1"
    private val waitMinutes = (env["PIKPAK_LEASE_PROBE_WAIT_MIN"] ?: System.getenv("PIKPAK_LEASE_PROBE_WAIT_MIN"))
        ?.toIntOrNull() ?: 70

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[lease] $line")
        report.appendLine(line)
    }

    @Test
    fun `a leased gcid can be created again after its object is gone`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_LEASE_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var folder: String? = null
        var rebuilt: String? = null
        try {
            log("=== lease rebuild probe, ${Clock.System.now()}, waiting $waitMinutes min ===")
            val resource = client.resolveMagnet(TestFixtures.ARCH_ISO_MAGNET)
            val file = resource?.files?.firstOrNull { it.gcid != null && it.size > 0 }
            Assumptions.assumeTrue(file != null, "PikPak does not index the fixture magnet")
            file!!
            folder = client.getOrCreateDeepFolderId("", "pikpak-kotlin-lease-probe-${Clock.System.now().epochSeconds}")

            val detail = client.leaseDetail(file, parentId = folder)
            log("leased ${file.gcid} (${file.size} bytes) as ${detail.id}")
            val gone = eventually("lease object deleted", timeout = 60.seconds) {
                client.listFiles(parentId = folder).none { it.id == detail.id }
            }
            log("lease object gone from the listing: $gone")
            val head = client.fileHandle(detail).use { it.readBytes(0, 64 * 1024) }

            val waited = TimeSource.Monotonic.markNow()
            while (waited.elapsedNow() < waitMinutes.minutes) {
                delay(1.minutes)
                println("[lease] waited ${waited.elapsedNow().inWholeMinutes} of $waitMinutes min")
            }

            val outcome = runCatching { client.instantCreate(file, parentId = folder, name = "rebuilt-${file.name}") }
            outcome.onFailure { log("rebuild after ${waited.elapsedNow()} FAILED: $it") }
            rebuilt = outcome.getOrNull()
            if (rebuilt != null) {
                val again = client.fileHandle(client.getFile(rebuilt)).use { it.readBytes(0, 64 * 1024) }
                log("rebuild after ${waited.elapsedNow()} succeeded as $rebuilt; same head bytes: ${again.contentEquals(head)}")
            }
            assertNotNull(rebuilt, "a gcid whose objects were all deleted could not be created again")
        } finally {
            runCatching { rebuilt?.let { client.deleteFile(it) } }
            runCatching { folder?.let { client.deleteFile(it) } }
            client.close()
            File("build").mkdirs()
            File("build/lease-rebuild-probe.txt").writeText(report.toString())
        }
    }
}
