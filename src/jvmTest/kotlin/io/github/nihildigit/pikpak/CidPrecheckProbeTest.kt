package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assumptions
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test

/**
 * Whether `/drive/v1/resource/cid` maps a Xunlei CID (SHA-1 over three 20 KB
 * windows) to the gcid of content PikPak already has, so an upload could skip
 * hashing the whole file. Reads existing files only through three range
 * requests each; uploads nothing. File names never reach the log.
 * Opt in with PIKPAK_PROBE=1.
 */
class CidPrecheckProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `cid precheck against existing files`(): Unit = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val files = collectFiles(client, maxFolders = 12)
            println("[pool] ${files.size} files")
            val picks = listOfNotNull(
                files.filter { it.sizeBytes in 1 until SMALL_LIMIT }.minByOrNull { it.sizeBytes },
                files.filter { it.sizeBytes in SMALL_LIMIT until 2_000_000 }.maxByOrNull { it.sizeBytes },
                files.filter { it.mimeType.startsWith("video/") }.maxByOrNull { it.sizeBytes },
                files.filter { it.mimeType.startsWith("video/") && it.sizeBytes < 500_000_000 }.maxByOrNull { it.sizeBytes },
            ).distinctBy { it.id }

            var firstCid: Pair<String, Long>? = null
            for (f in picks) {
                val url = client.getFile(f.id).downloadUrl ?: run { println("[skip] no link size=${f.sizeBytes}"); continue }
                val cid = cidOf(client, url, f.sizeBytes)
                // The SDK's own implementation, end to end: it must agree with this probe's and hit
                val sdkCid = XunleiCid.of(f.sizeBytes) { offset, length -> runBlocking { read(client, url, offset, length.toLong()) } }
                println("[sdk] cid matches probe=${sdkCid == cid} gcidByCid matches hash=${client.gcidByCid(sdkCid, f.sizeBytes) == f.hash?.lowercase()}")
                if (firstCid == null) firstCid = cid to f.sizeBytes
                val r = query(client, cid.lowercase(), f.sizeBytes)
                println("[file] mime=${f.mimeType} size=${f.sizeBytes} hash=${f.hash} cid=$cid")
                println("  -> $r")
                println("  upper-case cid -> ${query(client, cid, f.sizeBytes)}")
                println("  size+1 -> ${query(client, cid.lowercase(), f.sizeBytes + 1)}")
                println("  size 0 -> ${query(client, cid.lowercase(), 0)}")
                println("  no file_size -> ${query(client, cid.lowercase(), null)}")
            }

            val randomCid = hex(Random.nextBytes(20))
            println("[random cid, size 734003200] ${query(client, randomCid, 734_003_200)}")
            val local = Random.nextBytes(100 * 1024)
            val localCid = cidOfBytes(local)
            println("[local random 100KB] cid=$localCid gcid=${PikPakHash.fromSource(kotlinx.io.Buffer().apply { write(local) }, local.size.toLong())} -> ${query(client, localCid.lowercase(), local.size.toLong())}")
            println("[malformed cid] ${query(client, "xyz", 1000)}")
            firstCid?.let { (cid, size) -> println("[repeat first] ${query(client, cid.lowercase(), size)}") }
        } finally {
            client.close()
        }
    }

    private suspend fun query(client: PikPakClient, cid: String, size: Long?): String {
        val params = buildMap {
            put("cid", cid)
            if (size != null) put("file_size", size.toString())
        }
        return runCatching {
            client.http.request(
                HttpMethod.Get,
                buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/resource/cid", params),
                captchaAction = "GET:/drive/v1/resource/cid",
            )
        }.fold({ it.toString() }, { e ->
            if (e is PikPakException) "http=${e.httpStatus} code=${e.errorCode} error=${e.errorMessage} body=${e.rawBody}" else e.toString()
        })
    }

    private suspend fun collectFiles(client: PikPakClient, maxFolders: Int): List<FileStat> {
        val out = mutableListOf<FileStat>()
        val queue = ArrayDeque(listOf(""))
        var visited = 0
        while (queue.isNotEmpty() && visited < maxFolders) {
            val parent = queue.removeFirst()
            visited++
            for (f in client.listFiles(parent)) {
                if (f.kind.endsWith("folder")) queue += f.id else out += f
            }
        }
        return out
    }

    private suspend fun cidOf(client: PikPakClient, url: String, size: Long): String {
        if (size < SMALL_LIMIT) return hex(sha1(read(client, url, 0, size)))
        val windows = listOf(0L, size / 3, size - WINDOW).map { read(client, url, it, WINDOW) }
        return hex(sha1(*windows.toTypedArray()))
    }

    private fun cidOfBytes(b: ByteArray): String {
        if (b.size < SMALL_LIMIT) return hex(sha1(b))
        val n = b.size
        return hex(sha1(b.copyOfRange(0, WINDOW.toInt()), b.copyOfRange(n / 3, n / 3 + WINDOW.toInt()), b.copyOfRange(n - WINDOW.toInt(), n)))
    }

    private suspend fun read(client: PikPakClient, url: String, start: Long, length: Long): ByteArray =
        client.streamRangeFromUrl(url, start, length) { it.channel.toByteArray() }
            .also { check(it.size.toLong() == length) { "short read ${it.size}/$length at $start" } }

    private fun sha1(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { parts.forEach { update(it) } }.digest()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    private val FileStat.sizeBytes: Long get() = size.toLongOrNull() ?: 0L

    private companion object {
        const val SMALL_LIMIT = 0xF000L
        const val WINDOW = 0x5000L
    }
}
