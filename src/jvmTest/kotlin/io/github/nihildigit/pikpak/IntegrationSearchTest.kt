package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import org.junit.jupiter.api.Assumptions

/**
 * Exercises account-wide search against the live API. Creates a folder whose
 * name embeds a fresh timestamp (so the match is unique), searches for a
 * substring of that name, and verifies the folder is in the result set.
 *
 * Skipped when .env credentials are not provided. Cleanup runs unconditionally.
 */
class IntegrationSearchTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `searchFiles finds a uniquely-named folder by substring`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        var folderId: String? = null
        try {
            client.login()
            val ts = Clock.System.now().toEpochMilliseconds()
            val uniqueTag = "search$ts"
            val folderName = "pikpak-kotlin-$uniqueTag"

            folderId = client.createFolder(parentId = "", name = folderName)
            assertTrue(folderId.isNotBlank())

            val hits = client.searchFiles(keyword = uniqueTag)
            val match = hits.firstOrNull { it.id == folderId }
            assertNotNull(match, "search by unique substring should return the folder we just created")
            assertTrue(match.name == folderName, "matched entry should be our folder, got ${match.name}")
        } finally {
            folderId?.let { runCatching { client.deleteFile(it) } }
            client.close()
        }
    }
}
