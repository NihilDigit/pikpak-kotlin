package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions

/**
 * End-to-end run of the archive endpoints against the live account, confined
 * to a fresh `piko-probe-archive-<millis>` folder in the root that is
 * permanently deleted at the end. Nothing outside that folder is read or
 * written.
 *
 * The zip is built here with random contents, so the server has never seen
 * it: that is what shows the archive tree appearing only after the first
 * extraction. The password leg needs an encrypted archive, which the JDK
 * cannot write; point PIKPAK_PROBE_SECRET_ARCHIVE at one (for instance
 * `7z a -tzip -p<password> secret.zip a.txt`) and set
 * PIKPAK_PROBE_SECRET_PASSWORD, or it is skipped.
 *
 * Opt in with PIKPAK_PROBE=1.
 */
class ArchiveProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = setting("PIKPAK_PROBE") == "1"

    private fun setting(name: String): String? = env[name] ?: System.getenv(name)

    @Test
    fun `extract browse copy and pack inside a probe folder`() = runBlocking<Unit> {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())

        val work = kotlin.io.path.createTempDirectory("archive-probe").toFile()
        val a = Random.nextBytes(4000)
        val b = Random.nextBytes(2000)
        val zip = File(work, "plain.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a.bin")); out.write(a); out.closeEntry()
            out.putNextEntry(ZipEntry("sub/")); out.closeEntry()
            out.putNextEntry(ZipEntry("sub/b.bin")); out.write(b); out.closeEntry()
        }

        val probe = client.createFolder("", "piko-probe-archive-${System.currentTimeMillis()}")
        println("[probe] folder=$probe")
        try {
            val archiveId = client.upload(probe, Path(zip.absolutePath)).fileId
            val archive = client.getFile(archiveId)
            assertNull(archive.archiveTree, "a fresh upload has no tree")

            val top = client.listArchive(archiveId, archive.hash)
            println("[list] ${top.files.map { "${it.kind} ${it.path} ${it.size}" }}")
            val sub = top.files.single { it.isFolder }
            assertEquals(listOf("sub/b.bin"), client.listArchive(archiveId, archive.hash, sub.path).files.map { it.path })

            // Only the subfolder, into a folder of our own.
            val out = client.createFolder(probe, "out")
            val task = client.decompressArchive(archiveId, archive.hash, toParentId = out, paths = listOf(sub.path))
            println("[decompress] $task")
            val done = awaitDecompress(client, task.taskId)
            val created = client.listFiles(out).single()
            assertEquals(done.fileId, created.id)
            assertEquals("plain", created.name)
            val subOut = client.listFiles(created.id).single()
            assertEquals("sub", subOut.name)
            assertEquals(listOf("b.bin"), client.listFiles(subOut.id).map { it.name })

            val tree = assertNotNull(client.getFile(archiveId).archiveTree, "the tree appears after the first extraction")
            val entries = client.listArchiveTreePaged(tree).files
            println("[tree] ${entries.map { "${it.kind} ${it.name} ${it.size}" }}")
            val aEntry = entries.single { it.name == "a.bin" }
            val detail = client.getArchiveTreeFile(tree, aEntry.id)
            val fetched = File(work, "a.fetched")
            client.downloadSingleConnectionFromUrl(assertNotNull(detail.downloadUrl), Path(fetched.absolutePath))
            assertTrue(fetched.readBytes().contentEquals(a), "the entry's link serves the entry's bytes")

            val copied = client.createFolder(probe, "copied")
            val copyTask = client.copyFromArchiveTree(tree, listOf(aEntry.id), copied)
            println("[copy] task=$copyTask")
            assertEquals(listOf("a.bin"), awaitNames(client, copied))

            val packTask = client.packFolder(out)
            val packed = awaitTask(client, packTask)
            println("[pack] type=${packed.type} params=${packed.params}")
            val tar = client.getFile(out)
            assertEquals(FileKind.FILE, tar.kind)
            assertEquals("out.tar", tar.name)
            assertTrue(client.listFiles(out).isEmpty())
            client.unpackFolder(out)
            assertEquals(FileKind.FOLDER, client.getFile(out).kind)
            assertEquals(listOf(created.id), client.listFiles(out).map { it.id })

            val secretPath = setting("PIKPAK_PROBE_SECRET_ARCHIVE")
            val secretPassword = setting("PIKPAK_PROBE_SECRET_PASSWORD")
            if (secretPath != null && secretPassword != null) {
                val secretId = client.upload(probe, Path(secretPath)).fileId
                val secret = client.getFile(secretId)
                assertFalse(assertFailsWith<ArchivePasswordException> { client.listArchive(secretId, secret.hash) }.incorrect)
                assertTrue(assertFailsWith<ArchivePasswordException> { client.listArchive(secretId, secret.hash, password = "wrong") }.incorrect)
                assertTrue(client.listArchive(secretId, secret.hash, password = secretPassword).files.isNotEmpty())
            } else {
                println("[secret] skipped: set PIKPAK_PROBE_SECRET_ARCHIVE and PIKPAK_PROBE_SECRET_PASSWORD")
            }
        } finally {
            client.batchDelete(listOf(probe))
            println("[cleanup] probe folder gone: ${awaitGone(client, probe)}")
            work.deleteRecursively()
        }
    }

    private suspend fun awaitDecompress(client: PikPakClient, taskId: String): DecompressProgress {
        repeat(60) {
            val progress = client.getDecompressProgress(taskId)
            if (progress.phase == TaskPhase.COMPLETE) return progress
            check(progress.phase != TaskPhase.ERROR) { "decompress failed: ${progress.errorDescription}" }
            delay(1_000)
        }
        error("decompress $taskId did not finish in a minute")
    }

    private suspend fun awaitTask(client: PikPakClient, taskId: String): OfflineTask {
        repeat(60) {
            val task = client.getTask(taskId)
            if (task.phase == TaskPhase.COMPLETE) return task
            check(task.phase != TaskPhase.ERROR) { "task failed: ${task.message}" }
            delay(1_000)
        }
        error("task $taskId did not finish in a minute")
    }

    private suspend fun awaitNames(client: PikPakClient, folderId: String): List<String> {
        repeat(30) {
            val names = client.listFiles(folderId).map { it.name }
            if (names.isNotEmpty()) return names
            delay(1_000)
        }
        return emptyList()
    }

    /** A permanent delete of a folder is not immediate: it was still readable a moment after the call. */
    private suspend fun awaitGone(client: PikPakClient, id: String): Boolean {
        repeat(30) {
            try {
                client.getFile(id)
            } catch (e: PikPakException) {
                if (e.httpStatus == 404) return true
                throw e
            }
            delay(1_000)
        }
        return false
    }
}
