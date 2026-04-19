package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import org.junit.jupiter.api.Assumptions

/**
 * Verifies the "update" leg of CRUD: relocate a folder between parents.
 *
 * Layout: create `src/` and `dst/` under root, put a `payload/` folder
 * inside `src/`, `batchMove` it into `dst/`, assert it appears under `dst/`
 * and is gone from `src/`. Both top-level test folders are purged in the
 * `finally` block — surviving the move test means their trees are known.
 */
class IntegrationMoveTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `batchMove relocates a folder between parents`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        val created = mutableListOf<String>()
        try {
            client.login()
            val ts = Clock.System.now().toEpochMilliseconds()
            val srcName = "pikpak-kotlin-move-src-$ts"
            val dstName = "pikpak-kotlin-move-dst-$ts"

            val srcId = client.createFolder(parentId = "", name = srcName).also(created::add)
            val dstId = client.createFolder(parentId = "", name = dstName).also(created::add)
            val payloadId = client.createFolder(parentId = srcId, name = "payload").also(created::add)

            assertNotNull(
                client.listFiles(srcId).firstOrNull { it.id == payloadId },
                "payload should start in src/",
            )

            client.batchMove(ids = listOf(payloadId), toParentId = dstId)

            assertEquals(
                0,
                client.listFiles(srcId).count { it.id == payloadId },
                "after move, src/ should no longer contain payload",
            )
            assertNotNull(
                client.listFiles(dstId).firstOrNull { it.id == payloadId },
                "after move, dst/ should contain payload",
            )
        } finally {
            runCatching { client.batchDelete(created) }
            client.close()
        }
        Unit
    }
}
