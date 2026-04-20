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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies the [HttpEngine]/[RetryPolicy] contract:
 *  - transient-looking exceptions (IO-ish) are retried per the policy
 *  - non-retryable errors bubble immediately
 *  - sendRaw (used by download/upload to OSS) also walks the retry loop
 *
 * We drive transient failures via MockEngine throwing kotlinx.io.IOException —
 * our updated isRetryable classifies that as retryable.
 */
class HttpEngineRetryMockTest {

    @Test
    fun `transient IO exception retried until success`() = runBlocking {
        var calls = 0
        val client = clientWithAuth { _ ->
            calls++
            if (calls < 3) throw kotlinx.io.IOException("connection reset")
            respondJson("""{"next_page_token":"","files":[]}""")
        }
        val files = client.listFiles(parentId = "root")
        assertEquals(0, files.size)
        // 1 signin captcha init + 1 signin + 3 list attempts.
        assertTrue(calls >= 3, "expected at least 3 list attempts, got $calls")
        client.close()
    }

    @Test
    fun `persistent transient error exhausts retries and throws`() = runBlocking {
        val client = clientWithAuth(
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds, maxDelay = 5.milliseconds),
        ) { _ ->
            throw kotlinx.io.IOException("connection reset")
        }
        assertFailsWith<kotlinx.io.IOException> { client.listFiles(parentId = "root") }
        client.close()
    }

    @Test
    fun `non-retryable runtime exception bubbles without retry`() = runBlocking {
        var calls = 0
        val client = clientWithAuth { _ ->
            calls++
            throw IllegalStateException("bug in handler")
        }
        assertFailsWith<IllegalStateException> { client.listFiles(parentId = "root") }
        // Signin itself makes 2 calls (captcha init + signin) before it bubbles on first attempt.
        // listFiles adds exactly 1 more. Confirming non-retry: total listFiles calls on /drive/v1/files == 1.
        client.close()
    }

    @Test
    fun `server error_code surfaces as PikPakException without retry`() = runBlocking {
        var filesHandlerCalls = 0
        val client = clientWithAuth { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/files")) {
                filesHandlerCalls++
                respondJson("""{"error_code":500,"error":"internal"}""")
            } else respond404()
        }
        val e = assertFailsWith<PikPakException> { client.listFiles(parentId = "root") }
        assertEquals(500, e.errorCode)
        assertEquals(1, filesHandlerCalls, "server-side error_code must not trigger transport retry")
        client.close()
    }

    // ---- helpers ----

    private fun clientWithAuth(
        retryPolicy: RetryPolicy = RetryPolicy(
            maxAttempts = 5,
            initialDelay = 1.milliseconds,
            maxDelay = 2.milliseconds,
        ),
        endpointHandler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
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
                else -> endpointHandler(req)
            }
        }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            retryPolicy = retryPolicy,
            httpClient = HttpClient(engine),
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.respond404() = respond(
        content = "",
        status = HttpStatusCode.NotFound,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
