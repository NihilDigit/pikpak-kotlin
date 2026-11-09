package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PikPakDomainMockTest {

    // Switching is a setting a user flips while the app runs; a root captured at construction,
    // or anywhere below the request pipeline, would keep sending to the old one until a restart
    @Test
    fun `a root switched at runtime applies to the next request and the session carries over`() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = clientWith { req ->
            hosts += req.url.host
            when {
                req.url.encodedPath.endsWith("/v1/shield/captcha/init") -> json("""{"captcha_token":"CAP"}""")
                req.url.encodedPath.endsWith("/v1/auth/signin") ->
                    json("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                req.url.encodedPath == "/drive/v1/about" -> json("""{"quota":{"usage":"1"}}""")
                else -> json("""{"error":"not_found","error_code":404}""", HttpStatusCode.NotFound)
            }
        }
        client.getQuota()
        val signIns = hosts.count { it.startsWith("user.") }

        client.domain = PikPakDomain.PIKPAK_ME
        hosts.clear()
        client.getQuota()

        assertEquals(listOf("api-drive.pikpak.me"), hosts, "only the drive call, under the new root")
        assertTrue(signIns > 0)
        client.close()
    }

    @Test
    fun `a probe accepts only the gateway refusal of an unauthenticated call`() = runBlocking {
        val client = clientWith { req ->
            when (req.url.host) {
                "api-drive.mypikpak.net", "user.mypikpak.net" ->
                    json("""{"error":"unauthenticated","error_code":16}""", HttpStatusCode.Unauthorized)
                // A valid wildcard certificate with another service behind it, as one community-listed address was
                "api-drive.pikpak.me" -> respond(ByteReadChannel("<html>404 Not Found</html>"), HttpStatusCode.NotFound)
                // Drive answers, user does not: sign-in and refresh would fail there
                "api-drive.pikpakdrive.com" -> json("""{"error":"unauthenticated","error_code":16}""", HttpStatusCode.Unauthorized)
                else -> respond(ByteReadChannel(""), HttpStatusCode.BadGateway)
            }
        }

        val net = client.probeDomain(PikPakDomain.MYPIKPAK_NET)
        assertTrue(net.usable, net.failure)
        assertNotNull(net.firstRequest)
        assertNotNull(net.warmRequest)

        assertFalse(client.probeDomain(PikPakDomain.PIKPAK_ME).usable)
        assertFalse(client.probeDomain(PikPakDomain.PIKPAKDRIVE_COM).usable)
        // The probe measures; it does not switch
        assertEquals(PikPakDomain.MYPIKPAK_COM, client.domain)
        client.close()
    }

    @Test
    fun `high availability config falls back across origins and updates static dns table`() = runBlocking {
        io.github.nihildigit.pikpak.internal.PikPakDnsFallback.resetForTest()
        val client = clientWith { req ->
            when {
                req.url.host == "dl.pikpak.io" -> respond(ByteReadChannel(""), HttpStatusCode.BadGateway)
                req.url.host == "config.mypikpak.com" && req.url.encodedPath == "/config/v1/high_availability" ->
                    json(
                        """{"result":"ok","data":{"main_domain":"mypikpak.com","backup_domains":["pickpackapp.com","mypikpak.net"],"private_network_detection":"https://api-drive.mypikpak.com/generate_204","dns":[{"domain":"api-drive.mypikpak.com","ip":["203.0.113.10","203.0.113.11"],"ipv6":[]}]}}""",
                    )
                req.url.encodedPath == "/generate_204" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent)
                else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            }
        }
        try {
            val config = assertNotNull(client.fetchHighAvailabilityConfig())
            assertEquals("mypikpak.com", config.mainDomain)
            assertTrue("pickpackapp.com" in config.backupDomains)
            assertEquals(
                listOf("203.0.113.10", "203.0.113.11"),
                io.github.nihildigit.pikpak.internal.PikPakDnsFallback.lookupIps("api-drive.mypikpak.com"),
            )
            assertEquals(
                listOf("203.0.113.10", "203.0.113.11"),
                io.github.nihildigit.pikpak.internal.PikPakDnsFallback.lookupIps("api-drive.mypikpak.net"),
            )
            assertTrue(client.probeGateway204())
        } finally {
            io.github.nihildigit.pikpak.internal.PikPakDnsFallback.resetForTest()
            client.close()
        }
    }

    private fun clientWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): PikPakClient =
        PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(MockEngine { req -> handler(req) }),
        )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
