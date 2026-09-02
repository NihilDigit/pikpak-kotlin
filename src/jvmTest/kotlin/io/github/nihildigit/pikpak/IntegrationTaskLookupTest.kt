package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Covers the two server-side lookups added for consumers that poll: fetching
 * one task by id, and resolving a folder by name without paging its parent.
 *
 * Both are cheap and read-only apart from one scratch folder.
 */
class IntegrationTaskLookupTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `getTask returns one task by id`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(username!!, password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val listing = client.listOfflineTasks(
                phaseFilter = "${TaskPhase.RUNNING},${TaskPhase.ERROR},${TaskPhase.COMPLETE}",
                limit = 5,
            )
            Assumptions.assumeTrue(listing.tasks.isNotEmpty(), "account has no offline tasks to look up")
            val expected = listing.tasks.first()

            val fetched = client.getTask(expected.id)
            assertEquals(expected.id, fetched.id)
            assertEquals(expected.name, fetched.name)
            assertTrue(fetched.phase.isNotEmpty(), "phase should be populated")
            println("[task] ${fetched.id} phase=${fetched.phase} message=${fetched.message} file=${fetched.fileId}")
        } finally {
            client.close()
        }
    }

    @Test
    fun `getFolderId resolves through the server-side name filter`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(username!!, password!!, sessionStore = InMemorySessionStore())
        var folderId: String? = null
        try {
            client.login()
            val name = "pikpak-kotlin-filter-${Clock.System.now().toEpochMilliseconds()}"
            folderId = client.createFolder("", name)

            assertEquals(folderId, client.getFolderId("", name))
            assertEquals(folderId, client.getPathFolderId(name))
            // Second resolve must come out of the memo rather than the network.
            assertEquals(folderId, client.getPathFolderId(name))

            val folders = client.listFiles("", extraFilters = mapOf(FileFilter.kind(FileKind.FOLDER)))
            assertTrue(folders.all { it.isFolder }, "kind filter must exclude files server-side")
            assertTrue(folders.any { it.name == name })
        } finally {
            runCatching { folderId?.let { client.deleteFile(it) } }
            client.close()
        }
    }
}
