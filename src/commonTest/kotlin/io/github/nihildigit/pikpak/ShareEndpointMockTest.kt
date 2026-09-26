package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareEndpointMockTest {

    private val requests = mutableListOf<HttpRequestData>()

    @Test
    fun `a custom pass code locks the share`() = runBlocking {
        val client = client { path, _ ->
            if (path.endsWith("/drive/v1/share")) {
                respondJson("""{"share_id":"S1","share_url":"https://mypikpak.com/s/S1","pass_code":"zq47","share_text":"","share_list":[],"share_error_files":[]}""")
            } else respond("", HttpStatusCode.NotFound)
        }
        val created = client.createShare(listOf("F1"), customPassCode = "zq47", expirationDays = 1)
        assertEquals("zq47", created.passCode)
        val body = bodyOf("/drive/v1/share")
        assertEquals("encryptedlink", body["share_to"]!!.jsonPrimitive.content)
        assertEquals("REQUIRED", body["pass_code_option"]!!.jsonPrimitive.content)
        assertEquals("zq47", body["custom_pass_code"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun `my shares decode from the data field`() = runBlocking {
        // Recorded 2026-09-24, names and ids replaced. Counts are strings on the wire.
        val client = client { path, _ ->
            if (path.endsWith("/share/list")) respondJson(
                """{"data":[{"share_id":"S1","share_status":"OK","share_status_text":"","title":"probe","pass_code":"mcbi",
                   "file_num":"1","restore_limit":"-1","expiration_days":"1","expiration_at":"2026-09-25T23:46:22.772+08:00",
                   "restore_count":"0","view_count":"0","create_time":"2026-09-24T23:46:22.772+08:00",
                   "user_info":{"user_id":"U","nickname":"N"},"share_url":"https://mypikpak.com/s/S1","file_id":"F1",
                   "file_kind":"drive#folder","file_size":"0","share_to":"encryptedlink","params":{}}],
                   "next_page_token":"S1"}""",
            ) else respond("", HttpStatusCode.NotFound)
        }
        val page = client.listMyShares(pageToken = "P0")
        assertEquals(listOf("S1"), page.shares.map { it.shareId })
        assertEquals("mcbi", page.shares.single().passCode)
        assertEquals("S1", page.nextPageToken)
        assertEquals("P0", requests.last().url.parameters["page_token"])
        client.close()
    }

    @Test
    fun `a missing pass code is thrown not returned as an empty share`() = runBlocking {
        // Recorded 2026-09-24: HTTP 200, no error_code, no files.
        val client = client { path, _ ->
            if (path.endsWith("/drive/v1/share")) respondJson(
                """{"share_status":"PASS_CODE_EMPTY","share_status_text":"Incorrect password, please try again",
                   "file_num":"0","files":[],"next_page_token":"","pass_code_token":"","title":""}""",
            ) else respond("", HttpStatusCode.NotFound)
        }
        val e = assertFailsWith<ShareUnavailableException> { client.getShareInfo("S1") }
        assertEquals(ShareStatus.PASS_CODE_EMPTY, e.status)
        assertTrue(e.needsPassCode)
        client.close()
    }

    @Test
    fun `share reads send the client id`() = runBlocking {
        val client = client { path, _ ->
            when {
                path.endsWith("/drive/v1/share") -> respondJson(
                    """{"share_status":"OK","pass_code_token":"T","title":"probe","files":[{"kind":"drive#folder","id":"D1","name":"probe"}],"next_page_token":""}""",
                )
                path.endsWith("/share/detail") -> respondJson(
                    """{"share_status":"OK","files":[{"kind":"drive#file","id":"F2","parent_id":"D1","name":"a.bin","size":"4096"}],"next_page_token":"","pass_code_token":"T"}""",
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val info = client.getShareInfo("S1", passCode = "mcbi")
        val page = client.listShareFiles("S1", info.passCodeToken, parentId = "D1")
        assertEquals(listOf("F2"), page.files.map { it.id })
        val shareReads = requests.filter { it.url.encodedPath.contains("/drive/v1/share") }
        assertEquals(2, shareReads.size)
        shareReads.forEach { assertEquals(PikPakConstants.CLIENT_ID, it.headers["X-Client-Id"]) }
        assertEquals("mcbi", shareReads[0].url.parameters["pass_code"])
        assertEquals("T", shareReads[1].url.parameters["pass_code_token"])
        assertEquals("D1", shareReads[1].url.parameters["parent_id"])
        client.close()
    }

    @Test
    fun `restore names its destination and returns the task`() = runBlocking {
        val client = client { path, _ ->
            if (path.endsWith("/share/restore")) respondJson(
                """{"share_status":"OK","share_status_text":"","file_id":"","restore_status":"RESTORE_START","restore_task_id":"TK","params":{}}""",
            ) else respond("", HttpStatusCode.NotFound)
        }
        val result = client.restoreShare("S1", "T", listOf("F2"), toParentId = "DEST", ancestorIds = listOf("D1"))
        assertEquals("TK", result.restoreTaskId)
        val body = bodyOf("/share/restore")
        assertEquals("DEST", body["parent_id"]!!.jsonPrimitive.content)
        assertEquals("true", body["specify_parent_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("D1"), body["ancestor_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        client.close()
    }

    @Test
    fun `restoring your own share is not taken for a captcha`() = runBlocking {
        // Recorded 2026-09-24. Same error_code as a captcha failure.
        val client = client { path, _ ->
            if (path.endsWith("/share/restore")) respond(
                content = ByteReadChannel(
                    """{"error":"file_restore_own","error_code":9,"error_url":"","error_description":"You don't need to restore your own files or folders","error_details":[]}""",
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            ) else respond("", HttpStatusCode.NotFound)
        }
        val e = assertFailsWith<PikPakException> { client.restoreShare("S1", "T", listOf("F1")) }
        assertEquals("file_restore_own", e.errorMessage)
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("/share/restore") })
        client.close()
    }

    @Test
    fun `deleting shares posts their ids and skips an empty list`() = runBlocking {
        val client = client { path, _ ->
            if (path.endsWith("/share:batchDelete")) respondJson("{}") else respond("", HttpStatusCode.NotFound)
        }
        client.deleteShares(emptyList())
        assertTrue(requests.none { it.url.encodedPath.endsWith("/share:batchDelete") })
        client.deleteShares(listOf("S1", "S2"))
        assertEquals(listOf("S1", "S2"), bodyOf("/share:batchDelete")["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        client.close()
    }

    @Test
    fun `share id from a link`() {
        assertEquals("VP2J59n2xU-aH3jyvst_a1UPo2", shareIdFromUrl("https://mypikpak.com/s/VP2J59n2xU-aH3jyvst_a1UPo2"))
        assertEquals("VOab_c", shareIdFromUrl(" https://mypikpak.com/s/VOab_c/VOfolder?act=play "))
        assertNull(shareIdFromUrl("https://example.com/s/VOab_c"))
    }

    private fun bodyOf(pathSuffix: String): JsonObject {
        val request = requests.last { it.url.encodedPath.endsWith(pathSuffix) }
        return Json.parseToJsonElement((request.body as TextContent).text).jsonObject
    }

    private fun client(
        handler: MockRequestHandleScope.(path: String, request: HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            requests += req
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> handler(path, req)
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
