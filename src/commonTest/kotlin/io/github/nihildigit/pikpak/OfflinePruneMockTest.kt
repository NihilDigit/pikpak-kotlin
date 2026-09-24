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
import kotlin.test.assertTrue

class OfflinePruneMockTest {

    private val done = OfflineTask(id = "T", phase = TaskPhase.COMPLETE, fileId = "ROOT")

    // ROOT/
    //   01.mkv          keep
    //   notice.txt
    //   Scans/          nothing kept: deleted whole, never listed
    //     a.jpg
    //   Extras/
    //     NCOP.mkv      keep
    //     NCED.mkv
    private val tree = mapOf(
        "ROOT" to listOf(file("F01", "01.mkv"), file("FTXT", "notice.txt"), folder("DSCANS", "Scans"), folder("DEXTRAS", "Extras")),
        "DSCANS" to listOf(file("FJPG", "a.jpg")),
        "DEXTRAS" to listOf(file("FOP", "NCOP.mkv"), file("FED", "NCED.mkv")),
    )

    @Test
    fun `keeps picked paths, drops the rest, and deletes unpicked folders whole`() = runBlocking {
        val listed = mutableListOf<String>()
        val deleteBodies = mutableListOf<String>()
        val client = client { path, parentId, body ->
            when {
                path.endsWith("/drive/v1/files/ROOT") -> respondJson("""{"kind":"${FileKind.FOLDER}","id":"ROOT","name":"Pack"}""")
                path.endsWith("/drive/v1/files:batchDelete") -> {
                    deleteBodies += body
                    respondJson("{}")
                }
                path.endsWith("/drive/v1/files") -> {
                    listed += parentId
                    respondJson("""{"files":[${tree.getValue(parentId).joinToString(",")}],"next_page_token":""}""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val result = client.pruneOfflineOutput(done, keep = setOf("01.mkv", "Extras/NCOP.mkv", "Extras/gone.ass"))

        assertEquals(listOf("ROOT", "DEXTRAS"), listed, "an unpicked folder is deleted without being listed")
        assertEquals(listOf("notice.txt", "Scans/", "Extras/NCED.mkv"), result.deleted)
        assertEquals(setOf("Extras/gone.ass"), result.missing)
        val sent = deleteBodies.single()
        listOf("FTXT", "DSCANS", "FED").forEach { assertTrue("\"$it\"" in sent, sent) }
        listOf("F01", "FOP", "FJPG").forEach { assertTrue("\"$it\"" !in sent, sent) }
        client.close()
    }

    @Test
    fun `a single-file output is the file itself`() = runBlocking {
        var deleted = false
        val client = client { path, _, _ ->
            when {
                path.endsWith("/drive/v1/files/ROOT") -> respondJson("""{"kind":"${FileKind.FILE}","id":"ROOT","name":"ep.mkv"}""")
                path.endsWith("/drive/v1/files:batchDelete") -> {
                    deleted = true
                    respondJson("{}")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val result = client.pruneOfflineOutput(done, keep = setOf("ep.mkv"))
        assertEquals(emptyList(), result.deleted)
        assertTrue(!deleted)
        client.close()
    }

    private fun file(id: String, name: String) = """{"kind":"${FileKind.FILE}","id":"$id","name":"$name"}"""

    private fun folder(id: String, name: String) = """{"kind":"${FileKind.FOLDER}","id":"$id","name":"$name"}"""

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
