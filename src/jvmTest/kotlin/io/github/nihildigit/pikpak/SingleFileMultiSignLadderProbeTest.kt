package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Assumptions
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SingleFileMultiSignLadderProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(4).joinToString("") { "%02x".format(it) }

    private fun signOf(url: String): String =
        URI(url).rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("sign=") }?.removePrefix("sign=").orEmpty()

    private fun rewriteHost(url: String, newHost: String): String {
        val oldHost = URI(url).host
        return url.replaceFirst("://$oldHost", "://$newHost")
    }

    @Test
    fun `probe single file multi-sign concurrency ladder up to 32`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        System.setProperty("java.net.useSystemProxies", "true")

        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
        )

        val okHttp = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(64, 2, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 64
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        try {
            client.login()

            var fid: String? = null
            var fileSize = 0L
            val minted = mutableListOf<String>()
            var pageToken = ""
            for (page in 0 until 5) {
                val res = client.listFilesPaged(parentId = "*", pageSize = 100, pageToken = pageToken)
                val candidates = res.files.filter { !it.isFolder && !it.trashed && it.sizeBytes > 300L * 1024 * 1024 }
                for (cand in candidates.take(15)) {
                    val d = runCatching { client.getFile(cand.id) }.getOrNull() ?: continue
                    val u = d.webContentLink.takeIf { it.isNotBlank() } ?: continue
                    if (URI(u).host.orEmpty().startsWith("dl-z01a-")) {
                        fid = cand.id
                        fileSize = d.sizeBytes
                        minted += u
                        break
                    }
                }
                if (fid != null || res.nextPageToken.isBlank()) break
                pageToken = res.nextPageToken
            }
            val targetFid = requireNotNull(fid) { "No >300 MiB dl-z01a file found" }

            // Mint until we have 4 distinct signatures (spacing 1.5s)
            var attempts = 0
            while (minted.map(::signOf).distinct().size < 4 && attempts < 8) {
                attempts++
                delay(1500.milliseconds)
                val u = client.getFile(targetFid).webContentLink
                if (u.isNotBlank() && signOf(u) !in minted.map(::signOf)) {
                    minted += u
                }
            }

            println("[ladder] fileId=$targetFid, size=$fileSize, distinctSigns=${minted.size}")
            val rootDomain = URI(minted.first()).host.substringAfter('.')
            val spreadHosts = listOf("dl-z01a-0041", "dl-z01a-0043", "dl-z01a-0047", "dl-z01a-0048")
                .map { "$it.$rootDomain" }

            minted.forEachIndexed { idx, u ->
                println("[ladder] url[$idx] origHost=${URI(u).host}, signHash=${shortHash(signOf(u))}")
            }
            check(minted.size >= 4) { "Could only mint ${minted.size} distinct signs" }

            val spreadUrls = minted.take(4).mapIndexed { idx, u -> rewriteHost(u, spreadHosts[idx]) }

            suspend fun runRung(label: String, plan: List<Pair<String, Int>>, holdMs: Long = 3500L) {
                val totalConns = plan.sumOf { it.second }
                val stride = fileSize / (totalConns + 2)
                val started = TimeSource.Monotonic.markNow()
                var globalIdx = 0
                val outcomes = coroutineScope {
                    plan.flatMap { (u, count) ->
                        (0 until count).map {
                            val myIdx = globalIdx++
                            val offset = (myIdx + 1) * stride
                            async(Dispatchers.IO) {
                                val req = Request.Builder()
                                    .url(u)
                                    .header("User-Agent", PikPakConstants.USER_AGENT)
                                    .header("Range", "bytes=$offset-${offset + 8L * 1024 * 1024 - 1}")
                                    .build()
                                runCatching {
                                    okHttp.newCall(req).execute().use { resp ->
                                        val code = resp.code
                                        var bytes = 0L
                                        if (code == 206) {
                                            val src = resp.body.source()
                                            val buf = ByteArray(32 * 1024)
                                            while (started.elapsedNow().inWholeMilliseconds < holdMs) {
                                                val n = src.read(buf)
                                                if (n <= 0) break
                                                bytes += n
                                            }
                                        }
                                        Triple(code.toString(), bytes, null as String?)
                                    }
                                }.getOrElse { e ->
                                    Triple("ERR:${e.javaClass.simpleName}", 0L, e.message?.take(40))
                                }
                            }
                        }
                    }.awaitAll()
                }
                val elapsedSec = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000.0
                val byStatus = outcomes.groupingBy { it.first }.eachCount()
                val activeDelivering = outcomes.count { it.first == "206" && it.second > 0L }
                val totalBytes = outcomes.sumOf { it.second }
                println(
                    "[ladder][$label] requested=$totalConns, status=$byStatus, delivering206=$activeDelivering, " +
                        "bytes=${totalBytes / 1024}KiB, aggRate=${"%.2f".format(totalBytes / 1048576.0 / elapsedSec)} MiB/s"
                )
            }

            // Rung 1: 16 (2 x 8) spread across 2 hosts
            runRung("16 = 2x8 (spread)", listOf(spreadUrls[0] to 8, spreadUrls[1] to 8))
            delay(3.seconds)

            // Rung 2: 20 (8 + 8 + 4) spread across 3 hosts
            runRung("20 = 8+8+4 (spread)", listOf(spreadUrls[0] to 8, spreadUrls[1] to 8, spreadUrls[2] to 4))
            delay(3.seconds)

            // Rung 3: 24 (3 x 8) spread across 3 hosts
            runRung("24 = 3x8 (spread)", listOf(spreadUrls[0] to 8, spreadUrls[1] to 8, spreadUrls[2] to 8))
            delay(3.seconds)

            // Rung 4: 32 (4 x 8) spread across 4 hosts
            runRung("32 = 4x8 (spread)", listOf(spreadUrls[0] to 8, spreadUrls[1] to 8, spreadUrls[2] to 8, spreadUrls[3] to 8))
            delay(3.seconds)

            // Rung 5: 32 (4 x 8) ALL ON ONE SINGLE HOST (e.g. dl-z01a-0047) to test per-host vs per-account vs per-sign!
            val singleHostUrls = minted.take(4).map { rewriteHost(it, spreadHosts[2]) }
            runRung("32 = 4x8 (SAME-HOST 0047)", singleHostUrls.map { it to 8 })
        } finally {
            client.close()
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
        }
    }
}
