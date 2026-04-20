package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies the resume + retry branches of [downloadFromUrl]:
 *  - no local file → full 200 OK download
 *  - partial local file → Range request → 206 → append
 *  - partial local file → server returns 200 (no range support) → restart
 *  - local file larger than expected → delete + restart
 *  - local file already at expected size → no HTTP, instant return
 */
class DownloadResumeMockTest {

    private val scratchFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        scratchFiles.forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
    }

    @Test
    fun `full 200 download when file absent`() = runBlocking {
        val dest = newScratchPath()
        val body = "0123456789".encodeToByteArray()

        val client = clientWithAuth { req ->
            assertEquals(null, req.headers[HttpHeaders.Range], "no Range header for full download")
            respondOk(body)
        }

        val written = client.downloadFromUrl("https://cdn/x", dest, expectedSize = body.size.toLong())
        assertEquals(body.size.toLong(), written)
        assertEquals("0123456789", readFile(dest))
        client.close()
    }

    @Test
    fun `partial local file triggers range request and append`() = runBlocking {
        val dest = newScratchPath()
        writeFile(dest, "01234")
        val tail = "56789".encodeToByteArray()

        val client = clientWithAuth { req ->
            val range = req.headers[HttpHeaders.Range]
            assertEquals("bytes=5-", range)
            respondPartialContent(tail)
        }

        val written = client.downloadFromUrl("https://cdn/x", dest, expectedSize = 10L)
        assertEquals(10L, written)
        assertEquals("0123456789", readFile(dest))
        client.close()
    }

    @Test
    fun `server 200 to a range request restarts from zero`() = runBlocking {
        val dest = newScratchPath()
        writeFile(dest, "STALE")
        val body = "freshfresh".encodeToByteArray()
        var callCount = 0

        val client = clientWithAuth { req ->
            callCount++
            if (callCount == 1) {
                // server ignored the Range header and returned 200
                respondOk(body)
            } else {
                // second attempt has no Range because the file was wiped
                assertEquals(null, req.headers[HttpHeaders.Range])
                respondOk(body)
            }
        }

        val written = client.downloadFromUrl("https://cdn/x", dest, expectedSize = body.size.toLong())
        assertEquals(body.size.toLong(), written)
        assertEquals("freshfresh", readFile(dest))
        client.close()
    }

    @Test
    fun `local file larger than expected is deleted and restarted`() = runBlocking {
        val dest = newScratchPath()
        writeFile(dest, "XXXXXXXXXXXXXXXX") // 16 bytes, expected is 5
        val body = "abcde".encodeToByteArray()
        var sawRange = false

        val client = clientWithAuth { req ->
            if (req.headers[HttpHeaders.Range] != null) sawRange = true
            respondOk(body)
        }

        val written = client.downloadFromUrl("https://cdn/x", dest, expectedSize = 5L)
        assertEquals(5L, written)
        assertEquals("abcde", readFile(dest))
        assertEquals(false, sawRange, "oversize local file must NOT lead to a range request")
        client.close()
    }

    @Test
    fun `file already at expected size returns immediately without HTTP`() = runBlocking {
        val dest = newScratchPath()
        writeFile(dest, "abcde")
        var calls = 0

        val client = clientWithAuth { _ ->
            calls++
            respondOk("ignored".encodeToByteArray())
        }

        val written = client.downloadFromUrl("https://cdn/x", dest, expectedSize = 5L)
        assertEquals(5L, written)
        assertEquals(0, calls, "already-complete file must short-circuit before HTTP")
        client.close()
    }

    @Test
    fun `short server body fails after exhausting retries`() = runBlocking {
        val dest = newScratchPath()
        // expected 10 bytes, server only returns 5 → each attempt marks Retry.
        val body = "12345".encodeToByteArray()

        val client = clientWithAuth(
            retryPolicy = RetryPolicy(
                maxAttempts = 2,
                initialDelay = 1.milliseconds,
                maxDelay = 2.milliseconds,
            ),
        ) { _ -> respondOk(body) }

        assertFailsWith<PikPakException> {
            client.downloadFromUrl("https://cdn/x", dest, expectedSize = 10L)
        }
        client.close()
    }

    // --- helpers ---

    private fun clientWithAuth(
        retryPolicy: RetryPolicy = RetryPolicy(
            maxAttempts = 3,
            initialDelay = 1.milliseconds,
            maxDelay = 2.milliseconds,
        ),
        cdnHandler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") ->
                    respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson(
                        """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""",
                    )
                else -> cdnHandler(req)
            }
        }
        val client = PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            retryPolicy = retryPolicy,
            httpClient = HttpClient(engine),
        )
        runBlocking { client.login() }
        return client
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.respondOk(body: ByteArray) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
    )

    private fun MockRequestHandleScope.respondPartialContent(body: ByteArray) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.PartialContent,
    )

    private fun newScratchPath(): Path {
        val dir = tmpDir()
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
        val p = Path(dir, "dl-${counter++}.bin")
        scratchFiles += p
        return p
    }

    private fun writeFile(path: Path, text: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    private fun readFile(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun tmpDir(): Path = Path("build", "dl-scratch")

    companion object {
        private var counter = 0
    }
}
