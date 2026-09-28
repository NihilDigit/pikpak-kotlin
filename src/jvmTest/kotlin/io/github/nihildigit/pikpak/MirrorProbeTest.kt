package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.Test

/**
 * Checks the forum claims about PikPak hosts before anything is built on them:
 *
 *  - hosts: whether a signed link still works with its host swapped for another dl-* edge, and
 *    what each edge delivers over one connection;
 *  - api: whether the community IP list serves the API host and the user host, and how its
 *    latency compares with what DNS gives;
 *  - roots: whether api-drive and user on the other three root domains answer the same account
 *    with the same token.
 *
 * Runs on the desktop app's session in ~/.piko rather than .env, because that account holds large
 * files to read and signing in again would log the app out. Token rotation is written back the
 * way the app writes it. The password is never read: a dead session fails the probe instead.
 *
 * On a machine behind a fake-ip TUN proxy every connection is captured and re-dialled by the
 * proxy, which also sniffs SNI and replaces the destination, so a pinned IP is silently ignored.
 * `PIKPAK_PROBE_BIND=<address of the physical interface>` binds every socket to that interface
 * and resolves through DoH, which is the only way to see the direct route from such a machine.
 *
 * Read-only: one listing walk, file details and ranged reads, a few hundred MB in total.
 * Local only. Opt in with `PIKPAK_MIRROR_PROBE=1`; `PIKPAK_MIRROR_PROBE_PHASES` picks from
 * `hosts,api,roots`, `PIKPAK_MIRROR_PROBE_FILE` fixes the file.
 */
class MirrorProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val enabled = env["PIKPAK_MIRROR_PROBE"] == "1"
    private val bind = env["PIKPAK_PROBE_BIND"]?.takeIf { it.isNotBlank() }
    private val phases = (env["PIKPAK_MIRROR_PROBE_PHASES"] ?: "hosts,api,roots").split(',').map(String::trim).toSet()
    private val fixedFile = env["PIKPAK_MIRROR_PROBE_FILE"]?.takeIf { it.isNotBlank() }
    private val pikoDir = File(System.getProperty("user.home"), ".piko")

    // Appended as it goes: the host phase runs for many minutes and Gradle holds test output back.
    private val reportFile = File("build/mirror-probe-report.txt").apply { parentFile.mkdirs(); writeText("") }

    private fun log(line: String) {
        println("[mirror] $line")
        reportFile.appendText(line + "\n")
    }

    @Test
    fun `probe dl host swapping api mirrors and root domains`() = runBlocking {
        Assumptions.assumeTrue(enabled, "PIKPAK_MIRROR_PROBE != 1")
        val accountFile = File(pikoDir, "pikpak-account.txt")
        Assumptions.assumeTrue(accountFile.isFile, "no ~/.piko session")

        val sdk = PikPakClient(
            account = accountFile.readText().trim(),
            passwordSupplier = { error("probe never signs in with the password") },
            sessionStore = PikoDirSessionStore(pikoDir),
        )
        try {
            sdk.getQuota()
            log("path: ${bind?.let { "bound to $it, DoH resolution" } ?: "system route and DNS"}")
            if ("hosts" in phases) probeHosts(sdk)
            if ("api" in phases) probeApiMirrors(sdk)
            if ("roots" in phases) probeRoots(sdk)
            if ("probe" in phases) probeAllDomains(sdk)
            if ("steer" in phases) probeSteering(sdk)
            // Last: its refresh rotates the token under the clients above
            if ("domain" in phases) probeDomain(sdk)
        } finally {
            sdk.close()
            println("[mirror] report written to ${reportFile.absolutePath}")
        }
    }

    // ---- hosts ---------------------------------------------------------------------------------

    private suspend fun probeHosts(sdk: PikPakClient) {
        val fileId = fixedFile ?: findLargeFile(sdk)
        // Every mint picks a host anew; a dozen shows which hosts PikPak actually hands this file.
        val minted = (0 until MINTS).map { sdk.getFile(fileId) }
        val size = minted.first().sizeBytes
        val links = minted.map { d -> d.webContentLink.ifEmpty { d.medias.firstNotNullOf { it.url } }.toHttpUrl() }
        log("== hosts: file $fileId, ${size shr 20} MiB")
        log("   $MINTS mints: ${links.groupingBy { it.host.substringBefore('.') }.eachCount()}")
        log("   query keys: ${links.first().queryParameterNames.sorted()}")

        val probeOffset = size / 3 / 4096 * 4096
        var offset = size / 2 / 4096 * 4096
        val families = links.distinctBy { it.host.substring(0, 7) }
        for (original in families) {
            val label = original.host.substringBefore('.')
            val reference = timedRead(original.toString(), probeOffset, PROBE_LENGTH)
            log("-- link on $label: reference ${reference.status} ${reference.bytes} B sha ${reference.sha} ttfb ${reference.ttfbMs}ms")

            // The signature has to be checked at all, or a working swap proves nothing about binding.
            val tampered = original.newBuilder().setQueryParameter("sign", "0" + original.queryParameter("sign").orEmpty().drop(1)).build()
            log("   tampered sign: ${timedRead(tampered.toString(), probeOffset, 4096).status}")

            // Other hosts under the same signature, 64 KiB each, bytes compared with the reference.
            val working = mutableListOf(original.host)
            for (host in DL_HOSTS - original.host) {
                val r = runCatching { timedRead(original.newBuilder().host(host).build().toString(), probeOffset, PROBE_LENGTH) }
                val line = r.fold(
                    { "${it.status} ${if (it.status == 206) (if (it.sha == reference.sha) "same bytes" else "OTHER bytes ${it.sha}") else ""} ttfb ${it.ttfbMs}ms" },
                    { "${it::class.simpleName} ${it.message?.take(70)}" },
                )
                log("   swap to ${host.substringBefore('.')}: $line")
                if (r.getOrNull()?.let { it.status == 206 && it.sha == reference.sha } == true) working += host
            }
            log("   ${working.size - 1}/${DL_HOSTS.size - 1} other hosts served the same bytes on this link")

            // The root domain alone: same edge, same addresses, only the name in DNS, SNI and Host.
            for (round in 0 until 2) {
                val line = ROOTS.joinToString("  ") { root ->
                    val r = runCatching { timedRead(original.newBuilder().host("$label.$root").build().toString(), offset, RATE_LENGTH) }
                    offset += RATE_LENGTH
                    "$root ${r.fold({ if (it.status == 206) "%.2f".format(it.rate) else it.status.toString() }, { it::class.simpleName })}"
                }
                log("   root swap round $round (MB/s): $line")
            }

            // One connection per host, capped in time; two passes in opposite order so drift averages out.
            val rates = mutableMapOf<String, MutableList<Double>>()
            for (pass in 0 until 2) {
                for (host in if (pass == 0) working else working.reversed()) {
                    if (offset + RATE_LENGTH > size) offset = 0
                    val r = runCatching { timedRead(original.newBuilder().host(host).build().toString(), offset, RATE_LENGTH) }.getOrNull()
                    offset += RATE_LENGTH
                    if (r != null && r.status == 206) rates.getOrPut(host) { mutableListOf() } += r.rate
                }
            }
            val ranked = rates.entries.sortedByDescending { it.value.average() }
            for ((host, samples) in ranked) {
                val assigned = if (links.any { it.host == host }) " (assigned by a mint)" else ""
                log("   one conn ${host.substringBefore('.')}: ${samples.joinToString(" / ") { "%.2f".format(it) }} MB/s$assigned")
            }
            if (ranked.size < 2) continue

            // Eight connections, the SDK's per-file budget, on the best and the worst host.
            for (host in listOf(ranked.first().key, ranked.last().key)) {
                val candidate = original.newBuilder().host(host).build().toString()
                val started = System.nanoTime()
                val parts = coroutineScope {
                    (0 until 8).map { i ->
                        async(Dispatchers.IO) { runCatching { timedRead(candidate, offset + i * RATE_LENGTH, RATE_LENGTH) }.getOrNull() }
                    }.awaitAll()
                }
                offset += 8 * RATE_LENGTH
                val seconds = (System.nanoTime() - started) / 1e9
                log("   8 conns ${host.substringBefore('.')}: %.2f MB/s".format(parts.sumOf { it?.bytes ?: 0 } / 1048576.0 / seconds))
            }
        }
    }

    // ---- domain --------------------------------------------------------------------------------

    /**
     * PikPakDomain end to end on one alternative root: the API calls, a captcha refresh and a
     * refresh-token exchange, which rotates the token and writes it back to ~/.piko.
     */
    private suspend fun probeDomain(sdk: PikPakClient) {
        val client = PikPakClient(
            account = sdk.account,
            passwordSupplier = { error("probe never signs in with the password") },
            sessionStore = PikoDirSessionStore(pikoDir),
            domain = PikPakDomain.MYPIKPAK_NET,
        )
        try {
            log("== domain ${client.domain.root}")
            log("   quota usage ${client.getQuota().quota.usage}")
            log("   profile same user: ${client.getUserProfile().sub == sdk.currentSession?.sub}")
            val fileId = fixedFile ?: findLargeFile(client)
            log("   detail link host ${client.getFile(fileId).webContentLink.toHttpUrl().host}")
            val before = client.currentSession!!
            client.mutex.withLock { client.auth.reauthenticateLocked(before) }
            val after = client.currentSession!!
            log("   refresh: token rotated ${after.accessToken != before.accessToken}, same user ${after.sub == before.sub}")
            client.auth.refreshCaptchaToken("GET:/drive/v1/about", client.state.captchaToken)
            log("   captcha refreshed: ${client.state.captchaToken.isNotEmpty()}")
            log("   quota after refresh ${client.getQuota().quota.usage}")
        } finally {
            client.close()
        }
    }

    /** The SDK's own probeDomain on every root, twice in alternating order, as piko would call it. */
    private suspend fun probeAllDomains(sdk: PikPakClient) {
        log("== probeDomain")
        for (round in 0 until 2) {
            val order = if (round == 0) PikPakDomain.entries else PikPakDomain.entries.reversed()
            for (domain in order) {
                val p = sdk.probeDomain(domain)
                log("   ${domain.root}: usable ${p.usable} first ${p.firstRequest} warm ${p.warmRequest} ${p.failure.orEmpty()}")
            }
        }
    }

    // ---- steer ---------------------------------------------------------------------------------

    /**
     * The SDK's own steering, end to end: a link pinned to `PIKPAK_MIRROR_PROBE_SLOW_HOST` (a
     * host the hosts phase found slow), read in background priority by a RangeReader, once with
     * steering off and once on. Off stays on the slow host; on should explore siblings and move.
     */
    private suspend fun probeSteering(sdk: PikPakClient) {
        val slowHost = env["PIKPAK_MIRROR_PROBE_SLOW_HOST"] ?: error("set PIKPAK_MIRROR_PROBE_SLOW_HOST")
        val fileId = fixedFile ?: findLargeFile(sdk)
        val detail = sdk.getFile(fileId)
        val minted = detail.webContentLink.toHttpUrl()
        val pinned = minted.newBuilder().host("$slowHost.${minted.host.substringAfter('.')}").build().toString()
        log("== steer: link pinned to $slowHost, file ${detail.sizeBytes shr 20} MiB")
        for (steer in listOf(false, true)) {
            val client = PikPakClient(
                account = sdk.account,
                passwordSupplier = { error("probe never signs in with the password") },
                sessionStore = PikoDirSessionStore(pikoDir),
                steerEdgeHosts = steer,
            )
            val hosts = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val reader = RangeReader(client, { pinned }, onAttempt = { a -> a.host?.let { hosts.merge(it.substringBefore('.'), a.delivered, Long::plus) } })
            val started = System.nanoTime()
            var read = 0L
            var offset = (if (steer) detail.sizeBytes / 2 else detail.sizeBytes / 4) / 4096 * 4096
            val block = 1L shl 20
            kotlinx.coroutines.withTimeoutOrNull(STEER_WINDOW_MS) {
                while (read < STEER_BYTES) {
                    val parts = coroutineScope { (0 until 8).map { i -> async { reader.readBytes(offset + i * block, block, priority = 0).size } }.awaitAll() }
                    read += parts.sum()
                    offset += 8 * block
                }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            log("   steering ${if (steer) "on " else "off"}: ${read shr 20} MiB in %.1f s = %.2f MB/s, bytes by host %s".format(seconds, read / 1048576.0 / seconds, hosts.mapValues { it.value shr 10 }))
            client.close()
        }
    }

    /** Largest file within two levels of the root; listings only, nothing is opened. */
    private suspend fun findLargeFile(sdk: PikPakClient): String {
        val root = sdk.listFiles("")
        val candidates = root.filter { it.kind == FileKind.FILE }.toMutableList()
        for (folder in root.filter { it.kind == FileKind.FOLDER }.take(20)) {
            candidates += sdk.listFiles(folder.id).filter { it.kind == FileKind.FILE }
        }
        return candidates.filter { it.sizeBytes > 400L shl 20 }.maxByOrNull { it.sizeBytes }?.id
            ?: error("no file over 400 MiB within two levels; set PIKPAK_MIRROR_PROBE_FILE")
    }

    // ---- api -----------------------------------------------------------------------------------

    private suspend fun probeApiMirrors(sdk: PikPakClient) {
        sdk.getUserProfile()
        log("== api mirrors")
        val targets = listOf(
            "api-drive.mypikpak.com" to "/drive/v1/about",
            "user.mypikpak.com" to "/v1/user/me",
        )
        for ((host, path) in targets) {
            val resolved = runCatching { resolve(host) }.getOrElse { emptyList() }
            log("   $host resolves to ${resolved.joinToString { it.hostAddress }}")
            val candidates = resolved.map { it.hostAddress to "dns" } + API_IPS.map { it to "list" }
            for ((ip, origin) in candidates.distinctBy { it.first }) {
                val samples = (0 until 5).map { runCatching { authedGet(sdk, "https://$host$path", pin = ip) } }
                val ok = samples.mapNotNull { it.getOrNull() }
                val statuses = samples.joinToString(" ") { s -> s.fold({ it.status.toString() }, { "x" }) }
                if (ok.isEmpty()) {
                    log("   $host @ $ip ($origin): ${samples.first().exceptionOrNull()?.let { "${it::class.simpleName} ${it.message?.take(60)}" }}")
                    continue
                }
                val tls = ok.map { it.connectMs + it.tlsMs }.sorted()
                val total = ok.map { it.totalMs }.sorted()
                val same = ok.first().bodyKey
                log(
                    "   $host @ $ip ($origin): [$statuses] connect ${ok.map { it.connectMs }.sorted()[ok.size / 2]}ms " +
                        "connect+tls ${tls[tls.size / 2]}ms total ${total[total.size / 2]}ms body $same",
                )
            }
        }
    }

    // ---- roots ---------------------------------------------------------------------------------

    private suspend fun probeRoots(sdk: PikPakClient) {
        log("== root domains")
        val fileId = fixedFile ?: findLargeFile(sdk)
        for (root in ROOTS) {
            for ((sub, path) in listOf("api-drive" to "/drive/v1/about", "user" to "/v1/user/me", "api-drive" to "/drive/v1/files/$fileId")) {
                val host = "$sub.$root"
                val results = (0 until 3).map { runCatching { authedGet(sdk, "https://$host$path") } }
                val ok = results.mapNotNull { it.getOrNull() }
                val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                val summary = ok.firstOrNull()?.let { r ->
                    "${ok.map { it.status }} total ${ok.map { it.totalMs }.sorted()[ok.size / 2]}ms body ${r.bodyKey}"
                } ?: "${failure?.let { it::class.simpleName }} ${failure?.message?.take(80)}"
                log("   $host${path.substringBeforeLast('/').takeIf { path.startsWith("/drive/v1/files") }?.let { " files/{id}" } ?: path}: $summary")
            }
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private class Read(val status: Int, val bytes: Long, val sha: String, val connectMs: Long, val ttfbMs: Long, val bodyMs: Double) {
        val rate: Double get() = if (bodyMs <= 0) 0.0 else bytes / 1048576.0 / (bodyMs / 1000)
    }

    private class Answer(val status: Int, val connectMs: Long, val tlsMs: Long, val totalMs: Long, val bodyKey: String)

    private class Timings : EventListener() {
        var connectStart = 0L
        var connectEnd = 0L
        var tlsStart = 0L
        var tlsEnd = 0L
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connectStart = System.nanoTime() }
        override fun secureConnectStart(call: Call) { tlsStart = System.nanoTime() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { tlsEnd = System.nanoTime() }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connectEnd = System.nanoTime() }
        val connectMs: Long get() = if (tlsStart > connectStart && connectStart > 0) (tlsStart - connectStart) / 1_000_000 else 0
        val tlsMs: Long get() = if (tlsEnd > tlsStart && tlsStart > 0) (tlsEnd - tlsStart) / 1_000_000 else 0
    }

    /** A fresh client per call so every sample pays, and shows, its own handshake. */
    private fun freshClient(timings: Timings, pin: String? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .eventListener(timings)
            .dns { host -> pin?.let { listOf(InetAddress.getByName(it)) } ?: resolve(host) }
        bind?.let { address -> builder.socketFactory(BoundSocketFactory(InetAddress.getByName(address))) }
        return builder.build()
    }

    /**
     * A ranged read that stops after [READ_CAP] of body, so a host at 50 KB/s costs seconds and
     * not minutes; the rate is what arrived in that time. The hash covers what arrived, which is
     * the whole range for the 64 KiB comparison reads.
     */
    private fun timedRead(url: String, start: Long, length: Long): Read {
        val timings = Timings()
        val client = freshClient(timings)
        val request = Request.Builder().url(url)
            .header("User-Agent", PikPakConstants.USER_AGENT)
            .header("Range", "bytes=$start-${start + length - 1}")
            .build()
        val began = System.nanoTime()
        client.newCall(request).execute().use { response ->
            val headersAt = System.nanoTime()
            val stopAt = headersAt + READ_CAP_NANOS
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            response.body.byteStream().use { stream ->
                while (System.nanoTime() < stopAt) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
            val body = out.toByteArray()
            val done = System.nanoTime()
            val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }.take(12)
            return Read(response.code, body.size.toLong(), sha, timings.connectMs, (headersAt - began) / 1_000_000, (done - headersAt) / 1e6)
        }
    }

    /**
     * The request the SDK would send, with the session's own headers. The body is reduced to a
     * key that says whether two hosts answered for the same account, never printed whole.
     */
    private fun authedGet(sdk: PikPakClient, url: String, pin: String? = null): Answer {
        val session = sdk.currentSession ?: error("no session")
        val timings = Timings()
        val client = freshClient(timings, pin)
        val request = Request.Builder().url(url)
            .header("User-Agent", PikPakConstants.USER_AGENT)
            .header("X-Device-Id", sdk.deviceId)
            .header("Authorization", "Bearer ${session.accessToken}")
            .apply { sdk.state.captchaToken.takeIf { it.isNotEmpty() }?.let { header("X-Captcha-Token", it) } }
            .build()
        val began = System.nanoTime()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val total = (System.nanoTime() - began) / 1_000_000
            return Answer(response.code, timings.connectMs, timings.tlsMs, total, bodyKey(text, session.sub))
        }
    }

    private fun bodyKey(text: String, userId: String): String {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return "non-json(${text.length})"
        obj["error"]?.let { return "error=${it.jsonPrimitive.content}" }
        obj["quota"]?.let { q -> return "quota.usage=${q.jsonObject["usage"]?.jsonPrimitive?.content}" }
        obj["sub"]?.let { return if (it.jsonPrimitive.content == userId) "same user" else "other user" }
        obj["web_content_link"]?.let { link ->
            val host = link.jsonPrimitive.content.substringAfter("://").substringBefore('/')
            val media = (obj["medias"]?.jsonArray ?: emptyList()).mapNotNull {
                (it as? JsonObject)?.get("link")?.jsonObject?.get("url")?.jsonPrimitive?.content?.substringAfter("://")?.substringBefore('/')
            }
            return "link host $host, media hosts $media"
        }
        return "keys=${obj.keys.take(6)}"
    }

    /** System DNS unless bound; a bound socket bypasses the TUN, whose fake-ip answers would then lead nowhere. */
    private fun resolve(host: String): List<InetAddress> {
        if (bind == null) return Dns.SYSTEM.lookup(host)
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url("https://dns.alidns.com/resolve?name=$host&type=A").build()
        // One retry: a single dropped DoH query otherwise reads as a host that does not exist.
        val text = runCatching { client.newCall(request).execute().use { it.body.string() } }
            .getOrElse { client.newCall(request).execute().use { it.body.string() } }
        val answers = Json.parseToJsonElement(text).jsonObject["Answer"]?.jsonArray ?: return emptyList()
        return answers.mapNotNull { it.jsonObject["data"]?.jsonPrimitive?.content }
            .filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
            .map { InetAddress.getByName(it) }
    }

    private class BoundSocketFactory(private val local: InetAddress) : SocketFactory() {
        override fun createSocket(): Socket = Socket().apply { bind(InetSocketAddress(local, 0)) }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createSocket(address, port)
    }

    /** Same file and same atomic replace as the desktop app's store, so a rotated token reaches the app. */
    private class PikoDirSessionStore(root: File) : SessionStore {
        private val json = Json { ignoreUnknownKeys = true }
        private val file = File(root, "pikpak-session.json")

        override suspend fun load(account: String): Session? =
            file.takeIf { it.isFile }?.let { json.decodeFromString(Session.serializer(), it.readText()) }

        override suspend fun save(account: String, session: Session) {
            val staging = File(file.parentFile, "${file.name}.tmp")
            staging.writeText(json.encodeToString(Session.serializer(), session))
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        override suspend fun clear(account: String) = Unit
    }

    private companion object {
        const val MINTS = 12
        const val PROBE_LENGTH = 64 * 1024L
        // Bounds the download: on a fast route every rate sample is this much, and there are about 90.
        const val RATE_LENGTH = 3L shl 20
        const val READ_CAP_NANOS = 6_000_000_000L
        const val STEER_BYTES = 96L shl 20
        const val STEER_WINDOW_MS = 60_000L

        val ROOTS = listOf("mypikpak.com", "mypikpak.net", "pikpak.me", "pikpakdrive.com")

        val API_IPS = listOf(
            "8.222.208.40", "8.210.96.68", "8.209.208.12", "8.209.248.151", "149.129.129.1", "149.129.132.58",
            "198.11.172.147", "47.88.28.176", "43.160.170.231", "43.156.21.88", "43.160.168.77", "43.159.52.123",
        )

        val DL_HOSTS: List<String> = buildList {
            listOf(
                621, 622, 624, 625, 822, 835, 858, 859, 860, 861, 862, 863, 868, 869, 1193, 1194, 1195, 1196,
                1531, 1532, 1533, 1542, 1543, 1551, 1552, 1553, 1554, 1555, 1556, 1557, 1558,
            ).forEach { add("dl-a10b-%04d.mypikpak.com".format(it)) }
            (listOf(14, 15, 16, 26, 27) + (41..55) + (59..64)).forEach { add("dl-z01a-%04d.mypikpak.com".format(it)) }
        }
    }
}
