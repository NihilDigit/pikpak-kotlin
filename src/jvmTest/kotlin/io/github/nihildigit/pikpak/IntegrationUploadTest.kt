package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Full upload roundtrip against the live PikPak API:
 *  1. Generate a small (4 KB) random local file with a unique timestamped name.
 *  2. Upload it to a freshly-created test folder under root.
 *  3. Verify it appears in the folder listing with the correct size.
 *  4. Download it back and assert byte-for-byte equality.
 *  5. Clean up by deleting the test folder (which trashes the file too).
 *
 * Skipped automatically when .env credentials are missing.
 */
class IntegrationUploadTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `upload a small file then download and compare`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()

            val ts = Clock.System.now().toEpochMilliseconds()
            val folderName = "pikpak-kotlin-upload-test-$ts"
            val folderId = client.createFolder(parentId = "", name = folderName)

            val payload = Random(ts).nextBytes(4 * 1024)
            val srcName = "upload-$ts.bin"
            val src = Path(SystemTemporaryDirectory, srcName)
            val dst = Path(SystemTemporaryDirectory, "$srcName.dl")

            try {
                SystemFileSystem.delete(src, mustExist = false)
                SystemFileSystem.sink(src).buffered().use { it.write(payload, 0, payload.size) }

                val result = client.upload(parentId = folderId, source = src)
                assertTrue(result.fileId.isNotBlank(), "upload should return a file id")
                println("[upload] fileId=${result.fileId} instant=${result.instantUpload} bytes=${result.bytesUploaded}")

                val listing = client.listFiles(folderId)
                val uploaded = listing.firstOrNull { it.name == srcName && it.id == result.fileId }
                assertNotNull(uploaded, "uploaded file should appear in listing")
                assertEquals(payload.size.toLong(), uploaded!!.sizeBytes, "uploaded file should have correct size")

                SystemFileSystem.delete(dst, mustExist = false)
                // Newly uploaded files take a moment before PikPak generates a
                // signed download link — poll briefly instead of failing fast.
                val downloadable = pollUntil(15) {
                    runCatching { client.getFile(result.fileId).downloadUrl != null }.getOrDefault(false)
                }
                assertTrue(downloadable, "download link should appear within poll window")
                val written = client.download(result.fileId, dst)
                assertEquals(payload.size.toLong(), written, "download should write expected byte count")
                val downloaded = SystemFileSystem.source(dst).buffered().use { it.readByteArray(payload.size) }
                assertTrue(downloaded.contentEquals(payload), "downloaded bytes should match the original payload")
            } finally {
                runCatching { client.deleteFile(folderId) }
                SystemFileSystem.delete(src, mustExist = false)
                SystemFileSystem.delete(dst, mustExist = false)
            }
        } finally {
            client.close()
        }
    }

    private suspend fun pollUntil(maxAttempts: Int, predicate: suspend () -> Boolean): Boolean {
        repeat(maxAttempts) { i ->
            if (predicate()) return true
            delay(1000L + (i * 500L))
        }
        return predicate()
    }
}
