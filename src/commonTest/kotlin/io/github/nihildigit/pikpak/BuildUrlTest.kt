package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildUrlTest {

    @Test
    fun `no query returns base joined with path`() {
        assertEquals(
            "https://api.example.com/drive/v1/files",
            buildUrl("https://api.example.com", "/drive/v1/files"),
        )
    }

    @Test
    fun `trailing slash on base and leading slash on path dedupe`() {
        assertEquals(
            "https://x.test/a/b",
            buildUrl("https://x.test/", "/a/b"),
        )
    }

    @Test
    fun `missing slashes are inserted`() {
        assertEquals(
            "https://x.test/a/b",
            buildUrl("https://x.test", "a/b"),
        )
    }

    @Test
    fun `query map appended with ampersands`() {
        val url = buildUrl(
            base = "https://x.test",
            path = "/list",
            query = mapOf("a" to "1", "b" to "2"),
        )
        // Map iteration order is insertion order for LinkedHashMap — but our
        // encoder just joins entries; assert a permissive shape.
        assertTrue(url.startsWith("https://x.test/list?"))
        assertTrue("a=1" in url)
        assertTrue("b=2" in url)
    }

    @Test
    fun `empty query map produces no question mark`() {
        val url = buildUrl("https://x.test", "/p", emptyMap())
        assertEquals("https://x.test/p", url)
    }

    @Test
    fun `query separator switches to ampersand when path already contains question mark`() {
        val url = buildUrl(
            base = "https://x.test",
            path = "/p?existing=1",
            query = mapOf("extra" to "2"),
        )
        assertEquals("https://x.test/p?existing=1&extra=2", url)
    }

    @Test
    fun `percent encodes reserved characters in values`() {
        val url = buildUrl(
            base = "https://x.test",
            path = "/p",
            query = mapOf("q" to """{"k":"v"}"""),
        )
        assertTrue("q=%7B%22k%22%3A%22v%22%7D" in url, "got: $url")
    }

    @Test
    fun `percent encoding preserves unreserved characters`() {
        val url = buildUrl(
            base = "https://x.test",
            path = "/p",
            query = mapOf("q" to "abcXYZ-_.~09"),
        )
        assertTrue("q=abcXYZ-_.~09" in url, "got: $url")
    }

    @Test
    fun `utf8 characters encode as percent bytes`() {
        val url = buildUrl(
            base = "https://x.test",
            path = "/p",
            query = mapOf("q" to "中"),
        )
        // UTF-8 encoding of 中: E4 B8 AD
        assertTrue("q=%E4%B8%AD" in url, "got: $url")
    }

    @Test
    fun `space encodes as percent 20`() {
        val url = buildUrl("https://x.test", "/p", mapOf("q" to "hello world"))
        assertTrue("q=hello%20world" in url, "got: $url")
    }
}
