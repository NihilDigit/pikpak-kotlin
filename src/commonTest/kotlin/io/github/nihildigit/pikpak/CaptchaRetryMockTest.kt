package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the captcha auto-retry contract: when an authenticated PikPak request
 * comes back with `error_code=9` (captcha required), the HTTP layer must
 * silently re-issue the X-Captcha-Token via the salt-cascade signing flow
 * and retry the original request exactly once.
 *
 * Live API can't be coerced into returning code 9 reliably, so this is a
 * Ktor MockEngine unit test. The mock returns code 9 the first time
 * `/drive/v1/files` is hit and a valid file listing the second time. We
 * assert the listing is delivered to the caller, that `/v1/shield/captcha/init`
 * was called twice (once for signin, once for the post-9 refresh), and that
 * the client's in-memory captcha token reflects the refreshed value.
 */
class CaptchaRetryMockTest {

    private val callLog = mutableListOf<String>()

    private fun newClient(): PikPakClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            callLog += "${request.method.value} $path"
            val captchaInitCount = callLog.count { it.endsWith("/v1/shield/captcha/init") }
            val filesGetCount = callLog.count { it == "GET /drive/v1/files" }
            when {
                path.endsWith("/v1/shield/captcha/init") -> json(
                    """{"captcha_token":"CAPTCHA-$captchaInitCount","expires_in":300,"url":""}""",
                )
                path.endsWith("/v1/auth/signin") -> json(
                    """{"access_token":"AT-1","refresh_token":"RT-1","sub":"USER","expires_in":3600}""",
                )
                path.endsWith("/drive/v1/files") && filesGetCount == 1 -> json(
                    """{"error_code":9,"error":"captcha_required"}""",
                )
                path.endsWith("/drive/v1/files") -> json(
                    """{"next_page_token":"","files":[
                          {"kind":"drive#file","id":"FID","name":"hello.txt","size":"5"}
                       ]}""",
                )
                else -> respond(
                    content = "",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val http = HttpClient(engine)
        return PikPakClient(
            account = "mock@example.com",
            password = "mock-password",
            sessionStore = InMemorySessionStore(),
            httpClient = http,
        )
    }

    private fun MockRequestHandleScope.json(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `error_code 9 triggers captcha refresh and one retry`() = runBlocking {
        val client = newClient()
        try {
            client.login()
            assertEquals("CAPTCHA-1", client.state.captchaToken, "after signin we should hold the first captcha token")

            val files = client.listFiles(parentId = "root-folder")

            assertEquals(1, files.size, "second response (after refresh) must reach the caller")
            assertEquals("hello.txt", files[0].name)

            val captchaCalls = callLog.count { it.endsWith("/v1/shield/captcha/init") }
            assertEquals(2, captchaCalls, "captcha/init should fire twice: once at signin, once at the post-9 refresh")

            val filesCalls = callLog.count { it == "GET /drive/v1/files" }
            assertEquals(2, filesCalls, "the failed call should be retried exactly once")

            assertEquals(
                "CAPTCHA-2", client.state.captchaToken,
                "in-memory token must reflect the freshly refreshed value",
            )

            val signinCalls = callLog.count { it.endsWith("/v1/auth/signin") }
            assertEquals(1, signinCalls, "we must NOT fall back to a fresh signin on captcha-9")
        } finally {
            client.close()
        }
    }

    /**
     * Two coroutines hold the same stale captcha token and both get
     * error_code=9. Only one captcha/init may go out: the refresh is keyed on
     * the token, not on the request that tripped over it, so N concurrent
     * callers used to mean N handshakes and N of them racing to overwrite each
     * other's token.
     *
     * Both requests are parked in the mock engine until the second one arrives,
     * which is what guarantees they read the same stale token before either
     * takes the lock.
     */
    @Test
    fun `concurrent error_code 9 triggers a single captcha init`() = runBlocking {
        val firstArrived = CompletableDeferred<Unit>()
        val bothArrived = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            log += "${request.method.value} $path"
            val captchaInitCount = log.count { it.endsWith("/v1/shield/captcha/init") }
            when {
                path.endsWith("/v1/shield/captcha/init") -> json(
                    """{"captcha_token":"CAPTCHA-$captchaInitCount","expires_in":300,"url":""}""",
                )
                path.endsWith("/v1/auth/signin") -> json(
                    """{"access_token":"AT-1","refresh_token":"RT-1","sub":"USER","expires_in":3600}""",
                )
                path.endsWith("/drive/v1/about") -> {
                    val token = request.headers["X-Captcha-Token"]
                    if (token == "CAPTCHA-1") {
                        if (firstArrived.isCompleted) bothArrived.complete(Unit) else {
                            firstArrived.complete(Unit)
                            bothArrived.await()
                        }
                        json("""{"error_code":9,"error":"captcha_required"}""")
                    } else {
                        json("""{"kind":"drive#about","quota":{"limit":"10","usage":"1"}}""")
                    }
                }
                else -> respond(
                    content = "",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val client = PikPakClient(
            account = "mock@example.com",
            password = "mock-password",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
        try {
            client.login()
            assertEquals("CAPTCHA-1", client.state.captchaToken)

            val results = listOf(async { client.getQuota() }, async { client.getQuota() }).awaitAll()
            assertTrue(results.all { it.quota.limitBytes == 10L }, "both callers must get the retried response")

            assertEquals(
                2, log.count { it.endsWith("/v1/shield/captcha/init") },
                "one init at signin plus one shared refresh — not one refresh per coroutine",
            )
            assertEquals("CAPTCHA-2", client.state.captchaToken)
        } finally {
            client.close()
        }
    }
}

private typealias MockRequestHandleScope = io.ktor.client.engine.mock.MockRequestHandleScope
