package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * How PikPak hands out CDN hosts, and what a host's speed is made of.
 *
 * Signed links for one file land on a different edge host each time they are
 * minted, and some hosts serve a file at a fraction of what others do. Before
 * building on that — minting a fresh link to escape a slow host — this answers:
 *
 *  - whether assignment is random per mint, rotating, or sticky over time;
 *  - each host's RTT, taken as the TCP connect time, and its addresses;
 *  - whether a slow host is slow for everything or only cold: the same range
 *    read twice on one link, first byte of each;
 *  - whether one connection's rate follows RTT, which is what makes parallel
 *    connections pay: one connection alone against eight on one link.
 *
 * Read-only against files already in the account. Opt in with
 * `PIKPAK_HOST_PROBE=1` and `PIKPAK_HOST_PROBE_FILES=<id,id,…>`.
 */
class HostAssignmentProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = env["PIKPAK_HOST_PROBE"] == "1"
    private val fileIds = env["PIKPAK_HOST_PROBE_FILES"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    private val mints = env["PIKPAK_HOST_PROBE_MINTS"]?.toIntOrNull() ?: 6
    private val variants = (env["PIKPAK_HOST_PROBE_VARIANTS"] ?: "720P,Original").split(',').map(String::trim).toSet()

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[hosts] $line")
        report.append(line).append('\n')
    }

    @Test
    fun `probe how cdn hosts are assigned and what their speed is made of`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        Assumptions.assumeTrue(enabled && fileIds.isNotEmpty(), "PIKPAK_HOST_PROBE != 1 or no files")

        val sdk = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            sdk.login()
            for (fileId in fileIds) {
                log("== file $fileId")
                val links = mutableListOf<Pair<String, String>>()
                repeat(mints) { i ->
                    val detail = sdk.getFile(fileId)
                    log("mint $i: " + detail.medias.joinToString("  ") { "${it.mediaName}=${hostOf(it.url)}" })
                    detail.medias.forEach { media -> media.url?.let { links += media.mediaName to it } }
                }
                val targets = links.filter { it.first in variants }.distinctBy { it.first to hostOf(it.second) }
                for ((variant, url) in targets) {
                    // A refused handshake is a finding about that host, not a reason to stop
                    runCatching { measure(variant, url) }
                        .onFailure { log("  $variant @ ${hostOf(url)}: ${it::class.simpleName} ${it.message?.take(80)}") }
                }
                log("")
            }
        } finally {
            sdk.close()
            File("build/host-probe-report.txt").apply {
                parentFile.mkdirs()
                writeText(report.toString())
                println("[hosts] report written to $absolutePath")
            }
        }
    }

    /**
     * One link: its host's addresses and connect time, a 2 MiB range read cold
     * and again warm on the same link, then eight fresh 1 MiB ranges at once.
     * Every range starts at a different offset so the eight are all cold.
     */
    private suspend fun measure(variant: String, url: String) = withContext(Dispatchers.IO) {
        val host = hostOf(url)
        val fullHost = url.substringAfter("://").substringBefore("/")
        val addresses = runCatching { InetAddress.getAllByName(fullHost).joinToString(",") { it.hostAddress } }.getOrElse { "?" }
        val size = contentLength(url)
        // Scaled for short transcodes: the probe needs eighteen chunks past the base offset
        val chunk = minOf(1L shl 20, size / 32 / 4096 * 4096)
        val base = size / 4 / 4096 * 4096
        val cold = timedRead(url, base, 2 * chunk)
        val warm = timedRead(url, base, 2 * chunk)
        val parallelStart = System.nanoTime()
        val eight = (0 until 8).map { i ->
            async(Dispatchers.IO) { timedRead(url, base + 8 * chunk + i * chunk, chunk) }
        }.awaitAll()
        val parallelMs = (System.nanoTime() - parallelStart) / 1e6
        val parallelRate = eight.sumOf { it.bytes } / 1048576.0 / (parallelMs / 1000)
        log(
            "  $variant @ $host [$addresses] size ${size shr 20} MiB, chunk ${chunk shr 10} KiB\n" +
                "    cold x2 : connect ${cold.connectMs}ms tls ${cold.tlsMs}ms ttfb ${cold.ttfbMs}ms  ${cold.rate} MB/s\n" +
                "    warm x2 : connect ${warm.connectMs}ms tls ${warm.tlsMs}ms ttfb ${warm.ttfbMs}ms  ${warm.rate} MB/s\n" +
                "    8 x 1   : ttfb ${eight.minOf { it.ttfbMs }}-${eight.maxOf { it.ttfbMs }}ms  " +
                "per conn ${eight.joinToString(" ") { it.rate }} MB/s  total ${"%.2f".format(parallelRate)} MB/s",
        )
    }

    private class Timing(val connectMs: Long, val tlsMs: Long, val ttfbMs: Long, val bytes: Long, val bodyMs: Double) {
        val rate: String get() = "%.2f".format(if (bodyMs <= 0) 0.0 else bytes / 1048576.0 / (bodyMs / 1000))
    }

    /** Each read on a client of its own, so connect time is really paid and the listener sees only this call. */
    private fun timedRead(url: String, start: Long, length: Long): Timing {
        var connectStart = 0L
        var connectEnd = 0L
        var tlsStart = 0L
        var tlsEnd = 0L
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .readTimeout(60, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connectStart = System.nanoTime() }
                override fun secureConnectStart(call: Call) { tlsStart = System.nanoTime() }
                override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { tlsEnd = System.nanoTime() }
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connectEnd = System.nanoTime() }
            })
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", PikPakConstants.USER_AGENT)
            .header("Range", "bytes=$start-${start + length - 1}")
            .build()
        val began = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val headersAt = System.nanoTime()
                val bytes = response.body.byteStream().use { it.readAllBytes().size.toLong() }
                val done = System.nanoTime()
                // TCP connect is one round trip, the closest thing to RTT this side of a raw socket
                val connectMs = if (connectEnd > connectStart) (tlsStart.takeIf { it > 0 } ?: connectEnd).minus(connectStart) / 1_000_000 else 0
                val tlsMs = if (tlsEnd > tlsStart && tlsStart > 0) (tlsEnd - tlsStart) / 1_000_000 else 0
                return Timing(connectMs, tlsMs, (headersAt - began) / 1_000_000, bytes, (done - headersAt) / 1e6)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun contentLength(url: String): Long {
        val client = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build()
        val request = Request.Builder().url(url).header("User-Agent", PikPakConstants.USER_AGENT).header("Range", "bytes=0-0").build()
        return client.newCall(request).execute().use { response ->
            response.header("Content-Range")?.substringAfter('/')?.toLong() ?: error("no Content-Range")
        }
    }

    private fun hostOf(url: String?): String = url?.substringAfter("://")?.substringBefore("/")?.substringBefore(".") ?: "-"
}
