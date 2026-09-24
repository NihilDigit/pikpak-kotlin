package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventEndpointMockTest {

    private val requests = mutableListOf<HttpRequestData>()

    @Test
    fun `play history sends the type filter and decodes the resume position`() = runBlocking {
        // Recorded 2026-09-24, names and ids replaced. The token arrives percent-encoded.
        val body = """
            {"next_page_token":"2026-09-18T14%3A55%3A11.776%2B08%3A00","events":[
             {"kind":"drive#event","type":"TYPE_PLAY","type_name":"Play","id":"EV1",
              "created_time":"2026-09-20T00:15:44.615+08:00","updated_time":"2026-09-20T00:15:58.050+08:00",
              "mime_type":"video/x-matroska","file_id":"F1","file_name":"a.mkv","folder_id":"P1",
              "params":{"media_id":"B2A8","play_duration":"162","play_seconds":"115"},"progress":70,
              "reference_resource":{"@type":"type.googleapis.com/drive.ReferenceFile","kind":"drive#file","id":"F1",
               "parent_id":"P1","name":"a.mkv","size":"104602921","mime_type":"video/x-matroska",
               "hash":"C4339B198FA386D0249203EA2A8C4A92FB7971D9","audit":{"status":"STATUS_OK"},
               "params":{"duration":"163","width":"3840"},"medias":[],"tags":[]}}]}
        """.trimIndent()
        val client = client { req -> if (req.url.encodedPath.endsWith("/drive/v1/events")) respondJson(body) else null }

        val page = client.listPlayHistory(pageToken = "2026-09-20T00%3A00%3A00.000%2B08%3A00")

        val sent = requests.single { it.url.encodedPath.endsWith("/drive/v1/events") }
        assertEquals(HttpMethod.Get, sent.method)
        assertEquals("""{"type":{"in":"TYPE_PLAY"}}""", sent.url.parameters["filters"])
        // The token goes back exactly as received; the server accepts the double encoding on the wire.
        assertEquals("2026-09-20T00%3A00%3A00.000%2B08%3A00", sent.url.parameters["page_token"])
        assertEquals("2026-09-18T14%3A55%3A11.776%2B08%3A00", page.nextPageToken)
        val event = page.events.single()
        assertEquals(115L, event.playSeconds)
        assertEquals(162L, event.playDuration)
        assertEquals("F1", event.file?.id)
        assertEquals("163", event.file?.params?.get("duration"))
        client.close()
    }

    @Test
    fun `unfiltered listing sends no filter and several types join into one in list`() = runBlocking {
        val client = client { req -> if (req.url.encodedPath.endsWith("/drive/v1/events")) respondJson("""{"events":[]}""") else null }

        client.listEvents()
        client.listEvents(listOf(EventType.PLAY, EventType.UPLOAD))

        val (plain, both) = requests.filter { it.url.encodedPath.endsWith("/drive/v1/events") }
        assertNull(plain.url.parameters["filters"])
        assertEquals("""{"type":{"in":"TYPE_PLAY,TYPE_UPLOAD"}}""", both.url.parameters["filters"])
        client.close()
    }

    @Test
    fun `mutations post the bodies the web client sends`() = runBlocking {
        val client = client { req -> if (req.url.encodedPath.contains("/drive/v1/events")) respondJson("{}") else null }

        client.reportPlay("F1", positionSeconds = 115, durationSeconds = 162)
        client.deleteEvents(listOf("EV1", "EV2"))
        client.clearEvents(listOf(EventType.PLAY))
        client.deleteEvents(emptyList())
        client.clearEvents(emptyList())

        val posts = requests.filter { it.url.encodedPath.contains("/drive/v1/events") }
        assertTrue(posts.all { it.method == HttpMethod.Post })
        assertEquals(
            listOf("/drive/v1/events", "/drive/v1/events:delete", "/drive/v1/events:clear"),
            posts.map { it.url.encodedPath },
        )
        // Seconds are strings on the wire, as the web client sends them.
        assertEquals(
            json("""{"event":{"type":"TYPE_PLAY","file_id":"F1","params":{"play_duration":"162","play_seconds":"115"}}}"""),
            json(posts[0].bodyText()),
        )
        assertEquals(json("""{"ids":["EV1","EV2"]}"""), json(posts[1].bodyText()))
        assertEquals(json("""{"types":["TYPE_PLAY"]}"""), json(posts[2].bodyText()))
        client.close()
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun HttpRequestData.bodyText(): String = (body as? TextContent)?.text.orEmpty()

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData?): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> {
                    requests += req
                    handler(req) ?: respond("", HttpStatusCode.NotFound)
                }
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
