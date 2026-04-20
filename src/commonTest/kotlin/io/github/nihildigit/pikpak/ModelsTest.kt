package io.github.nihildigit.pikpak

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `FileStat folder helpers`() {
        val folder = FileStat(kind = FileKind.FOLDER, id = "x", name = "y")
        assertTrue(folder.isFolder)
        assertFalse(folder.isFile)
    }

    @Test
    fun `FileStat file helpers`() {
        val file = FileStat(kind = FileKind.FILE, id = "x", name = "y")
        assertTrue(file.isFile)
        assertFalse(file.isFolder)
    }

    @Test
    fun `FileStat sizeBytes parses string size`() {
        assertEquals(12345L, FileStat(size = "12345").sizeBytes)
    }

    @Test
    fun `FileStat sizeBytes defaults to zero on empty or invalid`() {
        assertEquals(0L, FileStat(size = "").sizeBytes)
        assertEquals(0L, FileStat(size = "not a number").sizeBytes)
    }

    @Test
    fun `FileStat decodes real listing snippet with unknown fields`() {
        val payload = """
            {
              "kind": "drive#file",
              "id": "FID",
              "name": "hello.txt",
              "size": "5",
              "parent_id": "PID",
              "something_future": "ignored"
            }
        """.trimIndent()
        val stat = json.decodeFromString(FileStat.serializer(), payload)
        assertEquals("FID", stat.id)
        assertEquals("hello.txt", stat.name)
        assertEquals("PID", stat.parentId)
        assertEquals(5L, stat.sizeBytes)
        assertTrue(stat.isFile)
    }

    @Test
    fun `FileDetail downloadUrl extracts link when present`() {
        val payload = """
            {
              "kind":"drive#file","id":"X","name":"f",
              "size":"100","links":{
                "application/octet-stream":{"url":"https://cdn/x","token":"t","expire":"later"}
              }
            }
        """.trimIndent()
        val d = json.decodeFromString(FileDetail.serializer(), payload)
        assertEquals("https://cdn/x", d.downloadUrl)
        assertEquals(100L, d.sizeBytes)
    }

    @Test
    fun `FileDetail downloadUrl null when octet-stream blank`() {
        val payload = """{"kind":"drive#file","id":"X","name":"f","size":"0"}"""
        val d = json.decodeFromString(FileDetail.serializer(), payload)
        assertNull(d.downloadUrl)
    }

    @Test
    fun `QuotaInfo remaining is limit minus usage`() {
        val q = QuotaInfo(limit = "1000", usage = "300")
        assertEquals(1000L, q.limitBytes)
        assertEquals(300L, q.usageBytes)
        assertEquals(700L, q.remainingBytes)
    }

    @Test
    fun `QuotaResponse decodes with nested quota`() {
        val payload = """
            {
              "kind":"drive#about",
              "quota":{"kind":"drive#quota","limit":"2000","usage":"100","usage_in_trash":"0"},
              "expires_at":"never"
            }
        """.trimIndent()
        val r = json.decodeFromString(QuotaResponse.serializer(), payload)
        assertEquals(2000L, r.quota.limitBytes)
        assertEquals(100L, r.quota.usageBytes)
        assertEquals(1900L, r.quota.remainingBytes)
    }

    @Test
    fun `FileListPage decodes empty listing`() {
        val r = json.decodeFromString(FileListPage.serializer(), """{"files":[]}""")
        assertEquals(0, r.files.size)
        assertEquals("", r.nextPageToken)
    }

    @Test
    fun `Session roundtrips with snake_case field names`() {
        val s = Session(accessToken = "AT", refreshToken = "RT", sub = "SUB", expiresAt = 1_700_000_000L)
        val text = json.encodeToString(Session.serializer(), s)
        assertTrue("access_token" in text)
        assertTrue("refresh_token" in text)
        assertTrue("expires_at" in text)
        val back = json.decodeFromString(Session.serializer(), text)
        assertEquals(s, back)
    }
}
