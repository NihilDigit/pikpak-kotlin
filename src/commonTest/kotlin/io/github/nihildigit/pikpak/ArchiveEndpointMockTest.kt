package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveEndpointMockTest {

    private val tree = ArchiveTree(rootId = "G__ROOT", token = "TOKEN+/=")

    @Test
    fun `listArchive sends gcid and path and decodes entries`() = runBlocking<Unit> {
        // Recorded 2026-09-24 from a small zip, names replaced.
        val body = """
            {"status":"OK","status_text":"","task_id":"","current_path":"sub/","title":"x.zip","file_size":"5555",
             "gcid":"G1","files":[{"index":1,"filename":"b.txt","filesize":"2704","mime_type":"text/plain","gcid":"",
             "kind":"drive#file","icon_link":"","path":"sub/b.txt"}]}
        """.trimIndent()
        var sent: JsonObject? = null
        val client = client { req ->
            if (req.url.encodedPath == "/decompress/v1/list") {
                sent = req.jsonBody()
                respondJson(body)
            } else null
        }
        val listing = client.listArchive(fileId = "F1", gcid = "G1", path = "sub/", password = "pw")
        assertEquals(mapOf("gcid" to "G1", "path" to "sub/", "file_id" to "F1", "password" to "pw"), sent!!.strings())
        val entry = listing.files.single()
        assertEquals("sub/b.txt", entry.path)
        assertEquals("b.txt", entry.name)
        assertEquals(2704L, entry.sizeBytes)
        assertFalse(entry.isFolder)
        client.close()
    }

    @Test
    fun `password statuses in a 200 body become ArchivePasswordException`() = runBlocking<Unit> {
        var status = "PASS_WORD_EMPTY"
        val client = client { req ->
            if (req.url.encodedPath.startsWith("/decompress/v1/")) {
                respondJson("""{"status":"$status","status_text":"x","task_id":"","files":[]}""")
            } else null
        }
        val empty = assertFailsWith<ArchivePasswordException> { client.listArchive("F1", "G1") }
        assertFalse(empty.incorrect)
        status = "PASS_WORD_ERROR"
        val wrong = assertFailsWith<ArchivePasswordException> { client.decompressArchive("F1", "G1", toParentId = null, password = "no") }
        assertTrue(wrong.incorrect)
        status = "NEED_MORE_QUOTA"
        val quota = assertFailsWith<PikPakException> { client.decompressArchive("F1", "G1", toParentId = null) }
        assertFalse(quota is ArchivePasswordException)
        assertEquals("NEED_MORE_QUOTA", quota.errorMessage)
        client.close()
    }

    @Test
    fun `decompressArchive selects by path and targets the given folder`() = runBlocking<Unit> {
        val bodies = mutableListOf<JsonObject>()
        val client = client { req ->
            if (req.url.encodedPath == "/decompress/v1/decompress") {
                bodies += req.jsonBody()
                respondJson("""{"status":"OK","status_text":"","task_id":"T1","files_num":1,"redirect_link":""}""")
            } else null
        }
        val task = client.decompressArchive("F1", "G1", toParentId = "DST", paths = listOf("sub/"))
        assertEquals("T1", task.taskId)
        assertEquals(1, task.fileCount)
        client.decompressArchive("F1", "G1", toParentId = null)

        val explicit = bodies[0]
        assertFalse(explicit.getValue("default_parent").jsonPrimitive.boolean)
        assertEquals("DST", explicit.getValue("parent_id").jsonPrimitive.content)
        // Only the path: an entry without one makes the server extract everything.
        assertEquals(listOf(mapOf("path" to "sub/")), explicit.getValue("files").jsonArray.map { it.jsonObject.strings() })

        val beside = bodies[1]
        assertTrue(beside.getValue("default_parent").jsonPrimitive.boolean)
        assertNull(beside["parent_id"])
        assertEquals(JsonArray(emptyList()), beside["files"])
        client.close()
    }

    @Test
    fun `getDecompressProgress decodes a completed task`() = runBlocking<Unit> {
        var taskParam: String? = null
        val client = client { req ->
            if (req.url.encodedPath == "/decompress/v1/progress") {
                taskParam = req.url.parameters["task_id"]
                respondJson(
                    """{"progress":100,"expires_in":999,"phase":"PHASE_TYPE_COMPLETE","file_id":"OUT","file_name":"x.zip",
                       "file_size":"5555","gcid":"G1","task_size":"6757","task_size_completed":"6757","task_type":"0",
                       "decompress_path":["x"],"params":{}}""",
                )
            } else null
        }
        val progress = client.getDecompressProgress("T1")
        assertEquals("T1", taskParam)
        assertEquals(100, progress.progress)
        assertEquals(TaskPhase.COMPLETE, progress.phase)
        assertEquals("OUT", progress.fileId)
        client.close()
    }

    @Test
    fun `archiveTree needs both root and token in params`() {
        val params = mapOf("global_file_kind" to "1", "global_file_root" to "G__R", "global_file_token" to "T")
        assertEquals(ArchiveTree("G__R", "T"), FileStat(params = params).archiveTree)
        assertNull(FileStat(params = params - "global_file_token").archiveTree)
        assertNull(FileDetail(params = mapOf("platform" to "Upload")).archiveTree)
    }

    @Test
    fun `tree requests carry the token header and the password query`() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        val client = client { req ->
            val path = req.url.encodedPath
            when {
                path == "/drive/v1/files" -> {
                    seen += req
                    respondJson("""{"next_page_token":"","files":[{"kind":"drive#file","id":"G__A","parent_id":"G__ROOT","name":"a.txt","size":"4053","hash":"H"}]}""")
                }
                path == "/drive/v1/files/G__A" -> {
                    seen += req
                    respondJson("""{"kind":"drive#file","id":"G__A","links":{"application/octet-stream":{"url":"https://cdn/a","token":"","expire":""}}}""")
                }
                path.endsWith("/drive/v1/files:batchCopy") -> {
                    seen += req
                    respondJson("""{"task_id":"T2"}""")
                }
                else -> null
            }
        }
        val page = client.listArchiveTreePaged(tree, password = "pw")
        assertEquals("H", page.files.single().hash)
        val detail = client.getArchiveTreeFile(tree, "G__A", password = "pw")
        assertEquals("https://cdn/a", detail.downloadUrl)
        assertEquals("T2", client.copyFromArchiveTree(tree, listOf("G__A"), toParentId = "DST", password = "pw"))

        for (req in seen) {
            assertEquals("TOKEN+/=", req.headers["x-global-file-token"])
            assertEquals("pw", req.url.parameters["unzip_password"])
        }
        assertEquals("G__ROOT", seen[0].url.parameters["parent_id"])
        val copy = seen[2].jsonBody()
        assertEquals(listOf("G__A"), copy.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("DST", copy.getValue("to").jsonObject.getValue("parent_id").jsonPrimitive.content)
        client.close()
    }

    @Test
    fun `tree password errors become ArchivePasswordException`() = runBlocking<Unit> {
        var error = "unzip_password_required"
        val client = client { req ->
            if (req.url.encodedPath == "/drive/v1/files") {
                respondJson(
                    """{"error":"$error","error_code":9,"error_description":"x"}""",
                    HttpStatusCode.BadRequest,
                )
            } else null
        }
        val missing = assertFailsWith<ArchivePasswordException> { client.listArchiveTreePaged(tree) }
        assertFalse(missing.incorrect)
        error = "unzip_password_incorrect"
        assertTrue(assertFailsWith<ArchivePasswordException> { client.listArchiveTreePaged(tree, password = "no") }.incorrect)
        error = "global_file_token_invalid"
        val invalid = assertFailsWith<PikPakException> { client.listArchiveTreePaged(tree) }
        assertFalse(invalid is ArchivePasswordException)
        client.close()
    }

    @Test
    fun `pack and unpack send folderId and surface refusals`() = runBlocking<Unit> {
        val bodies = mutableListOf<Pair<String, JsonObject>>()
        val client = client { req ->
            val path = req.url.encodedPath
            if (path == "/drive/v1/files/pack" || path == "/drive/v1/files/unpack") {
                val body = req.jsonBody()
                bodies += path to body
                when {
                    body.getValue("folderId").jsonPrimitive.content == "EMPTY" -> respondJson(
                        """{"error":"cannot_pack_empty_folder","error_code":3,"error_description":"Cannot pack empty folder"}""",
                        HttpStatusCode.BadRequest,
                    )
                    path.endsWith("/pack") ->
                        respondJson("""{"task_id":"T3","gcid":"","root_id":"","file_count":"0","total_size":"0"}""")
                    else -> respondJson("""{"task_id":""}""")
                }
            } else null
        }
        assertEquals("T3", client.packFolder("D1"))
        client.unpackFolder("D1")
        assertEquals(
            listOf("/drive/v1/files/pack" to "D1", "/drive/v1/files/unpack" to "D1"),
            bodies.map { (path, body) -> path to body.getValue("folderId").jsonPrimitive.content },
        )
        val refused = assertFailsWith<PikPakException> { client.packFolder("EMPTY") }
        assertEquals("cannot_pack_empty_folder", refused.errorMessage)
        client.close()
    }

    private fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun JsonObject.strings(): Map<String, String> =
        mapValues { (it.value as JsonPrimitive).content }

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> handler(req) ?: respond("", HttpStatusCode.NotFound)
            }
        }
        return PikPakClient(account = "mock@x", password = "pw", sessionStore = InMemorySessionStore(), httpClient = HttpClient(engine))
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
