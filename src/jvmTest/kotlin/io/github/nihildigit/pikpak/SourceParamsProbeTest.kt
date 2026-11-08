package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions
import kotlin.random.Random
import kotlin.test.Test

/**
 * Whether a client can record where a file came from in `params.url`, the
 * field offline downloads and share restores get from the server: on the
 * instant-upload request itself, or afterwards through PATCH. Then whether
 * the value survives rename, move, copy and a trash round trip, and how long
 * it may be.
 *
 * Everything happens inside one uniquely named folder in the root, which is
 * trashed at the end. Uploads a few KB; creates no offline task. Opt in with
 * PIKPAK_PROBE=1.
 */
class SourceParamsProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private var requests = 0

    @Test
    fun `source url in params`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var root: String? = null
        try {
            client.login()
            val stamp = System.currentTimeMillis()
            root = client.createFolder("", "piko-params-probe-$stamp").also { requests++ }
            val instantDir = client.createFolder(root, "instant").also { requests++ }
            val moveDir = client.createFolder(root, "moved").also { requests++ }
            val copyDir = client.createFolder(root, "copied").also { requests++ }
            println("[setup] folders created")

            // A few KB nobody else holds, so the first upload really sends bytes
            val bytes = ByteArray(4096).also { Random(stamp).nextBytes(it) }
            val gcid = PikPakHash.fromSource(Buffer().apply { write(bytes) }, bytes.size.toLong())
            val uploaded = client.upload(root, "probe-$stamp.bin", bytes.size.toLong(), gcid, open = { Buffer().apply { write(bytes) } })
            requests += 4
            println("[upload] instant=${uploaded.instantUpload} bytes=${uploaded.bytesUploaded}")
            dump(client, "uploaded, untouched", uploaded.fileId)

            val magnet = "magnet:?xt=urn:btih:${hex(40)}&dn=piko-probe"

            // 1. params on the instant-upload request
            val instantRaw = runCatching {
                client.http.request(
                    method = HttpMethod.Post,
                    url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files",
                    captchaAction = "POST:/drive/v1/files",
                ) {
                    jsonBody(client.json, buildJsonObject {
                        put("kind", FileKind.FILE)
                        put("name", "instant-with-params.bin")
                        put("size", bytes.size.toString())
                        put("hash", gcid)
                        put("upload_type", "UPLOAD_TYPE_RESUMABLE")
                        put("parent_id", instantDir)
                        putJsonObject("params") {
                            put("url", magnet)
                            put("piko_source", "probe")
                        }
                        putJsonObject("objProvider") { put("provider", "UPLOAD_TYPE_UNKNOWN") }
                    })
                }
            }.also { requests++ }
            val instantFile = instantRaw.getOrNull()?.let { (it as? JsonObject)?.get("file") as? JsonObject }
            println("[instant+params] ok=${instantRaw.isSuccess} err=${instantRaw.exceptionOrNull()?.message} " +
                "phase=${instantFile?.str("phase")} responseParams=${instantFile?.get("params")}")
            val instantId = instantFile?.str("id")
            if (instantId != null) dump(client, "instant+params detail", instantId)

            // 2. PATCH params afterwards
            val target = uploaded.fileId
            patch(client, "patch url", target, buildJsonObject { putJsonObject("params") { put("url", magnet) } })
            dump(client, "after patch url", target)
            patch(client, "patch piko_source only", target, buildJsonObject { putJsonObject("params") { put("piko_source", "share:abc") } })
            dump(client, "after patch piko_source only", target)
            patch(client, "patch url again with name", target, buildJsonObject {
                put("name", "probe-$stamp-renamed-by-patch.bin")
                putJsonObject("params") { put("url", magnet) }
            })
            dump(client, "after patch url with name", target)
            if (instantId != null) {
                patch(client, "patch instant file url", instantId, buildJsonObject { putJsonObject("params") { put("url", magnet) } })
                dump(client, "instant file after patch", instantId)
            }

            val listed = client.listFiles(root).also { requests++ }.firstOrNull { it.id == target }
            println("[listing] params=${listed?.params}")

            // 3. Survives rename, move, copy, trash round trip?
            client.rename(target, "probe-$stamp-renamed.bin").also { requests++ }
            dump(client, "after rename", target)
            client.batchMove(listOf(target), moveDir).also { requests++ }
            dump(client, "after move", target)
            val copyTask = client.batchCopy(listOf(target), copyDir).also { requests++ }
            println("[copy] tasks=${copyTask.size}")
            delay(2000)
            val copies = client.listFiles(copyDir).also { requests++ }
            println("[copy listing] ${copies.map { "id!=src:${it.id != target} params=${it.params}" }}")
            copies.firstOrNull()?.let { dump(client, "copy detail", it.id) }
            client.batchTrash(listOf(target)).also { requests++ }
            client.batchUntrash(listOf(target)).also { requests++ }
            dump(client, "after trash and untrash", target)

            // 4. Length and shape
            val trackers = (1..80).joinToString("") { "&tr=udp%3A%2F%2Ftracker-$it.example.org%3A1337%2Fannounce" }
            val longMagnet = "magnet:?xt=urn:btih:${hex(40)}&dn=%E6%B5%8B%E8%AF%95%20piko$trackers"
            val longStored = roundTrip(client, "long magnet (${longMagnet.length} chars)", target, longMagnet)
            roundTrip(client, "share with pwd", target, "https://mypikpak.com/s/VNprobe0123?pwd=ab12")
            roundTrip(client, "share text", target, "链接：https://mypikpak.com/s/VNprobe0123 提取码：ab12")
            // No point looking for a ceiling when even a few KB do not go through
            if (!longStored) return@runBlocking

            var ok = longMagnet.length
            var bad: Int? = null
            var n = 8192
            while (n <= 1 shl 20) {
                if (roundTrip(client, "pad $n", target, padded(n))) ok = n else { bad = n; break }
                n *= 2
            }
            if (bad != null) {
                while (bad!! - ok > 256) {
                    val mid = (ok + bad) / 2
                    if (roundTrip(client, "pad $mid", target, padded(mid))) ok = mid else bad = mid
                }
            }
            println("[limit] largest stored=$ok smallest rejected=$bad")
        } finally {
            if (root != null) {
                runCatching { client.batchTrash(listOf(root)) }
                    .onSuccess { println("[cleanup] probe folder trashed") }
                    .onFailure { println("[cleanup] FAILED ${it.message}") }
                requests++
            }
            println("[requests] about $requests API calls (login not counted)")
            client.close()
        }
    }

    /**
     * The first run had every PATCH carrying `params` refused with
     * invalid_argument. This tells apart a refused key from refused contents:
     * an empty map, a key the server itself sets, and the server's own map
     * sent back unchanged with `url` added.
     */
    @Test
    fun `which params patches are refused`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var root: String? = null
        try {
            client.login()
            val stamp = System.currentTimeMillis()
            root = client.createFolder("", "piko-params-probe-$stamp").also { requests++ }
            val bytes = ByteArray(4096).also { Random(stamp).nextBytes(it) }
            val gcid = PikPakHash.fromSource(Buffer().apply { write(bytes) }, bytes.size.toLong())
            val id = client.upload(root, "probe-$stamp.bin", bytes.size.toLong(), gcid, open = { Buffer().apply { write(bytes) } }).fileId
            requests += 4
            val own = client.getFile(id).params.also { requests++ }
            val magnet = "magnet:?xt=urn:btih:${hex(40)}&dn=piko-probe"

            patch(client, "params {}", id, buildJsonObject { putJsonObject("params") { } })
            patch(client, "params platform=Upload", id, buildJsonObject { putJsonObject("params") { put("platform", "Upload") } })
            patch(client, "params own map + url", id, buildJsonObject {
                putJsonObject("params") {
                    for ((k, v) in own) put(k, v)
                    put("url", magnet)
                }
            })
            patch(client, "params url as plain http", id, buildJsonObject { putJsonObject("params") { put("url", "https://example.org/a.bin") } })
            patch(client, "name only (control)", id, buildJsonObject { put("name", "probe-$stamp-control.bin") })
            println("[final] params keys=${client.getFile(id).params.keys.sorted()} hasUrl=${client.getFile(id).params.containsKey("url")}")
            requests += 2
        } finally {
            if (root != null) {
                runCatching { client.batchTrash(listOf(root)) }
                    .onSuccess { println("[cleanup] probe folder trashed") }
                    .onFailure { println("[cleanup] FAILED ${it.message}") }
                requests++
            }
            println("[requests] about $requests API calls (login not counted)")
            client.close()
        }
    }

    private fun padded(length: Int): String {
        val head = "magnet:?xt=urn:btih:${"0".repeat(40)}&dn="
        return head + "a".repeat(length - head.length)
    }

    /** Writes [value] as params.url and reads it back; true when it came back intact. */
    private suspend fun roundTrip(client: PikPakClient, label: String, id: String, value: String): Boolean {
        val written = runCatching {
            client.http.request(
                method = HttpMethod.Patch,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files/$id",
                captchaAction = "PATCH:/drive/v1/files",
            ) { jsonBody(client.json, buildJsonObject { putJsonObject("params") { put("url", value) } }) }
        }
        requests++
        if (written.isFailure) {
            val e = written.exceptionOrNull()
            println("[$label] rejected: ${(e as? PikPakException)?.let { "code=${it.errorCode} msg=${it.errorMessage} http=${it.httpStatus}" } ?: e?.message}")
            return false
        }
        val back = client.getFile(id).params["url"]
        requests++
        val intact = back == value
        println("[$label] accepted, intact=$intact storedLength=${back?.length}" + if (!intact && back != null && back.length < 300) " stored=$back" else "")
        return intact
    }

    private suspend fun patch(client: PikPakClient, label: String, id: String, body: JsonObject) {
        val r = runCatching {
            client.http.request(
                method = HttpMethod.Patch,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files/$id",
                captchaAction = "PATCH:/drive/v1/files",
            ) { jsonBody(client.json, body) }
        }
        requests++
        println("[$label] ok=${r.isSuccess} err=${r.exceptionOrNull()?.message} responseParams=${(r.getOrNull() as? JsonObject)?.get("params")}")
    }

    /** Prints a file's detail with links, ids of the account and thumbnails left out. */
    private suspend fun dump(client: PikPakClient, label: String, id: String) {
        val raw = client.http.request(
            method = HttpMethod.Get,
            url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files/$id",
            captchaAction = "GET:/drive/v1/files",
        ) as JsonObject
        requests++
        val hidden = setOf("links", "web_content_link", "medias", "thumbnail_link", "icon_link", "user_id", "id", "parent_id", "hash", "md5_checksum", "original_url")
        val shown = JsonObject(raw.filterKeys { it !in hidden })
        println("[$label] keys=${raw.keys.sorted()}")
        println("[$label] params=${raw["params"]} name=${raw.str("name")} trashed=${raw.str("trashed")} detail=$shown")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun hex(n: Int) = (1..n).joinToString("") { "0123456789abcdef"[Random.nextInt(16)].toString() }
}
