package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.OssSign
import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.formatHttpDate
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Upload behaviour PikPak does not document, measured on the live account:
 *  1. whether `/drive/v1/resource/cid` resolves content the account does not
 *     hold (public release ISOs, read through three 20 KB range requests);
 *  2. what an initiated but never completed upload leaves behind, and whether
 *     OSS AbortMultipartUpload works with the STS credentials;
 *  3. whether re-initiating the same upload returns the same file and OSS key;
 *  4. whether an init with a gcid from the CID lookup completes instantly.
 *
 * Everything written goes into a fresh `pikpak-kotlin-probe-upload-<millis>`
 * root folder that is trashed and then permanently deleted at the end.
 * Opt in with PIKPAK_PROBE=1.
 */
class UploadLifecycleProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val web: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private data class Iso(val name: String, val torrentUrl: String, val isoUrl: String)

    private data class IsoResult(val iso: Iso, val size: Long, val magnetGcid: String?, val cidGcid: String?)

    private val candidates = listOf(
        Iso(
            "debian-13.7.0-amd64-netinst.iso",
            "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/debian-13.7.0-amd64-netinst.iso.torrent",
            "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.7.0-amd64-netinst.iso",
        ),
        Iso(
            "ubuntu-26.04.1-live-server-amd64.iso",
            "https://releases.ubuntu.com/26.04.1/ubuntu-26.04.1-live-server-amd64.iso.torrent",
            "https://releases.ubuntu.com/26.04.1/ubuntu-26.04.1-live-server-amd64.iso",
        ),
    )

    @Test
    fun `upload lifecycle`(): Unit = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val work = kotlin.io.path.createTempDirectory("upload-probe").toFile()
        var probe: String? = null
        val created = mutableSetOf<String>()
        try {
            client.login()

            // Item 1: read-only.
            val isoResults = candidates.mapNotNull { iso ->
                runCatching { cidLookup(client, iso) }.onFailure { println("[iso ${iso.name}] failed: $it") }.getOrNull()
            }
            checkAccountHolds(client, isoResults)

            probe = client.createFolder("", "pikpak-kotlin-probe-upload-${System.currentTimeMillis()}")
            println("[probe] folder=$probe")

            // Item 2 and 3.
            val local = File(work, "probe-${System.currentTimeMillis()}.bin").apply { writeBytes(Random.nextBytes(1024 * 1024)) }
            val gcid = PikPakHash.fromPath(Path(local.absolutePath))
            println("[local] size=${local.length()} gcid=$gcid")
            abandonedUpload(client, probe, local.name, local.length(), gcid, created)

            // Item 4.
            val iso = isoResults.filter { it.cidGcid != null }.minByOrNull { it.size }
            when {
                iso == null -> println("[instant] skipped: no CID lookup hit")
                iso.size > 5_000_000_000L -> println("[instant] skipped: ${iso.iso.name} is ${iso.size} bytes")
                else -> instantViaInit(client, probe, iso, created)
            }
        } finally {
            cleanup(client, probe, created)
            client.close()
            work.deleteRecursively()
        }
    }

    /**
     * Item 5: whether init needs the gcid at all. Each variant uploads fresh
     * random bytes, so no answer can come from content PikPak already knows.
     * The last variant carries the real gcid as a control for the raw flow.
     */
    @Test
    fun `gcid at init`(): Unit = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var probe: String? = null
        val created = mutableSetOf<String>()
        try {
            client.login()
            probe = client.createFolder("", "pikpak-kotlin-probe-upload-${System.currentTimeMillis()}")
            println("[probe] folder=$probe")
            val variants = listOf("omitted", "empty", "wrong", "correct")
            for (variant in variants) {
                runCatching { uploadWithHash(client, probe, variant, created) }
                    .onFailure { println("[$variant] aborted: ${describe(it)}") }
            }
        } finally {
            cleanup(client, probe, created)
            client.close()
        }
    }

    private suspend fun uploadWithHash(client: PikPakClient, probe: String, variant: String, created: MutableSet<String>) {
        val bytes = Random.nextBytes(1024 * 1024)
        val realGcid = PikPakHash.fromSource(kotlinx.io.Buffer().apply { write(bytes) }, bytes.size.toLong()).uppercase()
        val sent: String? = when (variant) {
            "omitted" -> null
            "empty" -> ""
            "wrong" -> hex(Random.nextBytes(20))
            else -> realGcid
        }
        val name = "gcid-$variant-${System.currentTimeMillis()}.bin"
        println("[$variant] real gcid=$realGcid sent=${sent ?: "<no field>"}")
        val r = init(client, probe, name, bytes.size.toLong(), sent)
        println("[$variant] init -> ${redact(r)}")
        val id = fileId(r)?.also { created += it } ?: return
        val oss = ossParams(r) ?: run { println("[$variant] no resumable params"); return }
        val uploadId = ossInitiate(client, oss) ?: run { println("[$variant] OSS initiate gave no UploadId"); return }
        val chunk = 256 * 1024
        val etags = (0 until bytes.size / chunk).map { i ->
            val res = oss(client, oss, HttpMethod.Put, "partNumber=${i + 1}&uploadId=$uploadId", bytes.copyOfRange(i * chunk, (i + 1) * chunk))
            Regex("etag=\"?([0-9A-Fa-f]+)").find(res)?.groupValues?.get(1) ?: error("part ${i + 1}: $res")
        }
        val xml = buildString {
            append("<CompleteMultipartUpload>")
            etags.forEachIndexed { i, e -> append("<Part><PartNumber>${i + 1}</PartNumber><ETag>$e</ETag></Part>") }
            append("</CompleteMultipartUpload>")
        }
        println("[$variant] OSS complete -> ${oss(client, oss, HttpMethod.Post, "uploadId=$uploadId", xml.encodeToByteArray())}")

        val taskId = (r as? JsonObject)?.get("task")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        var detail: Any = ""
        for (attempt in 1..20) {
            delay(1500)
            detail = runCatching {
                client.http.request(HttpMethod.Get, "${PikPakConstants.DRIVE_BASE}/drive/v1/files/$id", captchaAction = "GET:/drive/v1/files")
            }.getOrElse { describe(it) }
            val phase = ((detail as? JsonObject)?.get("phase") as? JsonPrimitive)?.contentOrNull
            if (phase != null && phase != TaskPhase.PENDING && phase != TaskPhase.RUNNING) {
                println("[$variant] settled after ${attempt * 1.5}s")
                break
            }
        }
        println("[$variant] detail -> ${if (detail is JsonObject) brief(detail) else detail}")
        if (taskId != null) {
            val task = runCatching {
                client.http.request(HttpMethod.Get, "${PikPakConstants.DRIVE_BASE}/drive/v1/tasks/$taskId", captchaAction = "GET:/drive/v1/tasks")
            }.getOrElse { describe(it) }
            println("[$variant] task -> ${redact(task)}")
        }
        val listed = runCatching { client.listFiles(probe).firstOrNull { it.id == id } }.getOrNull()
        println("[$variant] listFiles -> ${listed?.let { "phase=${it.phase} hash=${it.hash} size=${it.size}" }}")
        val stored = ((detail as? JsonObject)?.get("hash") as? JsonPrimitive)?.contentOrNull
        println("[$variant] stored hash=$stored equalsReal=${stored.equals(realGcid, true)} equalsSent=${sent != null && stored.equals(sent, true)}")

        val link = runCatching { client.getFile(id).downloadUrl }.getOrElse { println("[$variant] getFile ${describe(it)}"); null }
        if (link == null) {
            println("[$variant] no download link")
        } else {
            val got = web.send(HttpRequest.newBuilder(URI(link)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            println("[$variant] download HTTP ${got.statusCode()} size=${got.body().size} identical=${got.body().contentEquals(bytes)}")
        }
    }

    // ---- item 1 -------------------------------------------------------------

    private suspend fun cidLookup(client: PikPakClient, iso: Iso): IsoResult {
        val torrent = get(iso.torrentUrl)
        val infoHash = hex(sha1(infoDict(torrent))).lowercase()
        println("[iso ${iso.name}] torrent=${torrent.size}B btih=$infoHash")
        val resolved = client.resolveMagnet("magnet:?xt=urn:btih:$infoHash")
        println("[iso ${iso.name}] resolveMagnet -> ${resolved?.let { r -> "name=${r.name} files=${r.files.map { "${it.path} ${it.size} ${it.gcid}" }}" }}")
        val magnetFile = resolved?.files?.firstOrNull { it.name.endsWith(".iso") }

        val size = headSize(iso.isoUrl)
        println("[iso ${iso.name}] mirror size=$size (magnet size=${magnetFile?.size})")
        val windows = listOf(0L, size / 3, size - WINDOW).map { range(iso.isoUrl, it, WINDOW) }
        val cid = hex(sha1(*windows.toTypedArray()))
        println("[iso ${iso.name}] cid=$cid")

        val raw = cidQuery(client, cid.lowercase(), size)
        println("[iso ${iso.name}] resource/cid -> $raw")
        val cidGcid = (raw as? JsonObject)?.let { findGcid(it) }
        println("[iso ${iso.name}] cid gcid=$cidGcid magnet gcid=${magnetFile?.gcid} match=${cidGcid != null && cidGcid.equals(magnetFile?.gcid, true)}")
        return IsoResult(iso, size, magnetFile?.gcid, cidGcid)
    }

    /** The first 40-hex value under a key naming a hash, wherever the response nests it. */
    private fun findGcid(e: JsonElement): String? = when (e) {
        is JsonObject -> e.entries.firstNotNullOfOrNull { (k, v) ->
            if (v is JsonPrimitive && (k.contains("gcid", true) || k == "hash") && v.contentOrNull?.matches(HEX40) == true) v.content
            else findGcid(v)
        }
        is JsonArray -> e.firstNotNullOfOrNull { findGcid(it) }
        else -> null
    }

    private suspend fun cidQuery(client: PikPakClient, cid: String, size: Long): Any = runCatching {
        client.http.request(
            HttpMethod.Get,
            buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/resource/cid", mapOf("cid" to cid, "file_size" to size.toString())),
            captchaAction = "GET:/drive/v1/resource/cid",
        )
    }.getOrElse { describe(it) }

    /** Walks the drive once and compares by gcid and size, so no file name is logged. */
    private suspend fun checkAccountHolds(client: PikPakClient, isos: List<IsoResult>) {
        val gcids = isos.flatMap { listOfNotNull(it.magnetGcid, it.cidGcid) }.map { it.uppercase() }.toSet()
        val sizes = isos.map { it.size.toString() }.toSet()
        var folders = 0
        var files = 0
        val queue = ArrayDeque(listOf(""))
        while (queue.isNotEmpty() && folders < 3000) {
            val parent = queue.removeFirst()
            folders++
            for (f in client.listFiles(parent)) {
                if (f.kind.endsWith("folder")) queue += f.id
                else {
                    files++
                    if (f.hash.uppercase() in gcids || f.size in sizes) println("[holds] match: size=${f.size} hash=${f.hash}")
                }
            }
        }
        println("[holds] walked folders=$folders (complete=${queue.isEmpty()}) files=$files; trash:")
        client.listTrash().filter { it.hash.uppercase() in gcids || it.size in sizes }.forEach { println("[holds] trash match size=${it.size} hash=${it.hash}") }
    }

    // ---- items 2 and 3 ------------------------------------------------------

    private suspend fun abandonedUpload(
        client: PikPakClient,
        probe: String,
        name: String,
        size: Long,
        gcid: String,
        created: MutableSet<String>,
    ) {
        val t0 = Instant.now()
        val init1 = init(client, probe, name, size, gcid)
        println("[init1] at=$t0 -> ${redact(init1)}")
        val id1 = fileId(init1)?.also { created += it }
        val oss1 = ossParams(init1)
        oss1?.expiration?.let { println("[init1] expiration=$it, ${Duration.between(t0, Instant.parse(it)).seconds}s after the request") }

        snapshot(client, probe, "after init1", id1)
        (init1 as? JsonObject)?.get("task")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { taskId ->
            println("[init1] getTask($taskId) -> ${runCatching { redact(client.http.request(HttpMethod.Get, "${PikPakConstants.DRIVE_BASE}/drive/v1/tasks/$taskId", captchaAction = "GET:/drive/v1/tasks")) }.getOrElse { describe(it) }}")
        }
        taskListings(client, id1)

        if (oss1 != null) {
            val uploadId = runCatching { ossInitiate(client, oss1) }.onFailure { println("[oss] initiate failed: ${describe(it)}") }.getOrNull()
            if (uploadId != null) {
                println("[oss] uploadId=${uploadId.take(8)}…")
                val part = oss(client, oss1, HttpMethod.Put, "partNumber=1&uploadId=$uploadId", Random.nextBytes(256 * 1024))
                println("[oss] upload part 1 -> $part")
                val list = oss(client, oss1, HttpMethod.Get, "uploadId=$uploadId", null)
                println("[oss] list parts -> $list")
                val abort = oss(client, oss1, HttpMethod.Delete, "uploadId=$uploadId", null)
                println("[oss] abort -> $abort")
                val again = oss(client, oss1, HttpMethod.Get, "uploadId=$uploadId", null)
                println("[oss] list parts after abort -> $again")
            }
            snapshot(client, probe, "after oss abort", id1)
        }

        // Item 3a: re-init while the pending file exists.
        val init2 = init(client, probe, name, size, gcid)
        val id2 = fileId(init2)?.also { created += it }
        val oss2 = ossParams(init2)
        println("[init2 pending exists] ${redact(init2)}")
        println("[init2] sameId=${id1 == id2} sameKey=${oss1?.key == oss2?.key} sameAk=${oss1?.accessKeyId == oss2?.accessKeyId} name=${fileName(init2)}")
        snapshot(client, probe, "after init2", id1)

        // Deleting the pending file(s).
        val pending = listOfNotNull(id1, id2).distinct()
        println("[delete] batchTrash($pending) -> ${runCatching { client.batchTrash(pending); "ok" }.getOrElse { describe(it) }}")
        delay(2000)
        snapshot(client, probe, "after batchTrash", id1)
        println("[delete] batchDelete($pending) -> ${runCatching { client.batchDelete(pending); "ok" }.getOrElse { describe(it) }}")
        delay(2000)
        snapshot(client, probe, "after batchDelete", id1)
        taskListings(client, id1)

        // Item 3b: re-init after deletion.
        val init3 = init(client, probe, name, size, gcid)
        val id3 = fileId(init3)?.also { created += it }
        val oss3 = ossParams(init3)
        println("[init3 after delete] ${redact(init3)}")
        println("[init3] sameIdAs1=${id1 == id3} sameKeyAs1=${oss1?.key == oss3?.key} sameKeyAs2=${oss2?.key == oss3?.key}")
    }

    /** [hash] null leaves the field out of the body entirely. */
    private suspend fun init(client: PikPakClient, parent: String, name: String, size: Long, hash: String?): Any = runCatching {
        val body = buildJsonObject {
            put("kind", FileKind.FILE)
            put("name", name)
            put("size", size.toString())
            if (hash != null) put("hash", hash)
            put("upload_type", "UPLOAD_TYPE_RESUMABLE")
            put("parent_id", parent)
            putJsonObject("body") {
                put("duration", "")
                put("width", "")
                put("height", "")
            }
            putJsonObject("objProvider") { put("provider", "UPLOAD_TYPE_UNKNOWN") }
        }
        client.http.request(HttpMethod.Post, "${PikPakConstants.DRIVE_BASE}/drive/v1/files", captchaAction = "POST:/drive/v1/files") {
            jsonBody(client.json, body)
        }
    }.getOrElse { describe(it) }

    private fun fileNode(r: Any): JsonObject? = (r as? JsonObject)?.get("file") as? JsonObject
    private fun fileId(r: Any) = fileNode(r)?.get("id")?.jsonPrimitive?.contentOrNull
    private fun fileName(r: Any) = fileNode(r)?.get("name")?.jsonPrimitive?.contentOrNull

    /** Raw folder listing (no phase filter), trash matches and the file detail. */
    private suspend fun snapshot(client: PikPakClient, probe: String, label: String, id: String?) {
        val listing = runCatching {
            client.http.request(
                HttpMethod.Get,
                buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files", mapOf("parent_id" to probe, "limit" to "100", "filters" to """{"trashed":{"eq":false}}""")),
                captchaAction = "GET:/drive/v1/files",
            )
        }.getOrElse { describe(it) }
        val files = ((listing as? JsonObject)?.get("files") as? JsonArray).orEmpty().map { it.jsonObject }
        println("[$label] folder has ${files.size}: ${files.map { brief(it) }}")
        val trash = runCatching { client.listTrash().filter { it.parentId == probe || it.id == id } }.getOrElse { println("[$label] listTrash failed ${describe(it)}"); emptyList() }
        println("[$label] trash matches: ${trash.map { "id=${it.id} phase=${it.phase} size=${it.size}" }}")
        if (id != null) {
            val detail = runCatching {
                client.http.request(HttpMethod.Get, "${PikPakConstants.DRIVE_BASE}/drive/v1/files/$id", captchaAction = "GET:/drive/v1/files")
            }.getOrElse { describe(it) }
            println("[$label] detail($id) -> ${if (detail is JsonObject) brief(detail) else detail}")
        }
    }

    private fun brief(o: JsonObject): String = listOf("id", "name", "phase", "size", "hash", "trashed", "kind", "audit")
        .joinToString(" ") { k -> "$k=${o[k]?.let { v -> if (v is JsonPrimitive) v.contentOrNull else v.toString().take(120) }}" }

    private suspend fun taskListings(client: PikPakClient, fileId: String?) {
        val variants = listOf(
            mapOf("type" to "upload"),
            mapOf("type" to "user#upload"),
            mapOf("type" to "offline", "filters" to """{"phase":{"in":"PHASE_TYPE_RUNNING,PHASE_TYPE_PENDING,PHASE_TYPE_ERROR"}}"""),
            emptyMap(),
        )
        for (q in variants) {
            val r = runCatching {
                client.http.request(
                    HttpMethod.Get,
                    buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/tasks", q + mapOf("limit" to "100")),
                    captchaAction = "GET:/drive/v1/tasks",
                )
            }.getOrElse { describe(it) }
            val tasks = ((r as? JsonObject)?.get("tasks") as? JsonArray).orEmpty()
            val mine = tasks.filter { it.toString().contains(fileId ?: "\u0000") }
            println("[tasks $q] ${if (r is JsonObject) "count=${tasks.size} types=${tasks.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull }.distinct()} mentioningFile=${mine.map { redact(it) }}" else r}")
        }
    }

    // ---- item 4 -------------------------------------------------------------

    private suspend fun instantViaInit(client: PikPakClient, probe: String, iso: IsoResult, created: MutableSet<String>) {
        val before = client.getTransferQuota().account.upload
        println("[instant] quota before used=${before.usedBytes} limit=${before.limitBytes}")
        val r = init(client, probe, iso.iso.name, iso.size, iso.cidGcid!!)
        println("[instant] init -> ${redact(r)}")
        val id = fileId(r)?.also { created += it }
        println("[instant] phase=${fileNode(r)?.get("phase")} resumable=${(r as? JsonObject)?.containsKey("resumable")}")
        var after = before
        repeat(10) {
            delay(2000)
            after = client.getTransferQuota().account.upload
            if (after.usedBytes != before.usedBytes) return@repeat
        }
        val delta = after.usedBytes - before.usedBytes
        println("[instant] quota after used=${after.usedBytes} delta=$delta (${"%.2f".format(delta * 100.0 / iso.size)}% of size)")
        if (id != null) {
            val d = runCatching { client.getFile(id) }.getOrElse { null }
            println("[instant] getFile hash=${d?.hash} size=${d?.size} link=${d?.downloadUrl != null}")
            println("[instant] batchDelete -> ${runCatching { client.batchDelete(listOf(id)); "ok" }.getOrElse { describe(it) }}")
        }
    }

    // ---- cleanup ------------------------------------------------------------

    private suspend fun cleanup(client: PikPakClient, probe: String?, created: Set<String>) {
        if (probe == null) return
        runCatching { client.batchTrash(listOf(probe)) }.onFailure { println("[cleanup] trash ${describe(it)}") }
        delay(1500)
        runCatching { client.batchDelete(listOf(probe) + created) }.onFailure { println("[cleanup] delete ${describe(it)}") }
        repeat(30) {
            val inRoot = client.listFiles("").any { it.id == probe }
            val inTrash = client.listTrash().any { it.id == probe || it.id in created || it.parentId == probe }
            val detail = runCatching { client.getFile(probe); "exists" }.getOrElse { describe(it) }
            if (!inRoot && !inTrash && detail != "exists") {
                println("[cleanup] probe gone: root=false trash=false detail=$detail")
                return
            }
            delay(1000)
        }
        println("[cleanup] probe still visible after 30 s")
    }

    // ---- OSS ----------------------------------------------------------------

    private data class Oss(
        val bucket: String, val accessKeyId: String, val accessKeySecret: String,
        val endpoint: String, val key: String, val securityToken: String, val expiration: String?,
    )

    private fun ossParams(r: Any): Oss? {
        val p = ((r as? JsonObject)?.get("resumable") as? JsonObject)?.get("params") as? JsonObject ?: return null
        fun s(k: String) = p[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        println("[resumable.params keys] ${p.keys}")
        return Oss(s("bucket"), s("access_key_id"), s("access_key_secret"), s("endpoint"), s("key"), s("security_token"), p["expiration"]?.jsonPrimitive?.contentOrNull)
    }

    private suspend fun ossInitiate(client: PikPakClient, oss: Oss): String? {
        val xml = oss(client, oss, HttpMethod.Post, "uploads", null)
        return Regex("<UploadId>(.+?)</UploadId>").find(xml)?.groupValues?.get(1)
    }

    /** One signed OSS call; returns "HTTP <status> <body prefix>" or the error. */
    private suspend fun oss(client: PikPakClient, oss: Oss, method: HttpMethod, rawQuery: String, body: ByteArray?): String = runCatching {
        val date = Clock.System.now().formatHttpDate()
        val path = "/${oss.key}"
        val auth = OssSign.authorizationHeader(
            method, oss.bucket, path, rawQuery, OSS_CONTENT_TYPE, date,
            headersOf("X-Oss-Security-Token", oss.securityToken), oss.accessKeyId, oss.accessKeySecret,
        )
        client.http.sendRaw(method, "https://${oss.endpoint}$path?$rawQuery", configure = {
            headers {
                set(HttpHeaders.UserAgent, OSS_USER_AGENT)
                set(HttpHeaders.Date, date)
                set("X-Oss-Security-Token", oss.securityToken)
                set(HttpHeaders.Authorization, auth)
            }
            contentType(ContentType.parse(OSS_CONTENT_TYPE))
            if (body != null) setBody(body)
        }) { resp ->
            "HTTP ${resp.status.value} etag=${resp.headers[HttpHeaders.ETag]} body=${resp.bodyAsText().replace(Regex("\\s+"), " ").take(600)}"
        }
    }.getOrElse { describe(it) }

    // ---- helpers ------------------------------------------------------------

    private fun redact(e: Any?): String = if (e is JsonElement) redactJson(e).toString() else e.toString()

    private fun redactJson(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            if (SECRET_KEYS.any { k.contains(it, true) } && v is JsonPrimitive) JsonPrimitive("<redacted ${v.contentOrNull?.length ?: 0}>") else redactJson(v)
        })
        is JsonArray -> JsonArray(e.map { redactJson(it) })
        else -> e
    }

    private fun describe(e: Throwable): String = when (e) {
        is PikPakException -> "PikPakException http=${e.httpStatus} code=${e.errorCode} error=${e.errorMessage} body=${e.rawBody?.take(600)}"
        is UrlExpiredException -> "UrlExpired http=${e.httpStatus} body=${e.rawBody?.take(600)}"
        else -> e.toString()
    }

    private fun get(url: String): ByteArray =
        web.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            .also { check(it.statusCode() == 200) { "GET $url -> ${it.statusCode()}" } }.body()

    private fun headSize(url: String): Long {
        val r = web.send(HttpRequest.newBuilder(URI(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        return r.headers().firstValueAsLong("content-length").orElseThrow { IllegalStateException("no length for $url (${r.statusCode()})") }
    }

    private fun range(url: String, start: Long, length: Long): ByteArray {
        val r = web.send(
            HttpRequest.newBuilder(URI(url)).header("Range", "bytes=$start-${start + length - 1}").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(r.statusCode() == 206 && r.body().size.toLong() == length) { "range $start -> ${r.statusCode()} ${r.body().size}B" }
        return r.body()
    }

    /** Bytes of the top-level `info` value, whose SHA-1 is the v1 info hash. */
    private fun infoDict(t: ByteArray): ByteArray {
        check(t[0] == 'd'.code.toByte())
        var p = 1
        while (t[p] != 'e'.code.toByte()) {
            val keyEnd = skip(t, p)
            val key = String(t, t.indexOf(':'.code.toByte(), p) + 1, keyEnd - t.indexOf(':'.code.toByte(), p) - 1)
            val valueEnd = skip(t, keyEnd)
            if (key == "info") return t.copyOfRange(keyEnd, valueEnd)
            p = valueEnd
        }
        error("no info dict")
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int { var i = from; while (this[i] != b) i++; return i }

    private fun skip(t: ByteArray, p: Int): Int = when (t[p].toInt().toChar()) {
        'i' -> t.indexOf('e'.code.toByte(), p) + 1
        'l', 'd' -> { var q = p + 1; while (t[q] != 'e'.code.toByte()) q = skip(t, q); q + 1 }
        else -> { val c = t.indexOf(':'.code.toByte(), p); c + 1 + String(t, p, c - p).toInt() }
    }

    private fun sha1(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { parts.forEach { update(it) } }.digest()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    private companion object {
        const val WINDOW = 0x5000L
        val HEX40 = Regex("[0-9A-Fa-f]{40}")
        val SECRET_KEYS = listOf("secret", "token", "access_key_id", "signature", "authorization")
        const val OSS_USER_AGENT = "aliyun-sdk-android/2.9.5(Linux/Android 11/ONEPLUS%20A6000;RKQ1.201217.002)"
        const val OSS_CONTENT_TYPE = "application/octet-stream"
    }
}
