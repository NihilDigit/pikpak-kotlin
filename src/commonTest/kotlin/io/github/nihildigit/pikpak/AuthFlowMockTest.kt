package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the Session state machine in AuthApi.loginLocked:
 *  - fresh login → captcha init + signin
 *  - cached & fresh session → no network
 *  - cached & expired → token refresh
 *  - cached & expired with bad refresh_token → fall back to signin
 */
class AuthFlowMockTest {

    private val callLog = mutableListOf<String>()

    @Test
    fun `first login hits captcha init then signin and persists session`() = runBlocking {
        val store = InMemorySessionStore()
        val client = clientWith(store) { req ->
            callLog += "${req.method.value} ${req.url.encodedPath}"
            when {
                req.url.encodedPath.endsWith("/v1/shield/captcha/init") ->
                    respondJson("""{"captcha_token":"CAP-1"}""")
                req.url.encodedPath.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> respond404()
            }
        }

        val session = client.login()

        assertEquals("AT", session.accessToken)
        assertEquals("RT", session.refreshToken)
        assertEquals("UID", session.sub)
        assertEquals(1, callLog.count { it.endsWith("/v1/shield/captcha/init") })
        assertEquals(1, callLog.count { it.endsWith("/v1/auth/signin") })
        assertNotNull(store.load("mock@x"))
        client.close()
    }

    @Test
    fun `cached fresh session skips the network`() = runBlocking {
        val store = InMemorySessionStore()
        val farFuture = 9_999_999_999L
        store.save("mock@x", Session("AT", "RT", "UID", farFuture))

        val client = clientWith(store) { req ->
            callLog += "${req.method.value} ${req.url.encodedPath}"
            respondJson("""{"ok":true}""")
        }

        val session = client.login()
        assertEquals("AT", session.accessToken)
        assertEquals(0, callLog.size, "cached session must not trigger any HTTP call, saw: $callLog")
        client.close()
    }

    @Test
    fun `expired cached session triggers refresh_token grant`() = runBlocking {
        val store = InMemorySessionStore()
        store.save("mock@x", Session("OLD-AT", "RT-1", "UID", expiresAt = 1L))

        val client = clientWith(store) { req ->
            callLog += "${req.method.value} ${req.url.encodedPath}"
            when {
                req.url.encodedPath.endsWith("/v1/auth/token") ->
                    respondJson("""{"access_token":"NEW-AT","refresh_token":"RT-2","sub":"UID","expires_in":3600}""")
                else -> respond404()
            }
        }

        val session = client.login()
        assertEquals("NEW-AT", session.accessToken)
        assertEquals("RT-2", session.refreshToken)
        assertEquals(1, callLog.count { it.endsWith("/v1/auth/token") })
        assertEquals(0, callLog.count { it.endsWith("/v1/auth/signin") })
        client.close()
    }

    @Test
    fun `refresh_token rejected with 4126 falls back to full signin`() = runBlocking {
        val store = InMemorySessionStore()
        store.save("mock@x", Session("OLD", "BAD-RT", "UID", expiresAt = 1L))

        val client = clientWith(store) { req ->
            callLog += "${req.method.value} ${req.url.encodedPath}"
            when {
                req.url.encodedPath.endsWith("/v1/auth/token") ->
                    respondJson("""{"error_code":4126,"error":"refresh_token_invalid"}""")
                req.url.encodedPath.endsWith("/v1/shield/captcha/init") ->
                    respondJson("""{"captcha_token":"CAP-2"}""")
                req.url.encodedPath.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"RECOVERED","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> respond404()
            }
        }

        val session = client.login()
        assertEquals("RECOVERED", session.accessToken)
        assertEquals(1, callLog.count { it.endsWith("/v1/auth/token") })
        assertEquals(1, callLog.count { it.endsWith("/v1/auth/signin") })
        client.close()
    }

    @Test
    fun `signin failure bubbles as PikPakException`() = runBlocking {
        val client = clientWith(InMemorySessionStore()) { req ->
            when {
                req.url.encodedPath.endsWith("/v1/shield/captcha/init") ->
                    respondJson("""{"captcha_token":"CAP"}""")
                req.url.encodedPath.endsWith("/v1/auth/signin") ->
                    respondJson("""{"error_code":16,"error":"invalid_credentials","error_description":"bad password"}""")
                else -> respond404()
            }
        }
        val e = assertFailsWith<PikPakException> { client.login() }
        assertEquals(16, e.errorCode)
        assertTrue("bad password" in (e.message ?: ""))
        client.close()
    }

    @Test
    fun `logout clears both in-memory and stored session`() = runBlocking {
        val store = InMemorySessionStore()
        store.save("mock@x", Session("AT", "RT", "UID", 9_999_999_999L))

        val client = clientWith(store) { respond404() }
        client.login()
        assertNotNull(client.currentSession)

        client.logout()
        assertNull(client.currentSession)
        assertNull(store.load("mock@x"))
        client.close()
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

    private fun clientWith(
        store: SessionStore,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req -> handler(req) }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = store,
            httpClient = HttpClient(engine),
        )
    }
}
