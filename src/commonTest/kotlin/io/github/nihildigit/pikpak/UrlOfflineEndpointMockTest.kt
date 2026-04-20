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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UrlOfflineEndpointMockTest {

    @Test
    fun `task in response produces Queued`() = runBlocking {
        val client = clientWithAuth { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/files")) {
                respondJson(
                    """{"task":{"id":"TID","phase":"PHASE_TYPE_RUNNING","name":"foo","file_id":"","file_size":"0"}}""",
                )
            } else respond404()
        }
        val result = client.createUrlFile(parentId = "", url = "https://example/x")
        val queued = assertIs<CreateUrlResult.Queued>(result)
        assertEquals("TID", queued.task.id)
        assertEquals("PHASE_TYPE_RUNNING", queued.task.phase)
        client.close()
    }

    @Test
    fun `missing task field produces InstantComplete`() = runBlocking {
        val client = clientWithAuth { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/files")) {
                // PikPak's cached-URL response path: envelope without `task`.
                respondJson("""{"upload_type":"UPLOAD_TYPE_URL"}""")
            } else respond404()
        }
        val result = client.createUrlFile(parentId = "parent", url = "https://example/x")
        assertTrue(result is CreateUrlResult.InstantComplete)
        client.close()
    }

    @Test
    fun `empty parentId is not sent in the body`() = runBlocking {
        var observedBody: String? = null
        val client = clientWithAuth { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/files")) {
                observedBody = req.body.toByteArrayText()
                respondJson(
                    """{"task":{"id":"T","phase":"PHASE_TYPE_RUNNING","name":"n","file_size":"0"}}""",
                )
            } else respond404()
        }
        client.createUrlFile(parentId = "", url = "https://example/x")
        val body = observedBody ?: error("request body was not captured")
        assertTrue("parent_id" !in body, "root-drive submission must omit parent_id")
        client.close()
    }

    private fun clientWithAuth(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
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
                else -> handler(req)
            }
        }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
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

    private suspend fun io.ktor.http.content.OutgoingContent.toByteArrayText(): String =
        when (this) {
            is io.ktor.http.content.TextContent -> this.text
            is io.ktor.http.content.ByteArrayContent -> this.bytes().decodeToString()
            else -> ""
        }
}
