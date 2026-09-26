package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Whether the decompress service reads multi-volume archives. Every group is
 * a subfolder of PIKPAK_PROBE_MULTIVOLUME_DIR holding the volumes of one
 * archive; all of them go into one drive folder, then each file named in
 * [targets] is listed and extracted. Groups whose name ends in `first` upload
 * only their first volume, to compare with the complete set.
 *
 * Everything lives in a fresh `piko-probe-multivolume-<millis>` folder in the
 * root, extraction output included, and that folder is permanently deleted at
 * the end. The root's other entries are compared before and after to catch
 * output landing elsewhere.
 *
 * Opt in with PIKPAK_PROBE=1.
 */
class MultiVolumeProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }

    private fun setting(name: String): String? = env[name] ?: System.getenv(name)

    /** Group folder to the volumes probed in it, first volume and zip main file. */
    private val targets = mapOf(
        "single" to listOf("single.7z"),
        "singlezip" to listOf("sz.zip"),
        "singlerar5" to listOf("sr5.rar"),
        "7zvol" to listOf("v7.7z.001"),
        "zip7vol" to listOf("vz.zip.001"),
        "zipspan" to listOf("zs.zip", "zs.z01"),
        "rar5vol" to listOf("r5.part1.rar", "r5.part2.rar"),
        "7zfirst" to listOf("f7.7z.001"),
        "rar5first" to listOf("f5.part1.rar"),
    )

    @Test
    fun `list and extract multi-volume archives`() = runBlocking<Unit> {
        Assumptions.assumeTrue(setting("PIKPAK_PROBE") == "1", "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val base = setting("PIKPAK_PROBE_MULTIVOLUME_DIR")?.let(::File)
        Assumptions.assumeTrue(base != null && base.isDirectory, "set PIKPAK_PROBE_MULTIVOLUME_DIR")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())

        val rootBefore = client.listFiles("").map { it.id }.toSet()
        val probe = client.createFolder("", "piko-probe-multivolume-${System.currentTimeMillis()}")
        println("[probe] folder=$probe")
        try {
            for ((group, names) in targets) {
                val dir = File(base, group)
                val volumes = dir.listFiles()!!.sortedBy { it.name }
                    .let { all -> if (group.endsWith("first")) all.take(1) else all }
                val folder = client.createFolder(probe, group)
                val ids = volumes.associate { it.name to client.upload(folder, Path(it.absolutePath)).fileId }
                println("\n===== $group uploaded ${ids.keys}")
                for ((name, id) in ids) {
                    val stat = awaitComplete(client, id)
                    println("[stat] $name size=${stat.size} mime=${stat.mimeType} ext=${stat.fileExtension} hash=${stat.hash} params=${stat.params}")
                }
                for (name in names) probeOne(client, group, name, ids.getValue(name), folder)
            }
            println("\n[root] new entries besides the probe folder: ${client.listFiles("").map { it.id }.toSet() - rootBefore - probe}")
        } finally {
            client.batchDelete(listOf(probe))
            println("[cleanup] probe folder gone: ${awaitGone(client, probe)}")
        }
    }

    private suspend fun probeOne(client: PikPakClient, group: String, name: String, id: String, folder: String) {
        println("\n--- $group / $name")
        val gcid = client.getFile(id).hash
        try {
            val listing = client.listArchive(id, gcid)
            println("[list] OK title=${listing.title} size=${listing.fileSize} files=${listing.files.map { "${it.kind} ${it.path} ${it.size}" }}")
        } catch (e: PikPakException) {
            println("[list] FAIL ${e::class.simpleName} code=${e.errorCode} msg=${e.errorMessage} desc=${e.errorDescription} http=${e.httpStatus} raw=${e.rawBody}")
        }

        val out = client.createFolder(folder, "out-${name.replace('.', '_')}")
        val task = try {
            client.decompressArchive(id, gcid, toParentId = out)
        } catch (e: PikPakException) {
            println("[decompress] REFUSED ${e::class.simpleName} code=${e.errorCode} msg=${e.errorMessage} desc=${e.errorDescription} http=${e.httpStatus} raw=${e.rawBody}")
            return
        }
        println("[decompress] accepted $task")
        var last: DecompressProgress? = null
        for (i in 0 until 120) {
            last = client.getDecompressProgress(task.taskId)
            if (last.phase == TaskPhase.COMPLETE || last.phase == TaskPhase.ERROR) break
            delay(1_000)
        }
        println("[progress] $last")
        try {
            val t = client.getTask(task.taskId)
            println("[task] phase=${t.phase} progress=${t.progress} message=${t.message} fileId=${t.fileId} params=${t.params}")
            if (t.fileId.isNotEmpty()) {
                val where = try {
                    client.getFile(t.fileId).let { "name=${it.name} kind=${it.kind} parent=${it.parentId} trashed=${it.trashed} phase=${it.phase}" }
                } catch (e: PikPakException) {
                    "FAIL http=${e.httpStatus} ${e.errorMessage}"
                }
                println("[task file] $where (out=$out)")
            }
        } catch (e: PikPakException) {
            println("[task] FAIL ${e.errorMessage} ${e.rawBody}")
        }
        println("[output]")
        dump(client, out, "  ")
    }

    private suspend fun dump(client: PikPakClient, folderId: String, indent: String) {
        for (f in client.listFiles(folderId)) {
            println("$indent${f.kind} ${f.name} size=${f.size} hash=${f.hash} phase=${f.phase}")
            if (f.kind == FileKind.FOLDER) dump(client, f.id, "$indent  ")
        }
    }

    /**
     * The decompress service answers `invalid_argument` with debug info
     * "file not complete" for an upload whose phase has not turned complete
     * yet, which a first run hit a few seconds after upload.
     */
    private suspend fun awaitComplete(client: PikPakClient, id: String): FileDetail {
        repeat(60) {
            val file = client.getFile(id)
            if (file.phase == TaskPhase.COMPLETE) return file
            delay(1_000)
        }
        error("$id did not complete in a minute")
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
