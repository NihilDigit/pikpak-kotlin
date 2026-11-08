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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
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

    @Test
    fun `emptyTrash patches the trash endpoint and invalidates folder ids`() = runBlocking<Unit> {
        val c = client()
        try {
            val generation = c.folderIds.generation()
            c.folderIds.put("", "folder", "F", generation)
            assertEquals("F", c.folderIds.get("", "folder"))
            c.emptyTrash()
            assertEquals("PATCH", sent.single().method)
            assertEquals("/drive/v1/files/trash:empty", sent.single().path)
            assertEquals(null, c.folderIds.get("", "folder"))
        } finally {
            c.close()
        }
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
    fun `batchCopy returns one task id per chunk of five hundred`() = runBlocking {
        var n = 0
        val c = client { """{"task_id":"T${n++}"}""" }
        val all = (1..501).map { "id$it" }
        val tasks = c.batchCopy(all, "DEST", awaitCompletion = false)
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
        c.batchCopy(listOf("A"), "", unzipPassword = "pw 1", awaitCompletion = false)
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
    fun `batch mutations wait for missing pending and running tasks before returning`() = runBlocking {
        var polls = 0
        val c = client {
            if (it.method == "POST") """{"task_id":"T","ids":["A"]}"""
            else when (polls++) {
                0 -> """{"tasks":[]}"""
                1 -> """{"tasks":[{"id":"T","phase":"PHASE_TYPE_PENDING"}]}"""
                2 -> """{"tasks":[{"id":"T","phase":"PHASE_TYPE_RUNNING"}]}"""
                else -> """{"tasks":[{"id":"T","phase":"PHASE_TYPE_COMPLETE"}]}"""
            }
        }
        try {
            c.batchMove(listOf("A"), "B")
            assertEquals(4, polls)
            assertTrue(sent.drop(1).all { it.path == "/drive/v1/tasks" && """"id":{"in":"T"}""" in it.query })
        } finally { c.close() }
    }

    @Test
    fun `task failures and missing task timeout are explicit`() = runBlocking {
        val failed = client { """{"id":"T","phase":"PHASE_TYPE_ERROR","tasks":[{"id":"T","phase":"PHASE_TYPE_ERROR"}]}""" }
        try {
            assertEquals("T", assertFailsWith<DriveTaskFailedException> {
                failed.awaitTasks(listOf("T"), pollIntervalMs = 1)
            }.task.id)
        } finally { failed.close() }
        val missing = client { """{"tasks":[]}""" }
        try {
            assertEquals(listOf("T"), assertFailsWith<DriveTasksTimeoutException> {
                missing.awaitTasks(listOf("T"), pollIntervalMs = 5, timeoutMs = 50)
            }.taskIds)
        } finally { missing.close() }
    }

    @Test
    fun `every batch mutation waits and submission receipts can be persisted`() = runBlocking {
        var submissions = 0
        var polls = 0
        val c = client {
            if (it.method == "POST") {
                submissions++
                """{"task_id":"T","ids":["A"]}"""
            } else {
                polls++
                """{"tasks":[{"id":"T","phase":"PHASE_TYPE_COMPLETE"}]}"""
            }
        }
        try {
            c.batchCopy(listOf("A"), "B")
            c.batchTrash(listOf("A"))
            c.batchUntrash(listOf("A"))
            c.batchDelete(listOf("A"))
            assertEquals(4, polls)
            val saved = mutableListOf<FileBatchReceipt>()
            val receipts = c.submitFileBatch((1..501).map { "A$it" }, FileBatchOperation.Trash) {
                assertEquals(4 + saved.size + 1, submissions)
                saved += it
            }
            assertEquals(2, receipts.size)
            assertEquals(receipts, saved)
            assertEquals(listOf("A"), receipts.first().ids)
            assertEquals(4, polls)
        } finally { c.close() }
    }

    @Test
    fun `task polling respects caller cancellation`() = runBlocking {
        val c = client { """{"tasks":[]}""" }
        try {
            val job = launch { c.awaitTasks(listOf("T"), pollIntervalMs = 10) }
            delay(50)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally { c.close() }
    }

    @Test
    fun `empty delta preserves server cursor and failed page callbacks abort checkpoint`() = runBlocking<Unit> {
        val c = client { """{"files":[],"sync_time":"SERVER"}""" }
        try {
            val result = c.syncFileChanges("BEFORE")
            assertTrue(result.upserted.isEmpty() && result.deletedIds.isEmpty())
            assertEquals("SERVER", result.syncTime)
            assertFailsWith<IllegalArgumentException> {
                c.consumeFileChanges("BEFORE") { throw IllegalArgumentException("database write failed") }
            }
        } finally { c.close() }
    }

    @Test
    fun `delta follows pages without hiding tombstones and checkpoints first page`() = runBlocking {
        val c = client {
            if ("page_token=" !in it.query)
                """{"files":[{"id":"A"},{"id":"B","trashed":true}],"sync_time":"FIRST","next_page_token":"NEXT"}"""
            else """{"files":[{"id":"B","trashed":false},{"id":"C","trashed":true}],"sync_time":"LAST"}"""
        }
        try {
            val result = c.syncFileChanges("SINCE")
            assertEquals(listOf("A", "B"), result.upserted.map { it.id })
            assertEquals(listOf("C"), result.deletedIds)
            assertEquals("FIRST", result.syncTime)
            assertEquals("LAST", result.checkpoint.lastSyncTime)
            assertTrue(sent.all { "with_audit=true" in it.query && "trashed" !in it.query && """"gt":"SINCE"""" in it.query })
        } finally { c.close() }
    }

    @Test
    fun `sync rejects expired versions missing watermark and repeated tokens`() = runBlocking<Unit> {
        val expired = client { """{"version_outdated":true,"sync_time":"S"}""" }
        try { assertFailsWith<FileSyncVersionOutdatedException> { expired.syncFileChanges("S") } }
        finally { expired.close() }
        val absent = client { """{"files":[]}""" }
        try { assertFailsWith<IllegalStateException> { absent.syncFileChanges("S") } }
        finally { absent.close() }
        val repeated = client { """{"sync_time":"S","next_page_token":"P"}""" }
        try { assertFailsWith<IllegalStateException> { repeated.syncFileChanges("S") } }
        finally { repeated.close() }
    }

    @Test
    fun `batchGet repeats and encodes ids and slices large requests`() = runBlocking {
        val c = client { """{"files":[{"id":"a+b"},{"id":"c&d"}]}""" }
        try {
            assertEquals(listOf("a+b", "c&d"), c.batchGetFiles(listOf("a+b", "c&d")).map { it.id })
            assertEquals("ids=a+b&ids=c&d", sent.single().query)
            sent.clear()
            c.batchGetFiles((1..101).map { "F$it" })
            assertEquals(2, sent.size)
            c.batchGetFiles(emptyList())
            assertEquals(2, sent.size)
        } finally { c.close() }
    }

    @Test
    fun `metadata includes wire stars tags database time and limited links`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """{"starred":false,"tags":[{"name":"STAR"}],"params":{"db_modify_time":"D"}}"""
        assertTrue(json.decodeFromString<FileStat>(raw).isStarred)
        assertTrue(json.decodeFromString<FileDetail>(raw).isStarred)
        assertTrue(FileStat(starred = true).isStarred)
        assertTrue(FileDetail(starred = true).isStarred)
        assertEquals("D", json.decodeFromString<FileDetail>(raw).dbModifyTime)
        assertTrue(DownloadLink(url = "https://cdn/f?x_limited=1&expire=1800000000").isLimited)
        assertTrue(DownloadLink(url = "https://cdn/f?expire=1800000000").expiresAt != null)
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
