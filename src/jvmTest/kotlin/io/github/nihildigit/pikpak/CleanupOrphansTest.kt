package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions

/**
 * One-shot housekeeping: trashes any root-level test folders left behind by a
 * prior interrupted integration test run (names starting with the test prefix).
 * Always passes; output lists what got cleaned. Safe to re-run.
 */
class CleanupOrphansTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `trash leftover pikpak-kotlin test folders in root`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val orphans = client.listFiles().filter {
                it.isFolder && it.name.startsWith("pikpak-kotlin-")
            }
            for (folder in orphans) {
                runCatching { client.deleteFile(folder.id) }
                    .onSuccess { println("[cleanup] trashed ${folder.name} (${folder.id})") }
                    .onFailure { println("[cleanup] could not trash ${folder.name}: ${it.message}") }
            }
            if (orphans.isEmpty()) println("[cleanup] no orphans found")
        } finally {
            client.close()
        }
    }
}
