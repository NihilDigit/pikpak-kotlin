package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import org.junit.jupiter.api.Assumptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end check of the pieces animeko's playback path depends on, against
 * the live account. Self-skips without `.env` credentials.
 *
 * Every HTTP exchange goes through one OkHttp interceptor so the test can
 * assert on traffic the SDK is supposed to avoid — a second token grant, a 503
 * reaching the caller — rather than only on return values.
 */
class RangeReaderSmokeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val folder = env["PIKPAK_SMOKE_FOLDER"] ?: "Animeko-Playing"

    private val paths = ConcurrentHashMap<String, AtomicInteger>()
    private val statuses = ConcurrentHashMap<Int, AtomicInteger>()

    private fun countingClient(budget: Int): HttpClient = HttpClient(OkHttp) {
        engine {
            addInterceptor { chain ->
                paths.computeIfAbsent(chain.request().url.encodedPath) { AtomicInteger() }.incrementAndGet()
                val response = chain.proceed(chain.request())
                statuses.computeIfAbsent(response.code) { AtomicInteger() }.incrementAndGet()
                response
            }
            config {
                protocols(listOf(Protocol.HTTP_1_1))
                connectionPool(ConnectionPool(budget * 2, 5, TimeUnit.MINUTES))
                dispatcher(
                    Dispatcher().apply {
                        maxRequestsPerHost = budget
                        maxRequests = budget * 4
                    },
                )
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }

    private fun count(path: String) = paths[path]?.get() ?: 0

    @Test
    fun `smoke the range reader against the live cdn`() = runBlocking {
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val budget = 8
        val http = countingClient(budget)
        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            connectionBudget = budget,
        )
        var uploaded: String? = null
        try {
            // 1. A second login must not go back to the token endpoint.
            client.login()
            val tokensAfterFirst = count("/v1/auth/token") + count("/v1/auth/signin")
            client.login()
            assertEquals(
                tokensAfterFirst,
                count("/v1/auth/token") + count("/v1/auth/signin"),
                "the second login must reuse the in-memory session",
            )
            println("[smoke] auth calls after two logins: signin=${count("/v1/auth/signin")} token=${count("/v1/auth/token")}")

            val parentId = client.getPathFolderId(folder)
            // Releases usually land one level down, in a per-title folder.
            val pool = client.listFiles(parentId).let { top ->
                top + top.filter { it.isFolder }.flatMap { client.listFiles(it.id) }
            }
            println("[smoke] $folder holds ${pool.count { it.isFile }} files across ${pool.count { it.isFolder }} subfolders")
            val target = pool.filter { it.isFile && it.sizeBytes > 4L * 1024 * 1024 }
                .maxByOrNull { it.sizeBytes }
            Assumptions.assumeTrue(target != null, "no file over 4 MB under $folder")
            val detail = client.getFile(target!!.id)
            val url = detail.downloadUrl!!
            println("[smoke] file=${detail.name} size=${detail.sizeBytes} links=${detail.links.keys}")
            println("[smoke] expiresAt=${detail.octetStream.expiresAt} queryKeys=${queryKeys(url)}")

            // 2. Eight concurrent reads must all land, with no 503 reaching us.
            val readerBudget = budget
            val reader = client.rangeReader(target.id, connectionBudget = readerBudget)
            val before503 = statuses[503]?.get() ?: 0
            val chunk = 256L * 1024
            val results = coroutineScope {
                (0 until 8).map { i ->
                    async { reader.readBytes(i * chunk, chunk, priority = if (i == 0) 10 else 0).size }
                }.awaitAll()
            }
            assertTrue(results.all { it == chunk.toInt() }, "every concurrent read must deliver a full chunk")
            val seen503 = (statuses[503]?.get() ?: 0) - before503
            println("[smoke] 8 concurrent reads OK; wire 503s during the burst = $seen503; stats=${reader.stats.value}")
            reader.close()

            // 3. A tampered signature must be recovered from via the provider.
            val tampered = tamperExpiry(url)
            Assumptions.assumeTrue(tampered != null, "download URL has no recognizable expiry parameter")
            var handedStale = false
            val refreshingReader = RangeReader(
                client = client,
                urlProvider = { request ->
                    if (!handedStale) {
                        handedStale = true
                        println("[smoke] handing out the tampered URL first")
                        tampered!!
                    } else {
                        println("[smoke] provider asked again: $request")
                        client.getFile(target.id).downloadUrl!!
                    }
                },
                connectionBudget = 2,
            )
            val recovered = refreshingReader.readBytes(0, 64 * 1024)
            assertEquals(64 * 1024, recovered.size)
            assertEquals(1, refreshingReader.stats.value.urlRefreshes, "exactly one refresh")
            println("[smoke] recovered ${recovered.size} bytes after one refresh; stats=${refreshingReader.stats.value}")
            refreshingReader.close()

            // 4. parallelDownloadFromUrl still writes a whole small file.
            // Downloading a 1.4 GB episode four ways proves nothing the small
            // case does not, and costs minutes; upload a scratch file when the
            // folder holds nothing small.
            val existingSmall = pool.filter { it.isFile && it.sizeBytes in 1L..(8L * 1024 * 1024) }
                .minByOrNull { it.sizeBytes }
            val smallId = existingSmall?.id ?: uploadScratchFile(client, parentId).also { uploaded = it }
            // A just-uploaded file has no signed link until PikPak finishes
            // ingesting it, so getFile can legitimately answer with none.
            var smallDetail = client.getFile(smallId)
            repeat(10) {
                if (smallDetail.downloadUrl != null) return@repeat
                delay(2.seconds)
                smallDetail = client.getFile(smallId)
            }
            assertTrue(smallDetail.downloadUrl != null, "no download link for $smallId after 20s")
            val dest = Path(SystemFileSystem.resolve(Path("build")).toString(), "smoke-$smallId.bin")
            SystemFileSystem.delete(dest, mustExist = false)
            val written = client.parallelDownloadFromUrl(
                url = smallDetail.downloadUrl!!,
                dest = dest,
                partCount = 4,
                expectedSize = smallDetail.sizeBytes,
            )
            val onDisk = SystemFileSystem.metadataOrNull(dest)?.size ?: -1
            println("[smoke] parallelDownload ${smallDetail.name}: returned=$written onDisk=$onDisk expected=${smallDetail.sizeBytes}")
            assertEquals(smallDetail.sizeBytes, written)
            assertEquals(smallDetail.sizeBytes, onDisk)
            SystemFileSystem.delete(dest, mustExist = false)

            println("[smoke] status histogram: ${statuses.mapValues { it.value.get() }}")
        } finally {
            runCatching { uploaded?.let { client.batchTrash(listOf(it)) } }
            client.close()
            http.close()
        }
    }

    /** Uploads 2 MiB of deterministic bytes and returns the new file id. */
    private suspend fun uploadScratchFile(client: PikPakClient, parentId: String): String {
        val local = Path(SystemFileSystem.resolve(Path("build")).toString(), "smoke-source.bin")
        SystemFileSystem.delete(local, mustExist = false)
        SystemFileSystem.sink(local).buffered().use { sink ->
            val block = ByteArray(64 * 1024) { (it % 251).toByte() }
            repeat(32) { sink.write(block, 0, block.size) }
        }
        val result = client.upload(parentId, local)
        SystemFileSystem.delete(local, mustExist = false)
        println("[smoke] uploaded scratch file ${result.fileId} instant=${result.instantUpload}")
        return result.fileId
    }

    private fun queryKeys(url: String): List<String> =
        url.substringAfter('?', "").split('&').mapNotNull { it.substringBefore('=').takeIf { k -> k.isNotEmpty() } }

    /** Rewrites whichever expiry parameter the signed URL carries so the CDN rejects it. */
    private fun tamperExpiry(url: String): String? {
        for (key in listOf("expire", "e", "exp", "t")) {
            val marker = "$key="
            val at = url.indexOf("?$marker").takeIf { it >= 0 }?.plus(1)
                ?: url.indexOf("&$marker").takeIf { it >= 0 }?.plus(1)
                ?: continue
            val valueStart = at + marker.length
            val valueEnd = url.indexOf('&', valueStart).takeIf { it >= 0 } ?: url.length
            val original = url.substring(valueStart, valueEnd)
            val broken = original.dropLast(1) + if (original.last() == '0') '1' else '0'
            return url.substring(0, valueStart) + broken + url.substring(valueEnd)
        }
        return null
    }
}
