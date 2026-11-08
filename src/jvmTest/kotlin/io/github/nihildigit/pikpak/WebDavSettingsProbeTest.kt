package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Which fields `GET /webdav/v1/applications` actually returns, so [WebDavSettings] is decoded from
 * what the server sends rather than assumed: whether `is_premium` is present on a premium account,
 * and what keys each application carries. Read-only; prints key names and booleans only, never the
 * generated usernames or passwords. Opt in with PIKPAK_PROBE=1.
 */
class WebDavSettingsProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `dump the webdav settings shape`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no credentials in .env")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val raw = client.http.request(
                method = HttpMethod.Get,
                url = buildUrl(PikPakConstants.DAV_BASE, "/webdav/v1/applications"),
                captchaAction = "GET:/webdav/v1/applications",
            ).jsonObject
            println("[webdav] top-level keys: ${raw.keys.sorted()}")
            for (key in listOf("webdav_enable", "is_premium")) println("[webdav] $key = ${raw[key]}")
            val apps = (raw["applications"] as? JsonArray).orEmpty()
            println("[webdav] applications: ${apps.size}")
            apps.firstOrNull()?.let { println("[webdav] application keys: ${(it as JsonObject).keys.sorted()}") }
            val decoded = client.getWebDav()
            println("[webdav] decoded enabled=${decoded.enabled} isPremium=${decoded.isPremium} clients=${decoded.clients.size}")
        } finally {
            client.close()
        }
    }
}
