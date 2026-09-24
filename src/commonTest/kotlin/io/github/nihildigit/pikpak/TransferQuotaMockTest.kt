package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferQuotaMockTest {

    @Test
    fun `transfer quota decodes the account and connected-app allowances`() = runBlocking {
        // Recorded 2026-09-24, identifiers removed. The allowances are JSON numbers, not strings.
        val body = """
            {"transfer":{"offline":{"info":"","total_assets":0,"assets":0}},"data":null,"has_more":false,
             "base":{"info":"","vip_status":"ok","expire_time":"2027-04-17T02:40:40+08:00","assets":"10T","size":0,
               "offline":{"total_assets":43980465111040,"assets":736368637030,"size":736368637030},
               "download":{"total_assets":4398046511104,"assets":197410452763,"size":197410452763},
               "upload":{"total_assets":1099511627776,"assets":162173574191,"size":162173574191},
               "download_daily":{"total_assets":0,"assets":0,"size":0}},
             "apps_summary":{"offline":{"total_assets":10995116277760,"assets":0,"size":0},
               "download":{"total_assets":1099511627776,"assets":0,"size":0}}}
        """.trimIndent()
        val client = client { path, _, _ ->
            if (path.endsWith("/vip/v1/quantity/list")) respondJson(body) else respond("", HttpStatusCode.NotFound)
        }
        val quota = client.getTransferQuota()
        assertEquals(1_099_511_627_776L, quota.account.upload.limitBytes)
        assertEquals(162_173_574_191L, quota.account.upload.usedBytes)
        assertEquals(43_980_465_111_040L - 736_368_637_030L, quota.account.offline.remainingBytes)
        assertEquals("2027-04-17T02:40:40+08:00", quota.account.expireTime)
        assertEquals(10_995_116_277_760L, quota.connectedApps.offline.limitBytes)
        client.close()
    }

    private fun client(
        handler: MockRequestHandleScope.(path: String, parentId: String, body: String) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> handler(path, req.url.parameters["parent_id"].orEmpty(), (req.body as? TextContent)?.text.orEmpty())
            }
        }
        return PikPakClient(account = "mock@x", password = "pw", sessionStore = InMemorySessionStore(), httpClient = HttpClient(engine))
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
