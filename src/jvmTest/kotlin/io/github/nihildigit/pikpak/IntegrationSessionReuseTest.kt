package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * End-to-end cover for the credential-caching contract:
 *  - First client triggers a full signin and writes a session file to disk.
 *  - A second client constructed against the same [FileSessionStore] reads
 *    that file on `login()` and reuses the access token verbatim — no fresh
 *    signin, no second captcha challenge.
 *
 * The "no fresh signin" assertion is indirect: if signin had happened, the
 * server would mint a new access token and our `Session.accessToken` strings
 * would differ. Identical token bytes = same session was reused.
 */
class IntegrationSessionReuseTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `second client reuses the on-disk session of the first`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val dir = Path(SystemTemporaryDirectory, "pikpak-kotlin-session-reuse-${System.nanoTime()}")
        val store = FileSessionStore(dir = dir)

        try {
            val first = PikPakClient(account = username!!, password = password!!, sessionStore = store)
            val firstSession = try {
                val s = first.login()
                assertTrue(s.accessToken.isNotEmpty(), "first login should produce a non-empty access token")
                // Session file must land on disk for the next client to reuse it.
                assertTrue(SystemFileSystem.exists(dir), "session dir should be created")
                val files = SystemFileSystem.list(dir)
                assertTrue(
                    files.any { it.name.startsWith("session_") && it.name.endsWith(".json") },
                    "session file should be written, found ${files.map { it.name }}",
                )
                // The session must round-trip cleanly through the store too.
                val reloaded = store.load(username)
                assertNotNull(reloaded, "store should re-load the saved session")
                assertEquals(s.accessToken, reloaded!!.accessToken, "stored access_token must match")
                s
            } finally {
                first.close()
            }

            // Second client: pointed at the same on-disk store. Should not
            // re-sign-in; should hand back the cached session bit-for-bit.
            val second = PikPakClient(account = username, password = password, sessionStore = store)
            try {
                val secondSession = second.login()
                assertEquals(
                    firstSession.accessToken,
                    secondSession.accessToken,
                    "access_token should be reused from disk — different value implies a fresh signin",
                )
                assertEquals(firstSession.refreshToken, secondSession.refreshToken)
                assertEquals(firstSession.expiresAt, secondSession.expiresAt)

                // Sanity: the cached session is actually usable for an authenticated call.
                val quota = second.getQuota()
                assertTrue(quota.quota.limitBytes > 0, "quota call with reused session should succeed")
            } finally {
                second.close()
            }
        } finally {
            // Best-effort cleanup of the tmp session dir.
            runCatching {
                if (SystemFileSystem.exists(dir)) {
                    for (entry in SystemFileSystem.list(dir)) SystemFileSystem.delete(entry, mustExist = false)
                    SystemFileSystem.delete(dir, mustExist = false)
                }
            }
        }
    }
}
