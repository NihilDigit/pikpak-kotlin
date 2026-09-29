package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a free account can do through the instant path, measured without
 * spending its daily cloud downloads.
 *
 * The daily count is readable: `/drive/v1/about` carries it under
 * `quotas.cloud_download` (limit 3 plus `complimentary`), which [getQuota]
 * does not decode. So the question "does instantCreate count as a cloud
 * download" is one create with the counter read on either side, not a run
 * that submits offline tasks until the server refuses.
 *
 * Also records which links a free account gets for a real episode — the
 * original, the transcodes, or only up to 720P — reading 1 MiB from each so
 * the whole run stays at a few MiB of the 20 GiB daily downstream.
 *
 * Uses PIKPAK_FREE_USERNAME / PIKPAK_FREE_PASSWORD and refuses to run on a
 * premium account. Opt in with PIKPAK_PROBE=1. Writes `build/free-account-probe.txt`.
 */
class FreeAccountProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_FREE_USERNAME"]?.takeIf { it.isNotBlank() }
    private val password = env["PIKPAK_FREE_PASSWORD"]?.takeIf { it.isNotBlank() }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[free] $line")
        report.appendLine(line)
    }

    @Test
    fun `probe the instant path on a free account`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no free-account credentials in .env")

        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )
        var createdId: String? = null
        try {
            client.login()
            log("=== free account probe, ${Clock.System.now()} ===")

            val transfer0 = client.getTransferQuota().account
            log("vip_status=${transfer0.vipStatus} expire=${transfer0.expireTime.ifEmpty { "-" }}")
            if (transfer0.vipStatus == "ok") {
                log("ABORT: this is a premium account")
                return@runBlocking
            }
            logTransfer("transfer before", transfer0)

            val about0 = rawAbout(client)
            log("about before: user_type=${about0["user_type"]} cloud_download=${about0["quotas"]}")
            val tasks0 = client.listOfflineTasks(phaseFilter = ALL_PHASES).tasks.map { it.id }.toSet()
            log("offline tasks before: ${tasks0.size}")

            val remaining = client.getQuota().quota.remainingBytes
            val (label, target) = pickTarget(client, remaining) ?: run {
                Assumptions.assumeTrue(false, "no sample pack has a small indexed video")
                return@runBlocking
            }
            log("pack: $label")
            log("target: ${target.path} (${mib(target.size)}), free space ${mib(remaining)}")

            // 1 --- does an instant create draw on the daily cloud-download count?
            log("")
            log("--- 1: instantCreate against the daily count ---")
            val mark = TimeSource.Monotonic.markNow()
            val created = runCatching { client.instantCreate(target, parentId = "") }
            created.onFailure {
                log("instantCreate FAILED after ${mark.elapsedNow().inWholeMilliseconds} ms: ${describe(it)}")
                return@runBlocking
            }
            createdId = created.getOrThrow()
            log("instantCreate: ${mark.elapsedNow().inWholeMilliseconds} ms -> $createdId")

            delay(SETTLE)
            val about1 = rawAbout(client)
            log("about after: cloud_download=${about1["quotas"]}")
            val used0 = cloudDownloadUsage(about0)
            val used1 = cloudDownloadUsage(about1)
            log(
                when {
                    used0 == null || used1 == null -> "VERDICT: counter unreadable ($used0 -> $used1)"
                    used1 == used0 -> "VERDICT: instantCreate does NOT count as a cloud download ($used0 -> $used1)"
                    else -> "VERDICT: instantCreate COUNTS as a cloud download ($used0 -> $used1)"
                },
            )
            val newTasks = client.listOfflineTasks(phaseFilter = ALL_PHASES).tasks.filter { it.id !in tasks0 }
            log("new offline tasks: ${newTasks.size}${newTasks.joinToString("") { "\n  ${it.type} ${it.phase} ${it.name}" }}")

            // 2 --- which links does a free account get?
            log("")
            log("--- 2: links on a free account ---")
            val detail = client.getFile(createdId)
            log("links: ${detail.links.mapValues { it.value.url.isNotBlank() }}, web_content_link=${detail.webContentLink.isNotBlank()}")
            if (detail.medias.isEmpty()) log("medias: none")
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            for (m in detail.medias) {
                val read = m.url?.let { readHead(http, it) } ?: "no url"
                log("media ${m.mediaName} / ${m.resolutionName} origin=${m.isOrigin} default=${m.isDefault} visible=${m.isVisible}: $read")
            }
            detail.downloadUrl?.let { log("octet-stream: ${readHead(http, it)}") }

            delay(SETTLE)
            logTransfer("transfer after", client.getTransferQuota().account)
        } finally {
            runCatching { createdId?.let { client.deleteFile(it) } }
                .onFailure { log("cleanup of $createdId failed: ${describe(it)}") }
            client.close()
            File("build").mkdirs()
            File("build/free-account-probe.txt").writeText(report.toString())
            println("[free] report written to build/free-account-probe.txt")
        }
    }

    /**
     * The same episode on the premium account of PIKPAK_USERNAME, so a free
     * account's missing transcodes can be told apart from content PikPak has
     * not transcoded at all.
     */
    @Test
    fun `list the same episode's medias on premium`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        val premiumUser = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
        val premiumPassword = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
        Assumptions.assumeTrue(premiumUser != null && premiumPassword != null, "no premium credentials in .env")

        val client = PikPakClient(account = premiumUser!!, password = premiumPassword!!, sessionStore = InMemorySessionStore())
        var createdId: String? = null
        try {
            client.login()
            log("=== premium comparison, ${Clock.System.now()} ===")
            log("vip_status=${client.getTransferQuota().account.vipStatus}")
            val (label, target) = pickTarget(client, Long.MAX_VALUE) ?: return@runBlocking
            log("pack: $label, target: ${target.path} (${mib(target.size)})")
            createdId = client.instantCreate(target, parentId = "")
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val detail = client.getFile(createdId)
            log("links: ${detail.links.mapValues { it.value.url.isNotBlank() }}")
            if (detail.medias.isEmpty()) log("medias: none")
            for (m in detail.medias) {
                val read = m.url?.let { readHead(http, it) } ?: "no url"
                log("media ${m.mediaName} / ${m.resolutionName} origin=${m.isOrigin} default=${m.isDefault} visible=${m.isVisible}: $read")
            }
        } finally {
            runCatching { createdId?.let { client.deleteFile(it) } }
                .onFailure { log("cleanup of $createdId failed: ${describe(it)}") }
            client.close()
            File("build").mkdirs()
            File("build/free-account-probe-premium.txt").writeText(report.toString())
        }
    }

    /**
     * Finds, read-only, a video in the premium drive that PikPak has
     * transcoded, then instant-creates its gcid on the free account: the one
     * pairing that says whether a free account is denied the transcodes or
     * the original.
     */
    @Test
    fun `compare a transcoded video across tiers`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        val premiumUser = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
        val premiumPassword = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
        Assumptions.assumeTrue(premiumUser != null && premiumPassword != null && username != null && password != null)

        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val premium = PikPakClient(account = premiumUser!!, password = premiumPassword!!, sessionStore = InMemorySessionStore())
        val free = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var createdId: String? = null
        try {
            premium.login()
            free.login()
            log("=== transcode comparison, ${Clock.System.now()} ===")
            val source = findTranscoded(premium) ?: run {
                log("no transcoded video found within the walk budget")
                return@runBlocking
            }
            log("premium: ${source.name.substringAfterLast('.')} ${mib(source.sizeBytes)}")
            logMedias(http, source.medias)

            val file = ResolvedFile(path = "probe-transcode.${source.name.substringAfterLast('.')}", size = source.sizeBytes, gcid = source.hash)
            createdId = free.instantCreate(file, parentId = "")
            val detail = free.getFile(createdId)
            log("free: links ${detail.links.mapValues { it.value.url.isNotBlank() }}")
            logMedias(http, detail.medias.filter { it.resolutionName != "1080P" || it.isOrigin })

            // A 1 MiB read is mostly first-byte latency; a throttle only shows over a sustained read.
            detail.downloadUrl?.let { log("free octet-stream, $SUSTAINED_BYTES B: ${readHead(http, it, SUSTAINED_BYTES)}") }
            detail.medias.firstOrNull { it.resolutionName == "720P" }?.url
                ?.let { log("free 720P, $SUSTAINED_BYTES B: ${readHead(http, it, SUSTAINED_BYTES)}") }
        } finally {
            runCatching { createdId?.let { free.deleteFile(it) } }
                .onFailure { log("cleanup of $createdId failed: ${describe(it)}") }
            premium.close()
            free.close()
            File("build").mkdirs()
            File("build/free-account-probe-transcode.txt").writeText(report.toString())
        }
    }

    /**
     * What archiving folders as references needs to know before it can warn
     * honestly: whether a restore that fails still draws the 15 % upload
     * charge, and whether a reference can be checked without restoring it.
     *
     * A random gcid stands in for content PikPak no longer holds. The check
     * goes through [gcidByCid], which needs the CID; that is 60 KB of reads,
     * cheap enough to take at archive time and store beside the gcid.
     */
    @Test
    fun `probe dead references and a read-only liveness check`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        val premiumUser = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
        val premiumPassword = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
        Assumptions.assumeTrue(premiumUser != null && premiumPassword != null && username != null && password != null)

        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val premium = PikPakClient(account = premiumUser!!, password = premiumPassword!!, sessionStore = InMemorySessionStore())
        val free = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            premium.login()
            free.login()
            log("=== dead references, ${Clock.System.now()} ===")

            // 1 --- does a create that fails still cost upload?
            log("--- 1: instantCreate of a gcid PikPak does not hold ---")
            val deadGcid = randomHex(40).uppercase()
            val before = free.getTransferQuota().account.upload.usedBytes
            val size = 500L * 1024 * 1024
            val mark = TimeSource.Monotonic.markNow()
            val outcome = runCatching { free.instantCreate(ResolvedFile(path = "probe-dead.mkv", size = size, gcid = deadGcid), parentId = "") }
            log("instantCreate(${mib(size)}): ${mark.elapsedNow().inWholeMilliseconds} ms -> ${outcome.fold({ "created $it (unexpected)" }, { describe(it) })}")
            outcome.onSuccess { runCatching { free.deleteFile(it) } }
            var after = before
            val settle = TimeSource.Monotonic.markNow()
            while (after == before && settle.elapsedNow() < 30.seconds) {
                delay(3.seconds)
                after = free.getTransferQuota().account.upload.usedBytes
            }
            log("upload ${mib(before)} -> ${mib(after)} (15 % would be ${mib(size * 15 / 100)})")
            log(if (after == before) "VERDICT: a failed create costs nothing" else "VERDICT: a failed create IS charged")
            val leftovers = free.listFiles(parentId = "").filter { it.name.startsWith("probe-dead") }
            log("pending nodes left in root: ${leftovers.size}")

            // 2 --- can a reference be checked without restoring it?
            log("")
            log("--- 2: gcidByCid as a liveness check ---")
            val source = findVideo(premium) ?: run {
                log("no video in the premium drive within the walk budget")
                return@runBlocking
            }
            val link = premium.getFile(source.id).downloadUrl!!
            val cid = XunleiCid.of(source.sizeBytes) { offset, length -> readRange(http, link, offset, length) }
            log("source ${mib(source.sizeBytes)}, gcid ${source.hash.take(8)}…, cid ${cid.take(8)}…")
            val live = free.gcidByCid(cid, source.sizeBytes)
            log("free account, real cid: ${live?.take(8)}… match=${live == source.hash.uppercase()}")
            log("free account, random cid: ${free.gcidByCid(randomHex(40), source.sizeBytes)}")
            log("free account, real cid, size+1: ${free.gcidByCid(cid, source.sizeBytes + 1)}")

            // A gcid-keyed lookup would spare the CID altogether; the parameter name is a guess.
            val byGcid = runCatching {
                free.http.request(
                    method = HttpMethod.Get,
                    url = "${PikPakConstants.DRIVE_BASE}/drive/v1/resource/cid?gcid=${source.hash}&file_size=${source.sizeBytes}",
                    captchaAction = "GET:/drive/v1/resource/cid",
                ).toString()
            }
            log("resource/cid?gcid=: ${byGcid.fold({ it.take(200) }, { describe(it) })}")
        } finally {
            premium.close()
            free.close()
            File("build").mkdirs()
            File("build/free-account-probe-dead.txt").writeText(report.toString())
        }
    }

    private suspend fun findVideo(client: PikPakClient): FileStat? {
        val queue = ArrayDeque(listOf(""))
        var folders = 0
        while (queue.isNotEmpty() && folders++ < WALK_DETAILS) {
            for (f in client.listFiles(parentId = queue.removeFirst())) {
                if (f.kind.endsWith("folder")) queue.addLast(f.id)
                else if (VIDEO_EXTENSIONS.any { f.name.endsWith(it, ignoreCase = true) } && f.sizeBytes > MIN_TARGET && f.hash.isNotBlank()) return f
            }
        }
        return null
    }

    private fun readRange(http: HttpClient, url: String, offset: Long, length: Int): ByteArray {
        val request = HttpRequest.newBuilder(URI(url))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .timeout(java.time.Duration.ofSeconds(60))
            .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body()
        check(body.size == length) { "range $offset+$length returned ${body.size} bytes" }
        return body
    }

    private fun randomHex(length: Int): String =
        (1..length).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("")

    /** Breadth-first over the drive, at most [WALK_DETAILS] file details, for a video with more than one media. */
    private suspend fun findTranscoded(client: PikPakClient): FileDetail? {
        val queue = ArrayDeque(listOf(""))
        var details = 0
        while (queue.isNotEmpty() && details < WALK_DETAILS) {
            for (f in client.listFiles(parentId = queue.removeFirst())) {
                if (f.kind.endsWith("folder")) {
                    queue.addLast(f.id)
                    continue
                }
                val size = f.size.toLongOrNull() ?: 0L
                if (VIDEO_EXTENSIONS.none { f.name.endsWith(it, ignoreCase = true) } || size !in MIN_TARGET..MAX_TARGET) continue
                if (details++ >= WALK_DETAILS) return null
                val detail = client.getFile(f.id)
                if (detail.medias.count { !it.isOrigin } > 0 && detail.hash.isNotBlank()) return detail
            }
        }
        return null
    }

    private fun logMedias(http: HttpClient, medias: List<MediaVariant>) {
        if (medias.isEmpty()) log("  medias: none")
        for (m in medias) {
            val read = m.url?.let { readHead(http, it) } ?: "no url"
            log("  ${m.mediaName} / ${m.resolutionName} origin=${m.isOrigin} default=${m.isDefault} visible=${m.isVisible}: $read")
        }
    }

    private suspend fun rawAbout(client: PikPakClient): JsonObject =
        client.http.request(
            method = HttpMethod.Get,
            url = "${PikPakConstants.DRIVE_BASE}/drive/v1/about",
            captchaAction = "GET:/drive/v1/about",
        ).jsonObject

    private fun cloudDownloadUsage(about: JsonObject): String? =
        (about["quotas"] as? JsonObject)?.get("cloud_download")?.jsonObject?.get("usage")?.jsonPrimitive?.content

    /**
     * The smallest video of the first indexed pack that fits between a few MiB
     * and [MAX_TARGET]: large enough that PikPak has transcoded it, small
     * enough that the upload charge and the 6 GB drive do not matter.
     */
    private suspend fun pickTarget(client: PikPakClient, freeBytes: Long): Pair<String, ResolvedFile>? {
        for ((label, magnet) in SAMPLE_PACKS) {
            val resource = runCatching { client.resolveMagnet(magnet) }.getOrNull()
            if (resource == null) {
                log("not indexed, skipping: $label")
                continue
            }
            val target = resource.files
                .filter { f -> f.gcid != null && VIDEO_EXTENSIONS.any { f.name.endsWith(it, ignoreCase = true) } }
                .filter { it.size in MIN_TARGET..minOf(MAX_TARGET, freeBytes) }
                .minByOrNull { it.size }
            if (target != null) return label to target
            log("no video within bounds, skipping: $label")
        }
        return null
    }

    /** Reads the first [bytes] of [url]: status, bytes and rate, or why it failed. */
    private fun readHead(http: HttpClient, url: String, bytes: Int = HEAD_BYTES): String {
        val request = HttpRequest.newBuilder(URI(url))
            .header("Range", "bytes=0-${bytes - 1}")
            .timeout(java.time.Duration.ofSeconds(120))
            .build()
        val mark = TimeSource.Monotonic.markNow()
        return runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val ms = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
            val bytes = response.body().size
            val body = if (response.statusCode() >= 400) " body=${String(response.body()).take(200)}" else ""
            "http ${response.statusCode()}, ${bytes} B in $ms ms (${bytes / ms} KB/s)$body"
        }.getOrElse { "${it::class.simpleName}: ${it.message}" }
    }

    private fun logTransfer(prefix: String, a: TransferAllowances) {
        log(
            "$prefix: daily ${mib(a.downloadDaily.usedBytes)}/${mib(a.downloadDaily.limitBytes)}, " +
                    "download ${mib(a.download.usedBytes)}, upload ${mib(a.upload.usedBytes)}, offline ${mib(a.offline.usedBytes)}",
        )
    }

    private fun describe(t: Throwable): String = when (t) {
        is PikPakException -> "${t::class.simpleName} code=${t.errorCode} http=${t.httpStatus} ${t.message} ${t.rawBody?.take(300)}"
        else -> "${t::class.simpleName}: ${t.message}"
    }

    private fun mib(bytes: Long): String = "%.1f MiB".format(bytes / 1024.0 / 1024.0)

    private companion object {
        val ALL_PHASES = listOf(TaskPhase.PENDING, TaskPhase.RUNNING, TaskPhase.COMPLETE, TaskPhase.ERROR).joinToString(",")
        val SETTLE = 5.seconds
        const val HEAD_BYTES = 1024 * 1024
        const val WALK_DETAILS = 40
        const val SUSTAINED_BYTES = 32 * 1024 * 1024
        const val MIN_TARGET = 30L * 1024 * 1024
        const val MAX_TARGET = 600L * 1024 * 1024
        val VIDEO_EXTENSIONS = listOf(".mkv", ".mp4")

        val SAMPLE_PACKS = listOf(
            "[Judas] City The Animation S01, 6.4 GiB" to
                    "magnet:?xt=urn:btih:365333205bd36ca419d9247c009f885ab435a1b3",
            "[VCB-Studio] VIRGIN PUNK Clockwork Girl, 9.5 GiB" to
                    "magnet:?xt=urn:btih:04bcd87b4c1d3874cf104d9f5b3cabddb5d7514d",
            "[VCB-Studio] Cyberpunk Edgerunners, 23.5 GiB" to
                    "magnet:?xt=urn:btih:7af771b417c55ebc86caa0cb82cdb7faac90c04c",
        )
    }
}
