package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test

/**
 * Which containers PikPak serves a video's transcodes in, and whether the file
 * detail offers any other form of them (another link type, another usage).
 * Decides whether "download in 720P" can save a ready MP4 or has to remux
 * MPEG-TS.
 *
 * Read-only: walks the drive for an already transcoded video, keeps the raw
 * detail JSON (signatures masked) and reads the first 4 KiB of each variant.
 * Opt in with PIKPAK_PROBE=1. Writes `build/transcode-container-probe.txt`
 * and `build/transcode-container-detail.json`.
 */
class TranscodeContainerProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[container] $line")
        report.appendLine(line)
    }

    @Test
    fun `probe the containers of transcoded variants`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no credentials in .env")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        try {
            client.login()
            val target = findTranscodedVideo(client)
            if (target == null) {
                log("no transcoded video found within the walk limit")
                return@runBlocking
            }
            log("video ${target.id} size=${target.sizeBytes}")

            for (usage in listOf(null, "FETCH", "PLAY", "CACHE")) {
                val query = buildMap {
                    put("thumbnail_size", THUMBNAIL_SIZE)
                    usage?.let { put("usage", it) }
                }
                val raw = runCatching {
                    client.http.request(
                        method = HttpMethod.Get,
                        url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files/${target.id}", query),
                        captchaAction = "GET:/drive/v1/files",
                    )
                }.getOrElse { log("usage=$usage failed: ${it.message}"); continue }
                log("=== usage=${usage ?: "(none)"} ===")
                if (usage == null) {
                    File("build").mkdirs()
                    File("build/transcode-container-detail.json").writeText(mask(raw.toString()))
                }
                describe(raw.jsonObject, http)
            }
        } finally {
            File("build").mkdirs()
            File("build/transcode-container-probe.txt").writeText(report.toString())
            client.close()
        }
    }

    private fun describe(detail: JsonObject, http: HttpClient) {
        log("top-level keys: ${detail.keys.sorted()}")
        (detail["links"] as? JsonObject)?.forEach { (type, link) ->
            log("links[$type] -> ${head(http, (link as? JsonObject)?.get("url")?.str())}")
        }
        log("web_content_link -> ${head(http, detail["web_content_link"]?.str())}")
        val medias = (detail["medias"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        for (media in medias) {
            val name = media["media_name"]?.str()
            val video = media["video"] as? JsonObject
            log(
                "media $name origin=${media["is_origin"]?.str()} category=${media["category"]?.str()} " +
                    "video_type=${video?.get("video_type")?.str()} codec=${video?.get("video_codec")?.str()}/" +
                    "${video?.get("audio_codec")?.str()} ${video?.get("width")?.str()}x${video?.get("height")?.str()}",
            )
            log("  keys: ${media.keys.sorted()}")
            val link = media["link"] as? JsonObject
            log("  link keys: ${link?.keys?.sorted()}")
            log("  link -> ${head(http, link?.get("url")?.str())}")
        }
    }

    /** Status, content type and the first bytes of [url], read as a 4 KiB range. */
    private fun head(http: HttpClient, url: String?): String {
        if (url.isNullOrBlank()) return "no url"
        return runCatching {
            val request = HttpRequest.newBuilder(URI(url))
                .header("Range", "bytes=0-4095")
                .header("User-Agent", "Piko/1.0")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val body = response.body()
            val type = response.headers().firstValue("Content-Type").orElse("-")
            val range = response.headers().firstValue("Content-Range").orElse("-")
            "${response.statusCode()} type=$type range=$range magic=${magic(body)} head=${body.take(12).joinToString("") { "%02x".format(it) }}"
        }.getOrElse { "failed: ${it.message}" }
    }

    private fun magic(bytes: ByteArray): String = when {
        bytes.size > 188 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() -> "mpegts"
        bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
        bytes.size > 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() -> "matroska"
        bytes.size > 7 && String(bytes, 0, 7, Charsets.ISO_8859_1) == "#EXTM3U" -> "m3u8"
        else -> "unknown"
    }

    private suspend fun findTranscodedVideo(client: PikPakClient): FileStat? {
        val queue = ArrayDeque(listOf(""))
        var folders = 0
        var checked = 0
        while (queue.isNotEmpty() && folders < MAX_FOLDERS && checked < MAX_VIDEOS) {
            val children = runCatching { client.listFiles(parentId = queue.removeFirst()) }.getOrDefault(emptyList())
            folders++
            for (file in children) {
                if (file.isFolder) {
                    queue.addLast(file.id)
                    continue
                }
                if (!file.mimeType.startsWith("video/") || file.sizeBytes !in MIN_BYTES..MAX_BYTES) continue
                checked++
                val detail = runCatching { client.getFile(file.id) }.getOrNull() ?: continue
                if (detail.medias.any { !it.isOrigin && it.video != null && it.link.url.isNotBlank() }) return file
            }
        }
        return null
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.content

    // Signed URLs carry tokens; the dump stays local but keep them out of it anyway
    private fun mask(text: String): String =
        text.replace(Regex("""([?&](?:sig|signature|token|e|fid|auth_key|x-[a-z-]+)=)[^&"\\]+"""), "$1***")

    private companion object {
        const val MAX_FOLDERS = 200
        const val MAX_VIDEOS = 40
        const val MIN_BYTES = 20L * 1024 * 1024
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
    }
}
