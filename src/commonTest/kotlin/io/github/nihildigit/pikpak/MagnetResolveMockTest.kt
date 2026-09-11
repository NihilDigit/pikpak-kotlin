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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The resource-list tree walk and the hash-only create. The response bodies
 * below are trimmed copies of live ones — in particular `file_size` is a
 * string and a directory's children arrive inlined under `dir.resources`
 * rather than behind a cursor.
 */
class MagnetResolveMockTest {

    private val parser = Json { ignoreUnknownKeys = true }

    // --- the tree walk ---

    @Test
    fun `a single-file torrent resolves to one file with its gcid`() = runBlocking<Unit> {
        val client = clientWith { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/resource/list")) {
                respondJson(singleFileResponse)
            } else {
                respond404()
            }
        }
        val resource = client.resolveMagnet("magnet:?xt=urn:btih:157e0a57")
            ?: error("a hit must not resolve to null")

        assertEquals("archlinux-2026.04.01-x86_64.iso", resource.name)
        assertEquals(1, resource.files.size)
        val file = resource.files.single()
        assertEquals("archlinux-2026.04.01-x86_64.iso", file.path)
        assertEquals("archlinux-2026.04.01-x86_64.iso", file.name)
        assertEquals("A3C62CB153B61CF265298986AD97EA07A85112DF", file.gcid)
        // file_size is quoted in the wire format; a naive decode leaves it a String.
        assertEquals(1_536_851_968L, file.size)
        client.close()
    }

    @Test
    fun `nested directories become slash-separated paths`() {
        val resource = parse(nestedResponse) ?: error("a hit must not resolve to null")

        assertEquals("Show S01", resource.name)
        assertEquals(
            listOf("E01.mkv", "specials/S00E01.mkv", "specials/extras/nfo.txt"),
            resource.files.map { it.path },
        )
        // The root folder names the torrent and is not a path segment, so a
        // file at the root comes out shaped like a single-file torrent's.
        assertEquals("E01.mkv", resource.files.first().name)
        assertEquals("S00E01.mkv", resource.files[1].name)
    }

    @Test
    fun `an unindexed file keeps its entry with a null gcid`() {
        val resource = parse(nestedResponse) ?: error("a hit must not resolve to null")
        val unindexed = resource.files.single { it.path == "specials/extras/nfo.txt" }
        assertNull(unindexed.gcid, "an empty meta.hash is a null gcid")
        assertEquals(3, resource.files.size, "unindexed entries stay in the tree the caller matches against")
    }

    @Test
    fun `a torrent PikPak has never seen resolves to null`() {
        // The miss shape: one placeholder named after the info hash, size zero,
        // empty meta.hash and no status field at all.
        assertNull(parse(missResponse))
    }

    @Test
    fun `an empty resource list resolves to null`() {
        assertNull(parse("""{"list_id":"L","list":{"page_size":500,"resources":[]}}"""))
    }

    @Test
    fun `the depth cap stops the walk instead of recursing forever`() {
        val resource = parseMagnetResource(parser.parseToJsonElement(nestedResponse) as JsonObject, maxDepth = 2)
            ?: error("entries above the cap must still resolve")
        assertEquals(
            listOf("E01.mkv", "specials/S00E01.mkv"),
            resource.files.map { it.path },
            "the contents of specials/extras sit below the cap and are dropped",
        )
    }

    // --- instantCreate ---

    @Test
    fun `a completed phase yields the new file id`() = runBlocking<Unit> {
        var body: String? = null
        val client = clientWith { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/files")) {
                body = req.body.asText()
                respondJson("""{"file":{"id":"NEWID","phase":"PHASE_TYPE_COMPLETE","name":"a.iso"}}""")
            } else {
                respond404()
            }
        }
        val file = ResolvedFile(path = "dir/a.iso", size = 42L, gcid = "AABB")
        assertEquals("NEWID", client.instantCreate(file, parentId = "PARENT"))

        val sent = body ?: error("request body was not captured")
        assertTrue("\"hash\":\"AABB\"" in sent, "the gcid must be sent as hash: $sent")
        // PikPak rejects a numeric size on this endpoint; it has to be quoted.
        assertTrue("\"size\":\"42\"" in sent, "size must be a string: $sent")
        assertTrue("\"name\":\"a.iso\"" in sent, "the default name is the leaf, not the path: $sent")
        assertTrue("\"parent_id\":\"PARENT\"" in sent, "parent must be sent: $sent")
        client.close()
    }

    @Test
    fun `a phase other than complete fails instead of returning an id`() = runBlocking<Unit> {
        val client = clientWith { req ->
            if (req.url.encodedPath.endsWith("/drive/v1/files")) {
                // PikPak wants the bytes, and a hash-only caller has none.
                respondJson(
                    """{"file":{"id":"PENDINGID","phase":"PHASE_TYPE_PENDING"},""" +
                        """"resumable":{"params":{"bucket":"b"}}}""",
                )
            } else {
                respond404()
            }
        }
        val e = assertFailsWith<PikPakException> {
            client.instantCreate(ResolvedFile("a.iso", 42L, "AABB"), parentId = "")
        }
        assertTrue(
            e.message!!.contains("PHASE_TYPE_PENDING"),
            "the failure must name the phase that was returned: ${e.message}",
        )
        client.close()
    }

    @Test
    fun `a file without a gcid is rejected before any request goes out`() = runBlocking<Unit> {
        val client = clientWith { respond404() }
        assertFailsWith<IllegalArgumentException> {
            client.instantCreate(ResolvedFile("a.iso", 42L, null), parentId = "")
        }
        client.close()
    }

    // --- fixtures ---

    private fun parse(body: String): MagnetResource? =
        parseMagnetResource(parser.parseToJsonElement(body) as JsonObject)

    private val singleFileResponse = """
        {"list_id":"NzSN10A0tw9KkDjJz8s0","list":{"page_size":500,"next_page_token":"","resources":[
         {"id":"kiGgmTBis2PGAotKZBn_SH0CxUE.0","name":"archlinux-2026.04.01-x86_64.iso",
          "file_size":"1536851968","file_count":1,"file_index":0,
          "meta":{"hash":"A3C62CB153B61CF265298986AD97EA07A85112DF",
                  "mime_type":"application/octet-stream","status":"1"},
          "is_dir":false,"dir":null,"parent_id":"","resolver":"DIRECT"}]}}
    """.trimIndent()

    private val nestedResponse = """
        {"list_id":"L","list":{"page_size":500,"resources":[
         {"id":"root","name":"Show S01","file_size":"300","file_count":3,"is_dir":true,
          "meta":{},"dir":{"resources":[
            {"id":"f1","name":"E01.mkv","file_size":"100","is_dir":false,"dir":null,
             "meta":{"hash":"1111111111111111111111111111111111111111"}},
            {"id":"d1","name":"specials","file_size":"200","is_dir":true,"meta":{},"dir":{"resources":[
              {"id":"f2","name":"S00E01.mkv","file_size":"200","is_dir":false,"dir":null,
               "meta":{"hash":"2222222222222222222222222222222222222222"}},
              {"id":"d2","name":"extras","file_size":"0","is_dir":true,"meta":{},"dir":{"resources":[
                {"id":"f3","name":"nfo.txt","file_size":"0","is_dir":false,"dir":null,
                 "meta":{"hash":""}}]}}]}}]}}]}}
    """.trimIndent()

    private val missResponse = """
        {"list_id":"L","list":{"page_size":500,"resources":[
         {"id":"x.0","name":"a1b2c3d4e5f60718293a4b5c6d7e8f9012345678","file_size":"0",
          "file_count":1,"file_index":0,"meta":{},"is_dir":false,"dir":null,"resolver":"DIRECT"}]}}
    """.trimIndent()

    private fun clientWith(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
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

    private suspend fun io.ktor.http.content.OutgoingContent.asText(): String =
        when (this) {
            is io.ktor.http.content.TextContent -> this.text
            is io.ktor.http.content.ByteArrayContent -> this.bytes().decodeToString()
            else -> ""
        }
}
