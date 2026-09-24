package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileOpsMockTest {

    private class Sent(val method: String, val path: String, val query: String, val body: String)

    private val sent = mutableListOf<Sent>()

    private fun client(respondWith: (Sent) -> String = { "{}" }): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/v1/shield/captcha/init") -> """{"captcha_token":"CAP"}"""
                path.endsWith("/v1/auth/signin") ->
                    """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}"""
                else -> {
                    val text = (req.body as? TextContent)?.text ?: ""
                    val s = Sent(req.method.value, path, req.url.encodedQuery.decodeURLQueryComponent(), text)
                    sent += s
                    respondWith(s)
                }
            }
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
    }

    private fun ids(body: String) = Json.parseToJsonElement(body).jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `star and unstar post the ids to their own paths`() = runBlocking {
        val c = client()
        c.starFiles(listOf("A", "B"))
        c.unstarFiles(listOf("A"))
        c.starFiles(emptyList())
        assertEquals(listOf("/drive/v1/files:star", "/drive/v1/files:unstar"), sent.map { it.path })
        assertTrue(sent.all { it.method == "POST" })
        assertEquals(listOf("A", "B"), ids(sent[0].body))
        c.close()
    }

    @Test
    fun `listStarred asks every parent with the STAR system tag and keeps trashed out`() = runBlocking {
        val c = client { """{"files":[{"id":"F","name":"x","kind":"drive#file"}],"next_page_token":""}""" }
        val files = c.listStarred()
        assertEquals(listOf("F"), files.map { it.id })
        val q = sent.single().query
        assertTrue("parent_id=*" in q, q)
        val filters = Json.parseToJsonElement(q.substringAfter("filters=").substringBefore("&")).jsonObject
        assertEquals("STAR", filters["system_tag"]!!.jsonObject["in"]!!.jsonPrimitive.content)
        assertEquals("false", filters["trashed"]!!.jsonObject["eq"]!!.jsonPrimitive.content)
        c.close()
    }

    @Test
    fun `batchCopy returns one task id per chunk of a hundred`() = runBlocking {
        var n = 0
        val c = client { """{"task_id":"T${n++}"}""" }
        val all = (1..150).map { "id$it" }
        val tasks = c.batchCopy(all, "DEST")
        assertEquals(listOf("T0", "T1"), tasks)
        assertEquals(all, sent.flatMap { ids(it.body) })
        val first = Json.parseToJsonElement(sent[0].body).jsonObject
        assertEquals("DEST", first["to"]!!.jsonObject["parent_id"]!!.jsonPrimitive.content)
        assertEquals("/drive/v1/files:batchCopy", sent[0].path)
        assertTrue("unzip_password" !in sent[0].query)
        c.close()
    }

    @Test
    fun `batchCopy sends the unzip password only when given`() = runBlocking {
        val c = client { """{"task_id":"T"}""" }
        c.batchCopy(listOf("A"), "", unzipPassword = "pw 1")
        assertEquals("unzip_password=pw 1", sent.single().query)
        c.close()
    }

    @Test
    fun `batchCopy without a task id is an error`() = runBlocking {
        val c = client { "{}" }
        assertFailsWith<PikPakException> { c.batchCopy(listOf("A"), "") }
        c.close()
    }

    @Test
    fun `emptyTrash is a bodiless PATCH`() = runBlocking {
        val c = client()
        c.emptyTrash()
        val s = sent.single()
        assertEquals("PATCH", s.method)
        assertEquals("/drive/v1/files/trash:empty", s.path)
        assertEquals("", s.body)
        c.close()
    }

    @Test
    fun `clearOfflineTasks posts phases and delete_files for every client`() = runBlocking {
        val c = client()
        c.clearOfflineTasks(listOf(TaskPhase.COMPLETE), deleteFiles = true)
        val s = sent.single()
        assertEquals("POST", s.method)
        assertEquals("/drive/v1/tasks:clear", s.path)
        val body = Json.parseToJsonElement(s.body).jsonObject
        assertEquals("offline", body["type"]!!.jsonPrimitive.content)
        assertEquals(listOf(TaskPhase.COMPLETE), body["phases"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("true", body["delete_files"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { c.clearOfflineTasks(emptyList()) }
        c.close()
    }
}
