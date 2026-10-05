package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions

/**
 * Whether one entry of a never-extracted archive can be read without leaving
 * anything in the drive: extract the archive's smallest entry into a scratch
 * folder only so the server builds the archive tree, delete what was
 * extracted, then fetch the wanted entry through the tree.
 *
 * Records rather than asserts, into `build/archive-lease-probe.txt`:
 *  - how long the one-entry extraction takes and when the tree appears;
 *  - whether the tree and its token survive moving the extracted folder to the
 *    trash, and then deleting it for good;
 *  - whether the entry's link answers a Range request with 206;
 *  - storage usage and the daily cloud-download count before and after.
 *
 * Runs on the premium account only. Everything happens in a fresh
 * `piko-probe-lease-<millis>` folder in the root, which goes to the trash at
 * the end. Opt in with PIKPAK_PROBE=1.
 */
class ArchiveLeaseProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private val report = StringBuilder()

    private fun log(line: String) {
        println("[lease] $line")
        report.appendLine(line)
    }

    @Test
    fun `read one entry of a fresh archive through a throwaway extraction`() = runBlocking<Unit> {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())

        val work = kotlin.io.path.createTempDirectory("lease-probe").toFile()
        // Stored, not deflated: random bytes do not compress, and a stored
        // entry keeps the archive the same size as its content.
        val big = Random.nextBytes(3 * 1024 * 1024)
        val zip = File(work, "lease.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putStored("tiny.txt", "piko".encodeToByteArray())
            out.putStored("big.bin", big)
            out.putNextEntry(ZipEntry("sub/")); out.closeEntry()
            out.putStored("sub/c.bin", Random.nextBytes(1024))
        }

        val probe = client.createFolder("", "piko-probe-lease-${System.currentTimeMillis()}")
        log("probe folder=$probe")
        try {
            val archiveId = client.upload(probe, Path(zip.absolutePath)).fileId
            // A file still PENDING right after upload is refused by listArchive as invalid_argument.
            var archive = client.getFile(archiveId)
            repeat(30) {
                if (archive.phase == TaskPhase.COMPLETE) return@repeat
                delay(1_000)
                archive = client.getFile(archiveId)
            }
            log("fresh upload: phase=${archive.phase} hash=${archive.hash.take(8)} size=${archive.size} has tree: ${archive.archiveTree != null}")

            val top = client.listArchive(archiveId, archive.hash)
            log("top level: ${top.files.map { "${it.kind} ${it.path} ${it.sizeBytes}" }}")
            val smallest = top.files.filter { !it.isFolder }.minBy { it.sizeBytes }
            log("priming with ${smallest.path} (${smallest.sizeBytes} B)")

            val before = client.getQuota()
            log("before: usage=${before.quota.usageBytes} trash=${before.quota.usageInTrash} cloud=${before.quotas.cloudDownload.usage}")

            val scratch = client.createFolder(probe, "scratch")
            val started = System.currentTimeMillis()
            val task = client.decompressArchive(archiveId, archive.hash, toParentId = scratch, paths = listOf(smallest.path))
            val done = awaitDecompress(client, task.taskId)
            log("one-entry extraction took ${System.currentTimeMillis() - started} ms, files=${task.fileCount}, output=${done.fileId}")

            var tree = awaitTree(client, archiveId, "after extracting ${smallest.path}", attempts = 5)
            if (tree == null) {
                // Some runs got no tree from the first extraction; is a second one of the same entry enough?
                val again = client.decompressArchive(archiveId, archive.hash, toParentId = scratch, paths = listOf(smallest.path))
                awaitDecompress(client, again.taskId)
                tree = awaitTree(client, archiveId, "after extracting ${smallest.path} again", attempts = 5)
            }
            if (tree == null) {
                // 2026-09-24 the tree appeared after extracting a folder entry; compare.
                val folder = top.files.first { it.isFolder }
                val second = client.decompressArchive(archiveId, archive.hash, toParentId = scratch, paths = listOf(folder.path))
                awaitDecompress(client, second.taskId)
                tree = awaitTree(client, archiveId, "after extracting ${folder.path}")
            }
            checkNotNull(tree) { "no tree after extraction" }

            val afterExtract = client.getQuota()
            log("after extract: usage=${afterExtract.quota.usageBytes} trash=${afterExtract.quota.usageInTrash} cloud=${afterExtract.quotas.cloudDownload.usage}")

            client.batchTrash(listOf(done.fileId))
            log("extracted folder trashed")
            readBig(client, archiveId, big, "after trash")

            client.batchDelete(listOf(done.fileId))
            log("extracted folder deleted for good: ${awaitGone(client, done.fileId)}")
            readBig(client, archiveId, big, "after delete")
            log("token unchanged since priming: ${client.getFile(archiveId).archiveTree?.token == tree!!.token}")

            // Could an entry instead go through the vault's lease path, an instant create by gcid?
            // big.bin was never extracted, so its content exists only inside the archive.
            val relisted = client.listArchive(archiveId, archive.hash).files
            log("listArchive gcids now: ${relisted.map { "${it.path}=${it.gcid.take(8).ifEmpty { "-" }}" }}")
            val treeGcid = client.listArchiveTreePaged(client.getFile(archiveId).archiveTree!!).files.single { it.name == "big.bin" }.hash
            val bigGcid = relisted.single { it.path == "big.bin" }.gcid.ifEmpty { treeGcid }
            log("big.bin gcid from list=${relisted.single { it.path == "big.bin" }.gcid.take(8)} from tree=${treeGcid.take(8)}")
            val created = runCatching { client.instantCreate(ResolvedFile("big.bin", big.size.toLong(), bigGcid), probe) }
                .onFailure { log("instant create by gcid failed: $it") }
                .getOrNull()
            if (created != null) {
                val url = client.getFile(created).downloadUrl
                val bytes = url?.let {
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(it)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body()
                }
                log("instant create by gcid: id=$created bytes match=${bytes?.contentEquals(big)}")
            }

            val after = client.getQuota()
            log("after delete: usage=${after.quota.usageBytes} trash=${after.quota.usageInTrash} cloud=${after.quotas.cloudDownload.usage}")
        } catch (e: Throwable) {
            log("failed: $e")
            throw e
        } finally {
            client.batchTrash(listOf(probe))
            log("probe folder trashed")
            work.deleteRecursively()
            File("build").mkdirs()
            File("build/archive-lease-probe.txt").writeText(report.toString())
        }
    }

    /** Re-reads the tree from the archive itself, as the app would, and reads a slice of big.bin through a Range request. */
    private suspend fun readBig(client: PikPakClient, archiveId: String, big: ByteArray, stage: String) {
        val tree = client.getFile(archiveId).archiveTree
        if (tree == null) {
            log("$stage: tree gone")
            return
        }
        val entries = runCatching { client.listArchiveTreePaged(tree).files }
            .onFailure { log("$stage: listing the tree failed: $it") }
            .getOrNull() ?: return
        log("$stage: tree lists ${entries.map { it.name }}")
        val entry = entries.single { it.name == "big.bin" }
        val url = runCatching { client.getArchiveTreeFile(tree, entry.id).downloadUrl }
            .onFailure { log("$stage: entry detail failed: $it") }
            .getOrNull()
        if (url == null) {
            log("$stage: no link")
            return
        }
        val from = 1_000_000
        val to = from + 999
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI(url)).header("Range", "bytes=$from-$to").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        val matches = response.body().contentEquals(big.copyOfRange(from, to + 1))
        log("$stage: range status=${response.statusCode()} content-range=${response.headers().firstValue("content-range").orElse("-")} bytes match=$matches host=${URI(url).host}")
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private suspend fun awaitDecompress(client: PikPakClient, taskId: String): DecompressProgress {
        repeat(120) {
            val progress = client.getDecompressProgress(taskId)
            if (progress.phase == TaskPhase.COMPLETE) return progress
            check(progress.phase != TaskPhase.ERROR) { "decompress failed: ${progress.errorDescription}" }
            delay(500)
        }
        error("decompress $taskId did not finish in a minute")
    }

    private suspend fun awaitTree(client: PikPakClient, archiveId: String, stage: String, attempts: Int = 45): ArchiveTree? {
        val start = System.currentTimeMillis()
        repeat(attempts) {
            val file = client.getFile(archiveId)
            file.archiveTree?.let {
                log("$stage: tree after ${System.currentTimeMillis() - start} ms")
                // An earlier run lost the tree on a later read; see whether reads disagree.
                val reads = List(3) { delay(1_000); client.getFile(archiveId).archiveTree != null }
                log("$stage: tree present on re-reads: $reads")
                return it
            }
            if (it == 0) log("$stage: params keys=${file.params.keys}")
            delay(2_000)
        }
        log("$stage: no tree within ${System.currentTimeMillis() - start} ms")
        return null
    }

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
