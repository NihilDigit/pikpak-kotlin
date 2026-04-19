package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Forces the access_token-expired branch of [AuthApi.loginLocked] against
 * the real PikPak API:
 *  1. Do a normal login to obtain a fresh, server-issued refresh_token.
 *  2. Pre-populate a clean store with the same refresh_token but a stale
 *     `expiresAt = 0` — that's how the client recognises a dead access token.
 *  3. Construct a second client over that store and call login(). The auth
 *     state machine must take the refresh_token branch, mint a new
 *     access_token, and the new token must be a different value from the
 *     original (a same-string token would mean we silently fell back to a
 *     full signin — the bug we're guarding against).
 *  4. Use the rotated session for an authenticated call to prove it works.
 */
class IntegrationRefreshTokenTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `expired access token triggers refresh_token path`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val freshSession = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        ).use {
            it.login()
        }
        assertTrue(freshSession.refreshToken.isNotEmpty(), "real signin should yield a refresh_token")

        // Stale-clone: same refresh_token, expiresAt deep in the past so the
        // client must take the refresh branch, not the cache-reuse branch.
        val staleSession = freshSession.copy(expiresAt = 0L)
        val store = InMemorySessionStore().apply { save(username, staleSession) }

        val client = PikPakClient(account = username, password = password, sessionStore = store)
        try {
            val refreshed = client.login()
            assertNotEquals(
                freshSession.accessToken, refreshed.accessToken,
                "access_token must rotate — same value implies the refresh path was skipped",
            )
            assertTrue(refreshed.refreshToken.isNotEmpty(), "refreshed session should still have a refresh_token")
            assertTrue(refreshed.expiresAt > 0L, "expiresAt should be repopulated from the new server response")
            assertEquals(freshSession.sub, refreshed.sub, "sub (user id) is stable across refresh")

            // Sanity: the rotated token is real, not just a string.
            val quota = client.getQuota()
            assertTrue(quota.quota.limitBytes > 0, "quota call with refreshed token should succeed")
        } finally {
            client.close()
        }
    }

    private inline fun <T> PikPakClient.use(block: (PikPakClient) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}
