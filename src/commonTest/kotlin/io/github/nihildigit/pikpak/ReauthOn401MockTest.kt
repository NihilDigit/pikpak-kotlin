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

/**
 * A token can be revoked server-side long before the `expires_in` it was
 * issued with runs out, and the only signal is a 401 on the next call. Without
 * this path a client polling a task for ten minutes fails permanently the
 * moment that happens, because every local check still says the session is
 * fresh.
 */
class ReauthOn401MockTest {

    @Test
    fun `401 refreshes the session once and replays the request`() = runBlocking {
        val log = mutableListOf<String>()
        var tokensIssued = 0

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            log += "${request.method.value} $path"
            when {
                path.endsWith("/v1/shield/captcha/init") -> json("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") -> json(
                    """{"access_token":"AT-1","refresh_token":"RT","sub":"U","expires_in":3600}""",
                )
                path.endsWith("/v1/auth/token") -> {
                    tokensIssued++
                    json("""{"access_token":"AT-2","refresh_token":"RT","sub":"U","expires_in":3600}""")
                }
                path.endsWith("/drive/v1/about") ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer AT-1") {
                        respond(
                            content = ByteReadChannel(""""""),
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        json("""{"kind":"drive#about","quota":{"limit":"42","usage":"1"}}""")
                    }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
        try {
            client.login()
            val quota = client.getQuota()
            assertEquals(42L, quota.quota.limitBytes)
            assertEquals(1, tokensIssued, "exactly one refresh_token grant")
            assertEquals(2, log.count { it == "GET /drive/v1/about" }, "the 401 call is replayed once")
            assertEquals("AT-2", client.currentSession?.accessToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a second 401 is surfaced to the caller`() = runBlocking {
        val log = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            log += path
            when {
                path.endsWith("/v1/shield/captcha/init") -> json("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") -> json(
                    """{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""",
                )
                path.endsWith("/v1/auth/token") -> json(
                    """{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""",
                )
                else -> respond(
                    content = ByteReadChannel("""{"error":"unauthorized"}"""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
        try {
            client.login()
            val e = assertFailsWith<PikPakException> { client.getQuota() }
            assertEquals(401, e.httpStatus)
            assertEquals(2, log.count { it.endsWith("/drive/v1/about") }, "no retry storm on a genuine 401")
        } finally {
            client.close()
        }
    }

    private fun MockRequestHandleScope.json(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
