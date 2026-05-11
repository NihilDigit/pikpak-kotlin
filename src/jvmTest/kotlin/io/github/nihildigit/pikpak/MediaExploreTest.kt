package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * One-off exploration: dump the full raw JSON for a known video file (so we can see
 * the `medias[]` field that Models.kt currently ignores), then probe each visible
 * media link to see what container format the URL actually serves.
 *
 * Purpose: decide whether time-based clipping should be built on top of HLS
 * (most likely), DASH, or fall back to mp4 + byte-range. Not a real test —
 * delete or convert to an assertion once we've answered the question.
 *
 * Run: ./gradlew jvmTest --tests '*MediaExplore*' -i
 */
class MediaExploreTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    private val pretty = Json { prettyPrint = true; encodeDefaults = false }

    @Test
    fun `dump medias for known video file`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        try {
            client.login()
            val rootHint = "VOsGrmDyGsfAwogjE8JyddaYo2"

            // The hint may point at a folder (BT-style root). Walk into it to find an
            // actual video file we can probe. Depth-first, first video wins. Hint
            // is maintainer-specific drive state; gracefully skip if the file isn't
            // in this account's drive (matches IntegrationDownloadTest's pattern).
            val fileId = findVideoFile(client, rootHint, depth = 3)
            Assumptions.assumeTrue(
                fileId != null,
                "no video file under $rootHint within depth 3 — test data not present in this account",
            )
            println("Probing video file id=$fileId (resolved from $rootHint)")

            val raw = client.http.request(
                method = HttpMethod.Get,
                url = "https://api-drive.mypikpak.com/drive/v1/files/$fileId",
                captchaAction = "GET:/drive/v1/files",
            )

            println("=" .repeat(72))
            println("FULL DETAIL JSON for $fileId")
            println("=" .repeat(72))
            println(pretty.encodeToString(JsonObject.serializer(), raw.jsonObject))

            val obj = raw.jsonObject
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            val size = obj["size"]?.jsonPrimitive?.contentOrNull
            println()
            println("name=$name  mime_type=$mimeType  size=$size")

            val medias: JsonArray = obj["medias"] as? JsonArray ?: run {
                println("!! no medias[] field on response")
                return@runBlocking
            }

            println()
            println("medias[] has ${medias.size} entries:")
            medias.forEachIndexed { i, mEl ->
                val m = mEl.jsonObject
                val resolution = m["resolution_name"]?.jsonPrimitive?.contentOrNull
                val isOrigin = m["is_origin"]?.jsonPrimitive?.contentOrNull
                val isDefault = m["is_default"]?.jsonPrimitive?.contentOrNull
                val isVisible = m["is_visible"]?.jsonPrimitive?.contentOrNull
                val category = m["category"]?.jsonPrimitive?.contentOrNull
                val link = m["link"]?.jsonObject
                val url = link?.get("url")?.jsonPrimitive?.contentOrNull
                println()
                println("  [$i] resolution=$resolution  origin=$isOrigin  default=$isDefault  visible=$isVisible  category=$category")
                println("       url=$url")

                if (url.isNullOrBlank()) {
                    println("       (skipped — no URL)")
                    return@forEachIndexed
                }

                // Skip origin probes — they point at the full original container (hundreds
                // of MB) and the underlying engine buffers more than readRemaining wants.
                // We already know the origin format from mime_type; what we need to confirm
                // is the transcode URL response (mpegts file vs m3u8 playlist).
                if (isOrigin == "true") {
                    println("       (origin — skipping probe to avoid streaming a huge body)")
                    return@forEachIndexed
                }

                runCatching {
                    val resp = client.http.sendRaw(HttpMethod.Get, url) {
                        header(HttpHeaders.UserAgent, PikPakConstants.USER_AGENT)
                        // Force a tiny response so we never accidentally start streaming
                        // hundreds of MB. 2KB is plenty to see a m3u8 header or ts packets.
                        header(HttpHeaders.Range, "bytes=0-2047")
                    }
                    val ct = resp.headers[HttpHeaders.ContentType]
                    val cl = resp.headers[HttpHeaders.ContentLength]
                    val ar = resp.headers[HttpHeaders.AcceptRanges]
                    val cr = resp.headers[HttpHeaders.ContentRange]
                    println("       status=${resp.status.value}  content-type=$ct  content-length=$cl")
                    println("       accept-ranges=$ar  content-range=$cr")

                    val firstChunk = resp.bodyAsChannel().readRemaining(800).readByteArray()
                    val asText = firstChunk.decodeToString().takeWhile { it.code < 0x80 || it == '\n' }
                    val looksTextual = firstChunk.take(64).all { it == 0x09.toByte() || it == 0x0A.toByte() || it == 0x0D.toByte() || it >= 0x20 }
                    if (looksTextual) {
                        println("       --- first 800 bytes (text) ---")
                        asText.lines().take(20).forEach { println("       | $it") }
                    } else {
                        val magic = firstChunk.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                        println("       --- first 16 bytes (binary) ---")
                        println("       | $magic")
                        // mp4 box header: 4-byte size then 4-byte type. Print box type if it looks plausible.
                        if (firstChunk.size >= 8) {
                            val boxType = firstChunk.copyOfRange(4, 8).decodeToString()
                            println("       | first mp4 box type guess: '$boxType'")
                        }
                    }
                }.onFailure {
                    println("       !! probe failed: ${it::class.simpleName}: ${it.message}")
                }
            }

            println()
            println("=" .repeat(72))
            println("END")
            println("=" .repeat(72))
        } finally {
            client.close()
        }
    }

    private suspend fun findVideoFile(client: PikPakClient, parentId: String, depth: Int): String? {
        if (depth < 0) return null
        // Try treating `parentId` itself as a file first — if the hint already points
        // at a video file, no listing needed.
        if (depth == 3) {
            runCatching { client.getFile(parentId) }.getOrNull()?.let { detail ->
                if (detail.mimeType.startsWith("video/")) return detail.id
            }
        }
        val children = runCatching { client.listFiles(parentId) }.getOrNull() ?: return null
        children.firstOrNull { it.isFile && it.mimeType.startsWith("video/") }?.let { return it.id }
        for (sub in children.filter { it.isFolder }) {
            findVideoFile(client, sub.id, depth - 1)?.let { return it }
        }
        return null
    }
}
