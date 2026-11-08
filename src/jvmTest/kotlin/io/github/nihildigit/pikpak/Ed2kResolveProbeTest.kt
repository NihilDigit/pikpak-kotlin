package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

class Ed2kResolveProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    @Test
    fun `probe resource list with ed2k links`() = runBlocking {
        Assumptions.assumeTrue(System.getenv("PIKPAK_PROBE") == "1", "PIKPAK_PROBE!=1")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val samples = listOf(
                // eMule official v0.50a installer well-known ed2k link
                "ed2k://|file|eMule0.50a-Installer.exe|3377264|7683295B023BD0D52F8FC24FA381AA53|h=65543252C2F0F1C2A653D3270296A476|/",
                // Standard well-known zero-byte / small file MD4
                "ed2k://|file|ubuntu-22.04-desktop-amd64.iso|3654957056|A8A31B2E0C3D5A6B7C8D9E0F1A2B3C4D|/",
            )
            for (ed2k in samples) {
                val raw = runCatching {
                    client.http.request(
                        method = HttpMethod.Post,
                        url = "${PikPakConstants.DRIVE_BASE}/drive/v1/resource/list",
                        captchaAction = "POST:/drive/v1/resource/list",
                    ) {
                        jsonBody(client.json, buildJsonObject {
                            put("urls", ed2k)
                            put("page_size", 500)
                            put("thumbnail_type", "FROM_HASH")
                        })
                    }
                }
                println("[ED2K_PROBE] input=$ed2k -> result=$raw")
                val parsed = runCatching { client.resolveMagnet(ed2k) }
                println("[ED2K_PROBE] resolveMagnet=$parsed")
            }
        } finally {
            client.close()
        }
    }
}
