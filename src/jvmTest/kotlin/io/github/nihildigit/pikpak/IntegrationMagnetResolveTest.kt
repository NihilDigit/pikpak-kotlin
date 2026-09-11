package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import org.junit.jupiter.api.Assumptions

/**
 * The fast path end to end: resolve a magnet against PikPak's content index,
 * then materialise one of its files from the gcid alone. Neither step creates
 * an offline-download task, so a run costs a few hundred milliseconds instead
 * of the minutes [IntegrationOfflineDownloadTest] can spend waiting.
 */
class IntegrationMagnetResolveTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `resolveMagnet yields gcids that instantCreate turns into a file`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        var parentId: String? = null
        var fileId: String? = null
        try {
            client.login()
            val resource = client.resolveMagnet(TestFixtures.ARCH_ISO_MAGNET)
            // A miss is a legitimate answer from the index, not a broken SDK:
            // skip rather than fail if PikPak has dropped this release.
            Assumptions.assumeTrue(resource != null, "PikPak does not index the fixture magnet")

            val files = resource!!.files
            assertTrue(files.isNotEmpty(), "a resolved torrent must list at least one file")
            val target = files.firstOrNull { it.gcid != null }
            Assumptions.assumeTrue(target != null, "no file of the fixture magnet is in the index")

            val gcid = target!!.gcid!!
            assertTrue(
                gcid.length == 40 && gcid.all { it in '0'..'9' || it in 'A'..'F' },
                "a gcid is 40 upper-case hex digits; got \"$gcid\"",
            )
            assertTrue(target.size > 0, "an indexed file carries its length; got ${target.size}")

            val ts = Clock.System.now().toEpochMilliseconds()
            parentId = client.createFolder(parentId = "", name = "pikpak-kotlin-resolve-$ts")
            fileId = client.instantCreate(target, parentId = parentId)
            assertTrue(fileId!!.isNotEmpty(), "instantCreate must return a file id")

            val created = client.getFile(fileId!!)
            assertEquals(target.name, created.name)
            assertEquals(target.size, created.sizeBytes, "the instant copy must have the source's length")
        } finally {
            runCatching { fileId?.let { client.batchDelete(listOf(it)) } }
            runCatching { parentId?.let { client.deleteFile(it) } }
            client.close()
        }
    }
}
