package io.github.nihildigit.pikpak

import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FileSessionStore persists through SystemFileSystem. On JVM this is java.io
 * under the hood — the test uses a scratch dir per test file and cleans up
 * after each run.
 */
class FileSessionStoreTest {

    private val cleanupTargets = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        cleanupTargets.forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
    }

    private fun scratchDir(): Path {
        val root = Path("build", "session-scratch", "run-${counter.getAndIncrement()}")
        if (!SystemFileSystem.exists(root)) SystemFileSystem.createDirectories(root)
        return root
    }

    private fun newStore(): Pair<FileSessionStore, Path> {
        val dir = scratchDir()
        return FileSessionStore(dir) to dir
    }

    private fun sampleSession() = Session(
        accessToken = "AT-xyz",
        refreshToken = "RT-xyz",
        sub = "USER-xyz",
        expiresAt = 1_700_000_000L,
    )

    @Test
    fun `save then load round-trips the session`() = runBlocking {
        val (store, _) = newStore()
        val s = sampleSession()
        store.save("alice@example.com", s)
        assertEquals(s, store.load("alice@example.com"))
    }

    @Test
    fun `load returns null when no file exists`() = runBlocking {
        val (store, _) = newStore()
        assertNull(store.load("ghost@example.com"))
    }

    @Test
    fun `clear removes the file`() = runBlocking {
        val (store, _) = newStore()
        store.save("bob@example.com", sampleSession())
        assertNotNull(store.load("bob@example.com"))
        store.clear("bob@example.com")
        assertNull(store.load("bob@example.com"))
    }

    @Test
    fun `file name hides the account email`() = runBlocking {
        val (store, dir) = newStore()
        store.save("secret-username@example.com", sampleSession())
        // Enumerate files and confirm the account is not substring-visible in any filename.
        val file = java.io.File(dir.toString())
        val names = file.list()?.toList().orEmpty()
        assertTrue(names.isNotEmpty())
        for (n in names) {
            assertTrue(
                "secret-username" !in n && "example.com" !in n,
                "filename leaks the account: $n",
            )
            assertTrue(n.startsWith("session_") && n.endsWith(".json"), "unexpected filename shape: $n")
        }
    }

    @Test
    fun `corrupted file yields null rather than throwing`() = runBlocking {
        val (store, dir) = newStore()
        val account = "brokeny@example.com"
        store.save(account, sampleSession())
        // Find the only file and smash it.
        val file = java.io.File(dir.toString()).listFiles()!!.first { it.name.endsWith(".json") }
        Path(file.absolutePath).let { p ->
            SystemFileSystem.sink(p).buffered().use { it.writeString("not-json-at-all {{") }
            cleanupTargets += p
        }
        assertNull(store.load(account), "corrupted JSON must decode to null, not throw")
    }

    @Test
    fun `different accounts use different files`() = runBlocking {
        val (store, dir) = newStore()
        store.save("a@x", sampleSession().copy(accessToken = "A"))
        store.save("b@x", sampleSession().copy(accessToken = "B"))
        val names = java.io.File(dir.toString()).list()?.toSet().orEmpty()
        assertEquals(2, names.size, "two accounts should produce two files, got $names")
        assertEquals("A", store.load("a@x")?.accessToken)
        assertEquals("B", store.load("b@x")?.accessToken)
    }

    @Test
    fun `save creates missing parent directory`() {
        runBlocking {
            val parent = Path("build", "session-scratch", "nested-${counter.getAndIncrement()}", "inner")
            val store = FileSessionStore(parent)
            store.save("new@x", sampleSession())
            assertTrue(SystemFileSystem.exists(parent), "ensureDir should have created the folder tree")
            assertNotNull(store.load("new@x"))
        }
    }

    companion object {
        private val counter = AtomicInteger(0)
    }
}
