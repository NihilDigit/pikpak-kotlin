package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Probes the two offline-task mutations PikPak's API exposes but the SDK does
 * not: retry (`POST /drive/v1/task`, create_type RETRY) and delete
 * (`DELETE /drive/v1/tasks`). The shapes come from the community PikPakAPI
 * client; this checks them against the live server before the SDK commits to
 * them.
 *
 * Only touches tasks it creates itself, pointed at URLs that cannot resolve,
 * so no file lands in the drive. Opt in with PIKPAK_PROBE=1.
 */
class OfflineTaskMutationProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `retry and delete offline tasks`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val created = mutableListOf<String>()
        try {
            client.login()

            for (n in 1..2) {
                val url = "https://piko-probe-$n.invalid/never.bin"
                when (val r = client.createUrlFile(parentId = "", url = url)) {
                    is CreateUrlResult.Queued -> {
                        println("[create $n] id=${r.task.id} phase=${r.task.phase} message=${r.task.message}")
                        created += r.task.id
                    }
                    is CreateUrlResult.InstantComplete -> println("[create $n] instant, raw=${r.raw}")
                }
            }
            if (created.isEmpty()) return@runBlocking

            for (id in created) println("[settled] ${describe(awaitTerminal(client, id))}")

            val target = created.first()
            val retryRaw = runCatching {
                client.http.request(
                    method = HttpMethod.Post,
                    url = "${PikPakConstants.DRIVE_BASE}/drive/v1/task",
                    captchaAction = "POST:/drive/v1/task",
                ) {
                    jsonBody(client.json, buildJsonObject {
                        put("type", "offline")
                        put("create_type", "RETRY")
                        put("id", target)
                    })
                }
            }
            println("[retry] $retryRaw")
            println("[after retry, immediate] ${describe(client.getTask(target))}")
            println("[after retry, settled] ${describe(awaitTerminal(client, target))}")

            // httpx, which PikPakAPI uses, sends a list param as repeated keys
            val repeated = created.joinToString("&") { "task_ids=$it" }
            val deleteRaw = runCatching {
                client.http.request(
                    method = HttpMethod.Delete,
                    url = "${PikPakConstants.DRIVE_BASE}/drive/v1/tasks?$repeated&delete_files=false",
                    captchaAction = "DELETE:/drive/v1/tasks",
                )
            }
            println("[delete repeated keys] $deleteRaw")
            for (id in created) println("[after delete] $id -> ${runCatching { describe(client.getTask(id)) }}")

            val listed = client.listOfflineTasks(
                phaseFilter = listOf(TaskPhase.PENDING, TaskPhase.RUNNING, TaskPhase.COMPLETE, TaskPhase.ERROR).joinToString(","),
            ).tasks.filter { it.id in created }
            println("[still listed] ${listed.map { describe(it) }}")
        } finally {
            client.close()
        }
    }

    /**
     * Whether deleting a finished task's record with deleteFiles = false keeps
     * the file it produced. Downloads a few-KB public file, deletes the task,
     * reads the file back, then trashes it.
     */
    @Test
    fun `deleting a finished task keeps its file`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val url = "https://raw.githubusercontent.com/NihilDigit/piko/main/LICENSE"
            val queued = client.createUrlFile(parentId = "", url = url)
            println("[create] $queued")
            val taskId = (queued as? CreateUrlResult.Queued)?.task?.id ?: return@runBlocking
            val done = awaitTerminal(client, taskId)
            println("[settled] ${describe(done)}")
            if (done.phase != TaskPhase.COMPLETE) return@runBlocking

            println("[file before] ${runCatching { client.getFile(done.fileId).let { "name=${it.name} size=${it.size} trashed=${it.trashed} phase=${it.phase}" } }}")
            client.deleteOfflineTasks(listOf(taskId), deleteFiles = false)
            println("[task after delete] ${runCatching { describe(client.getTask(taskId)) }}")
            val after = runCatching { client.getFile(done.fileId) }
            println("[file after] ${after.map { "name=${it.name} size=${it.size} trashed=${it.trashed} phase=${it.phase}" }}")

            if (after.getOrNull()?.trashed == false) {
                client.deleteFile(done.fileId)
                println("[cleanup] trashed ${done.fileId}")
            }
        } finally {
            client.close()
        }
    }

    /**
     * The probe's tasks never failed; they sat in RUNNING with a placeholder
     * file, and deleting the task with delete_files=false may leave that file
     * behind. Trashes only placeholders whose source URL is the probe's own.
     */
    @Test
    fun `trash placeholder files left by the probe`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val fileIds = (System.getenv("PROBE_FILE_IDS") ?: "").split(',').filter { it.isNotBlank() }
        Assumptions.assumeTrue(fileIds.isNotEmpty(), "set PROBE_FILE_IDS")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            for (id in fileIds) {
                val detail = runCatching { client.getFile(id) }
                println("[placeholder] $id -> ${detail.map { "name=${it.name} parent=${it.parentId} trashed=${it.trashed} phase=${it.phase} url=${it.sourceUrl}" }}")
                val file = detail.getOrNull() ?: continue
                if (!file.trashed && file.sourceUrl.orEmpty().contains("piko-probe")) {
                    client.deleteFile(id)
                    println("[trashed] $id")
                }
            }
        } finally {
            client.close()
        }
    }

    /** Read-only: the account's errored tasks as the listing and the single-task endpoint see them. */
    @Test
    fun `list errored tasks`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val all = client.listOfflineTasks(
                phaseFilter = listOf(TaskPhase.PENDING, TaskPhase.RUNNING, TaskPhase.COMPLETE, TaskPhase.ERROR).joinToString(","),
                limit = 50,
            ).tasks
            println("[as piko lists] " + all.groupingBy { "${it.phase}/${it.message}" }.eachCount())
            val errored = all.filter { it.phase == TaskPhase.ERROR }
            println("[errored] ${errored.size}")
            for (t in errored) {
                val single = runCatching { client.getTask(t.id) }.map { "${it.phase}/${it.message}" }
                println("[task] id=${t.id} size=${t.fileSize} message=${t.message} single=$single updated=${t.updatedTime} name=${t.name.take(40)}")
            }
        } finally {
            client.close()
        }
    }

    /** Resubmit a task's source URL as a new task, instead of RETRY. Trashes the result. */
    @Test
    fun `resubmit a given task`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val taskId = System.getenv("PROBE_TASK_ID") ?: return@runBlocking
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val old = client.getTask(taskId)
            val url = old.params["url"].orEmpty()
            println("[source] parent=${old.params["parent_folder_id"]} url=${url.take(80)}")
            if (url.isEmpty()) return@runBlocking
            val r = client.createUrlFile(parentId = old.params["parent_folder_id"].orEmpty(), url = url)
            println("[resubmit] $r")
            val newId = (r as? CreateUrlResult.Queued)?.task?.id ?: return@runBlocking
            val settled = awaitTerminal(client, newId)
            println("[settled] ${describe(settled)}")
            if (settled.phase == TaskPhase.COMPLETE && settled.fileId.isNotEmpty()) {
                runCatching { client.batchTrash(listOf(settled.fileId)) }.also { println("[cleanup] trash ${settled.fileId} -> $it") }
            }
            runCatching { client.deleteOfflineTasks(listOf(newId)) }.also { println("[cleanup] delete task $newId -> $it") }
        } finally {
            client.close()
        }
    }

    private suspend fun awaitTerminal(client: PikPakClient, id: String): OfflineTask {
        var task = client.getTask(id)
        repeat(20) {
            if (task.phase in TaskPhase.TERMINAL) return task
            delay(3_000)
            task = client.getTask(id)
        }
        return task
    }

    private fun describe(t: OfflineTask) =
        "id=${t.id} phase=${t.phase} progress=${t.progress} message=${t.message} fileId=${t.fileId} updated=${t.updatedTime}"
}
