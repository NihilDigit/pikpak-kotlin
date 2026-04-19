package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * MVP smoke test against the real PikPak API.
 *
 * Skipped automatically if PIKPAK_USERNAME / PIKPAK_PASSWORD are not provided
 * via either the process environment or a top-level `.env` file. CI without
 * credentials therefore stays green.
 *
 * Operations are read-only (login, quota, list root) — won't pollute the account.
 */
class IntegrationLoginTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `login then quota then list root`() = runBlocking {
        Assumptions.assumeTrue(
            username != null && password != null,
            "PIKPAK_USERNAME / PIKPAK_PASSWORD not set — skipping live integration test",
        )
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            val session = client.login()
            assertTrue(session.accessToken.isNotEmpty(), "access_token should be present")
            assertTrue(session.refreshToken.isNotEmpty(), "refresh_token should be present")
            assertTrue(session.expiresAt > 0, "expires_at should be positive")

            val quota = client.getQuota()
            assertNotNull(quota.quota, "quota envelope should decode")
            println("[quota] limit=${quota.quota.limitBytes} usage=${quota.quota.usageBytes} remaining=${quota.quota.remainingBytes}")

            val files = client.listFiles()
            println("[ls /] found ${files.size} entries; first 5: ${files.take(5).joinToString { it.name }}")
        } finally {
            client.close()
        }
    }
}
