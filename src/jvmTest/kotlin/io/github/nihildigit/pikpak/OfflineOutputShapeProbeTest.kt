package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * How an offline download of a magnet lands in the drive, compared with the
 * paths [resolveMagnet] reports for the same magnet. A caller that downloads a
 * whole torrent and then deletes what the user did not pick has to map one onto
 * the other, and neither side documents its shape.
 *
 * The first test is read-only: it inspects the account's most recent completed
 * magnet tasks. The second submits PIKPAK_PROBE_MAGNET and deletes what it made.
 * Opt in with PIKPAK_PROBE=1.
 */
class OfflineOutputShapeProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `compare offline output with resolved magnet paths`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val tasks = client.listOfflineTasks(phaseFilter = TaskPhase.COMPLETE, limit = 50, with = null).tasks
            .filter { it.params["url"].orEmpty().startsWith("magnet:") }
            .take(8)
        println("[probe] ${tasks.size} completed magnet tasks")

        for (task in tasks) {
            val magnet = task.params.getValue("url")
            println("[task] name=${task.name} fileName=${task.fileName} fileId=${task.fileId} size=${task.fileSize}")
            val root = runCatching { client.getFile(task.fileId) }.getOrElse {
                println("  output gone: ${it.message}")
                continue
            }
            println("  root kind=${root.kind} name=${root.name}")
            val output = if (root.kind == FileKind.FOLDER) walk(client, task.fileId, "") else listOf(root.name to root.sizeBytes)
            val resolved = client.resolveMagnet(magnet)?.files?.map { it.path to it.size }
            if (resolved == null) {
                println("  resolveMagnet: index miss")
                continue
            }
            val outPaths = output.map { it.first }.toSet()
            val resPaths = resolved.map { it.first }.toSet()
            println("  output=${outPaths.size} resolved=${resPaths.size} equal=${outPaths == resPaths}")
            (resPaths - outPaths).take(5).forEach { println("  only in resolved: $it") }
            (outPaths - resPaths).take(5).forEach { println("  only in output:   $it") }
            output.take(3).forEach { println("  sample output: ${it.first}") }
            resolved.take(3).forEach { println("  sample resolved: ${it.first}") }
        }
    }

    /**
     * Submits PIKPAK_PROBE_MAGNET as a fresh offline task, waits for it, compares
     * the output tree with [resolveMagnet], then permanently deletes the output
     * and the task. Pick a small torrent with a subfolder: the tasks already on
     * the account were all flat, so they say nothing about nesting.
     */
    @Test
    fun `offline a nested torrent and compare`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val magnet = env["PIKPAK_PROBE_MAGNET"] ?: System.getenv("PIKPAK_PROBE_MAGNET")
        Assumptions.assumeTrue(!magnet.isNullOrBlank(), "set PIKPAK_PROBE_MAGNET")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val resolved = client.resolveMagnet(magnet!!)
        println("[probe] resolved name=${resolved?.name} files=${resolved?.files?.size}")
        resolved?.files?.forEach { println("  resolved: ${it.path} ${it.size}") }

        val queued = client.createUrlFile(parentId = "", url = magnet) as CreateUrlResult.Queued
        var task = queued.task
        println("[probe] task=${task.id} phase=${task.phase}")
        try {
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (task.phase != TaskPhase.COMPLETE && task.phase != TaskPhase.ERROR && System.currentTimeMillis() < deadline) {
                delay(3_000)
                task = client.getTask(task.id)
            }
            println("[probe] final phase=${task.phase} message=${task.message} fileId=${task.fileId} fileName=${task.fileName}")
            if (task.phase != TaskPhase.COMPLETE) return@runBlocking

            val root = client.getFile(task.fileId)
            println("[probe] root kind=${root.kind} name=${root.name}")
            val output = if (root.kind == FileKind.FOLDER) walk(client, task.fileId, "") else listOf(root.name to root.sizeBytes)
            output.forEach { println("  output: ${it.first} ${it.second}") }
            val outPaths = output.map { it.first }.toSet()
            val resPaths = resolved?.files?.map { it.path }?.toSet().orEmpty()
            println("[probe] equal=${outPaths == resPaths}")
            (resPaths - outPaths).forEach { println("  only in resolved: $it") }
            (outPaths - resPaths).forEach { println("  only in output: $it") }
        } finally {
            if (task.fileId.isNotEmpty()) {
                runCatching { client.batchDelete(listOf(task.fileId)) }
                    .onFailure { println("[cleanup] delete output failed: ${it.message}") }
            }
            runCatching { client.deleteOfflineTasks(listOf(task.id)) }
                .onFailure { println("[cleanup] delete task failed: ${it.message}") }
            println("[cleanup] done")
        }
    }

    private suspend fun walk(client: PikPakClient, folderId: String, prefix: String): List<Pair<String, Long>> =
        client.listFiles(folderId).flatMap { entry ->
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            if (entry.isFolder) walk(client, entry.id, path) else listOf(path to entry.sizeBytes)
        }
}
