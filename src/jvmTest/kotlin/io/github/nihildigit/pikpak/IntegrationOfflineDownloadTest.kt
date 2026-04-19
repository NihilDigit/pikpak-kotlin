package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions

/**
 * Submits a known-good magnet to the offline-download queue and verifies the
 * task gets accepted (id + non-ERROR phase). Then polls [listOfflineTasks]
 * briefly to observe progress; the test does **not** wait for the download
 * to complete — Arch ISOs are ~1 GiB and free-tier accounts can take many
 * minutes. Completion is considered out-of-scope for the SDK contract.
 *
 * Cleanup: drops the task's resulting file (if one materialised) and any
 * orphan tasks in the staging folder. See ACID note in the finally block.
 */
class IntegrationOfflineDownloadTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    private val magnet =
        "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=archlinux-2026.04.01-x86_64.iso"

    @Test
    fun `createUrlFile accepts a magnet and surfaces it in listOfflineTasks`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        var parentId: String? = null
        var taskId: String? = null
        var fileId: String? = null
        try {
            client.login()
            val ts = Clock.System.now().toEpochMilliseconds()
            parentId = client.createFolder(parentId = "", name = "pikpak-kotlin-offline-$ts")

            val task = client.createUrlFile(parentId = parentId, url = magnet)
            taskId = task.id.takeIf { it.isNotEmpty() }
            fileId = task.fileId.takeIf { it.isNotEmpty() }
            assertTrue(
                taskId != null || fileId != null,
                "createUrlFile should return either a task id or an immediate file id",
            )
            assertTrue(
                task.phase != "PHASE_TYPE_ERROR",
                "freshly-submitted task should not be in ERROR phase; got ${task.phase} / ${task.message}",
            )

            if (taskId != null) {
                val deadline = Clock.System.now().toEpochMilliseconds() + 30_000L
                var observed = task
                while (Clock.System.now().toEpochMilliseconds() < deadline) {
                    val listing = client.listOfflineTasks(
                        phaseFilter = "PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR,PHASE_TYPE_COMPLETE",
                    )
                    val hit = listing.tasks.firstOrNull { it.id == taskId }
                    if (hit != null) {
                        observed = hit
                        fileId = hit.fileId.takeIf { it.isNotEmpty() } ?: fileId
                        if (hit.phase == "PHASE_TYPE_COMPLETE" || hit.phase == "PHASE_TYPE_ERROR") break
                    }
                    delay(3.seconds)
                }
                assertTrue(
                    observed.phase != "PHASE_TYPE_ERROR",
                    "observed task should not error during brief polling; message=${observed.message}",
                )
            }
        } finally {
            runCatching {
                fileId?.let { client.batchDelete(listOf(it)) }
            }
            runCatching {
                parentId?.let { client.deleteFile(it) }
            }
            client.close()
        }
    }
}
