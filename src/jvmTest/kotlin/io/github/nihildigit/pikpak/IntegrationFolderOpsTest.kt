package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * End-to-end exercise of folder lifecycle on the real PikPak API.
 * Creates a uniquely-named folder under root, renames it, deletes it,
 * and asserts the root listing reflects each step. The folder is named
 * with a timestamp so concurrent runs don't collide and stragglers are
 * easy to identify in the trash.
 *
 * Skipped when .env credentials are not provided.
 */
class IntegrationFolderOpsTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `create rename delete folder roundtrip`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val ts = Clock.System.now().toEpochMilliseconds()
            val initialName = "pikpak-kotlin-test-$ts"
            val renamedName = "$initialName-renamed"

            val folderId = client.createFolder(parentId = "", name = initialName)
            assertTrue(folderId.isNotBlank(), "createFolder should return a non-blank id")

            val rootAfterCreate = client.listFiles()
            assertNotNull(
                rootAfterCreate.firstOrNull { it.name == initialName && it.id == folderId },
                "newly-created folder should appear in root listing",
            )

            client.rename(folderId, renamedName)
            val rootAfterRename = client.listFiles()
            assertNotNull(
                rootAfterRename.firstOrNull { it.name == renamedName && it.id == folderId },
                "renamed folder should appear with new name",
            )
            assertEquals(
                0, rootAfterRename.count { it.name == initialName },
                "original name should no longer be present",
            )

            client.deleteFile(folderId)
            val rootAfterDelete = client.listFiles()
            assertEquals(
                0, rootAfterDelete.count { it.id == folderId },
                "deleted folder should no longer appear (it's in trash)",
            )
        } finally {
            client.close()
        }
    }
}
