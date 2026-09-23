package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Prints `/drive/v1/about` verbatim.
 *
 * [QuotaInfo] names its fields `usage` and `limit`, but nothing in the SDK
 * verifies that PikPak means by them what the names suggest, and a UI about to
 * show the user "x of y used" cannot be built on the guess. Opt in with
 * PIKPAK_PROBE=1.
 */
class QuotaRawProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `dump the about response`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val raw = client.http.request(
                method = HttpMethod.Get,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/about",
                captchaAction = "GET:/drive/v1/about",
            )
            println("[about] $raw")

            val q = client.getQuota().quota
            println("[parsed] kind=${q.kind} limit=${q.limit} usage=${q.usage} usageInTrash=${q.usageInTrash}")
            println("[parsed] limit=${gib(q.limitBytes)} usage=${gib(q.usageBytes)} remaining=${gib(q.remainingBytes)}")

            // Cross-check against what is actually in the drive: the root's
            // immediate children, so a wrong reading of `usage` shows up as a
            // number that does not resemble the sum of the user's own files.
            println("[root]")
            for (f in client.listFiles(parentId = "")) {
                println("  ${if (f.kind.endsWith("folder")) "DIR " else "FILE"} ${gib(f.size.toLongOrNull() ?: 0L)}  ${f.name}")
            }
        } finally {
            client.close()
        }
    }

    private fun gib(bytes: Long): String = "%.2f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
