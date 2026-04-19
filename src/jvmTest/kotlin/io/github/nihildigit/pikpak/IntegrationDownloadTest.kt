package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Verifies the full download path: getFile → signed URL → ranged GET → kotlinx-io
 * sink → byte-perfect output. Walks the user's drive looking for any file under
 * 5 MB to keep the test cheap; skips if no suitable candidate exists.
 */
class IntegrationDownloadTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `download a small file end to end`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val candidate = findSmallFile(client, parentId = "", maxSize = 5L * 1024 * 1024, depth = 3)
            Assumptions.assumeTrue(candidate != null, "no file under 5 MB found within depth 3")

            val dest = Path(SystemTemporaryDirectory, "pikpak-kotlin-download-${candidate!!.id}.bin")
            SystemFileSystem.delete(dest, mustExist = false)

            val written = client.download(candidate.id, dest)
            assertEquals(candidate.sizeBytes, written, "downloaded byte count must match expected size")

            val onDisk = SystemFileSystem.metadataOrNull(dest)?.size ?: -1L
            assertEquals(candidate.sizeBytes, onDisk, "on-disk file size must match expected size")
            assertTrue(onDisk > 0, "downloaded file should be non-empty")

            SystemFileSystem.delete(dest, mustExist = false)
        } finally {
            client.close()
        }
    }

    private suspend fun findSmallFile(
        client: PikPakClient,
        parentId: String,
        maxSize: Long,
        depth: Int,
    ): FileStat? {
        if (depth < 0) return null
        val entries = client.listFiles(parentId)
        entries.firstOrNull { it.isFile && it.sizeBytes in 1..maxSize }?.let { return it }
        for (folder in entries.filter { it.isFolder }) {
            findSmallFile(client, folder.id, maxSize, depth - 1)?.let { return it }
        }
        return null
    }
}
