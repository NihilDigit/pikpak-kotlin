package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Guards the property the range reader is built on: `sendRaw` must hand the
 * caller a live connection, not a buffer.
 *
 * The server here writes the first half of the body and then stops until the
 * caller says so. Under the old `HttpStatement.execute()` path — which calls
 * `call.save()` and materialises the whole body as a ByteArray — the block
 * would never be entered and this test would hit its timeout. A byte count is
 * not enough to catch that regression; the withholding is the assertion.
 */
class StreamingBodyMockTest {

    @Test
    fun `sendRaw enters the block before the body has finished arriving`() = runBlocking {
        val head = ByteArray(16) { 1 }
        val tail = ByteArray(16) { 2 }
        val releaseTail = CompletableDeferred<Unit>()

        val client = clientWith { _ ->
            val body = ByteChannel(autoFlush = true)
            CoroutineScope(Dispatchers.Default).launch {
                body.writeByteArray(head)
                body.flush()
                releaseTail.await()
                body.writeByteArray(tail)
                body.flushAndClose()
            }
            respond(body, HttpStatusCode.OK)
        }

        val total = withTimeout(15.seconds) {
            client.http.sendRaw(HttpMethod.Get, "https://cdn/file") { response ->
                val channel = response.bodyAsChannel()
                val first = channel.readExactly(head.size)
                assertEquals(head.toList(), first.toList())
                releaseTail.complete(Unit)
                val second = channel.readExactly(tail.size)
                assertEquals(tail.toList(), second.toList())
                first.size + second.size
            }
        }

        assertEquals(32, total)
        client.close()
    }

    private suspend fun ByteReadChannel.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = readAvailable(out, filled, count - filled)
            if (n == -1) break
            filled += n
        }
        return out.copyOf(filled)
    }

    private fun clientWith(
        cdnHandler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") ->
                    respond(
                        content = ByteReadChannel("""{"captcha_token":"CAP"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                path.endsWith("/v1/auth/signin") ->
                    respond(
                        content = ByteReadChannel(
                            """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> cdnHandler(req)
            }
        }
        val client = PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
        runBlocking { client.login() }
        return client
    }
}
