package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Whether `/user/v1/settings` can hold Piko's own data. A userscript stores
 * its config inside the official `bookmark` setting; this probe checks
 * whether a separate key works instead. The read test prints the shape of
 * existing settings with string values replaced by their length, so the
 * user's bookmarks never reach the log. The write test only touches keys
 * named `piko_probe_*` and never `bookmark`. Opt in with PIKPAK_PROBE=1.
 */
class SettingsProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val base = "${PikPakConstants.DRIVE_BASE}/user/v1/settings"

    private fun client(): PikPakClient {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        return PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
    }

    @Test
    fun `read settings shape`() = runBlocking {
        val client = client()
        try {
            client.login()
            for (query in listOf("?items=bookmark", "", "?items=piko_probe", "?items=bookmark,piko_probe")) {
                val r = call(client, HttpMethod.Get, "$base$query")
                println("[GET$query] ${r.fold({ shape(it) }, { it })}")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `read settings item syntax`() = runBlocking {
        val client = client()
        try {
            client.login()
            for (query in listOf(
                "?items=enablePrivacyMode",
                "?items=bookmark&items=enablePrivacyMode",
                "?items=bookmark%2CenablePrivacyMode",
                "?items=",
                "?items=Bookmark",
            )) {
                val r = call(client, HttpMethod.Get, "$base$query")
                println("[GET$query] ${r.fold({ shape(it) }, { it })}")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `write custom key`() = runBlocking {
        val client = client()
        val key = "piko_probe_${System.currentTimeMillis()}"
        val value = JsonPrimitive("""{"v":1}""")
        try {
            client.login()
            val shapes = listOf(
                "settings-object" to JsonObject(mapOf("settings" to JsonObject(mapOf(key to value)))),
                "items-object" to JsonObject(mapOf("items" to JsonObject(mapOf(key to value)))),
                "flat" to JsonObject(mapOf(key to value)),
                "settings-array" to JsonObject(mapOf("settings" to JsonArray(listOf(JsonObject(mapOf("key" to JsonPrimitive(key), "value" to value)))))),
                // The body PikPak Enhancement Master 5.0.0 posts for `bookmark`.
                "item-value" to JsonObject(mapOf("item" to JsonPrimitive(key), "value" to value)),
                "item-value bookmark_piko" to JsonObject(mapOf("item" to JsonPrimitive("bookmark_piko"), "value" to value)),
                "item-value empty item" to JsonObject(mapOf("item" to JsonPrimitive(""), "value" to value)),
            )
            for ((label, body) in shapes) {
                for (method in listOf(HttpMethod.Post, HttpMethod.Patch)) {
                    val r = call(client, method, base, body)
                    println("[${method.value} $label] ${r.fold({ it.toString() }, { it })}")
                }
            }
        } finally {
            client.close()
        }
    }

    private suspend fun call(
        client: PikPakClient,
        method: HttpMethod,
        url: String,
        body: JsonElement? = null,
    ): Result<JsonElement> = runCatching {
        client.http.request(method, url, captchaAction = "${method.value}:/user/v1/settings") {
            if (body != null) jsonBody(client.json, body)
        }
    }.recoverCatching { e ->
        if (e !is PikPakException) throw e
        throw IllegalStateException("http=${e.httpStatus} code=${e.errorCode} error=${e.errorMessage} desc=${e.errorDescription} body=${e.rawBody}")
    }

    /**
     * The element with every string replaced by `"<str N>"`, and strings that
     * themselves parse as JSON expanded the same way, so the encoding is
     * visible without the contents.
     */
    private fun shape(e: JsonElement, depth: Int = 0): String = when (e) {
        is JsonNull -> "null"
        is JsonObject -> e.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${shape(v, depth)}" }
        is JsonArray -> if (e.isEmpty()) "[]" else "[${e.size}× ${shape(e.first(), depth)}]"
        is JsonPrimitive -> when {
            !e.isString -> e.content
            depth < 3 && e.content.trimStart().let { it.startsWith("{") || it.startsWith("[") } ->
                runCatching { "json-string(${e.content.length}ch)${shape(Json.parseToJsonElement(e.content), depth + 1)}" }
                    .getOrElse { "<str ${e.content.length}>" }
            else -> "<str ${e.content.length}>"
        }
    }
}
