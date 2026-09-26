package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import org.junit.jupiter.api.Assumptions

/**
 * Full trash lifecycle: create → batchTrash → listTrash sees it →
 * batchUntrash → root sees it again → batchDelete purges. Every step is
 * asserted; the final `batchDelete` is both the assertion and the cleanup,
 * so nothing is left behind on success. A catch-all `runCatching` pass
 * handles leaks on failure.
 */
class IntegrationTrashLifecycleTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `trash untrash purge roundtrip`() = runBlocking {
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
            val name = "pikpak-kotlin-trash-$ts"

            folderId = client.createFolder(parentId = "", name = name)
            assertTrue(folderId.isNotBlank())

            client.batchTrash(listOf(folderId))
            assertTrue(
                eventually("trashed folder gone from root") { client.listFiles().none { it.id == folderId } },
                "trashed folder should not appear in root listing",
            )
            assertTrue(
                eventually("trashed folder in trash") { client.listTrash().any { it.id == folderId } },
                "trashed folder should appear in listTrash",
            )

            client.batchUntrash(listOf(folderId))
            assertTrue(
                eventually("restored folder back in root") { client.listFiles().any { it.id == folderId } },
                "untrashed folder should reappear at its original parent",
            )
            assertTrue(
                eventually("restored folder gone from trash") { client.listTrash().none { it.id == folderId } },
                "restored folder should no longer appear in listTrash",
            )

            client.batchDelete(listOf(folderId))
            folderId = null // purged; no cleanup needed
        } finally {
            folderId?.let {
                runCatching { client.batchDelete(listOf(it)) }
            }
            client.close()
        }
    }
}
