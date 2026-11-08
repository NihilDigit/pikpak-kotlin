package io.github.nihildigit.pikpak

import io.ktor.http.HttpMethod
import io.github.nihildigit.pikpak.internal.buildUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Values PikPak uses for [DriveTask.phase] and [FileDetail.phase]. Exported
 * because polling a task means comparing against them, and a caller that has
 * to spell the strings itself gets no compiler help when one is mistyped.
 */
object TaskPhase {
    const val PENDING = "PHASE_TYPE_PENDING"
    const val RUNNING = "PHASE_TYPE_RUNNING"
    const val COMPLETE = "PHASE_TYPE_COMPLETE"
    const val ERROR = "PHASE_TYPE_ERROR"

    /** The two phases a task never leaves. */
    val TERMINAL: Set<String> = setOf(COMPLETE, ERROR)
}

/**
 * One drive task, whatever made it: an offline download ([type] `offline`),
 * a share restore (`restore`), a server-side extraction (`decompress`), a
 * pack (`pack_files`), a copy (`copy`). The shape is the same for all of them,
 * and so is [getTask]. `fileId` / `fileName` / `fileSize` populate once an
 * offline task finishes resolving.
 */
@Serializable
data class DriveTask(
    val id: String = "",
    val kind: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("user_id") val userId: String = "",
    val phase: String = "",
    val progress: Int = 0,
    val message: String = "",
    @SerialName("status_size") val statusSize: Int = 0,
    val params: Map<String, String> = emptyMap(),
    @SerialName("file_id") val fileId: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: String = "0",
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("updated_time") val updatedTime: String? = null,
)

// The offline download was the first task type this SDK read, and the name stuck
// to every task type after it.
@Deprecated("Every task type shares this record", ReplaceWith("DriveTask"))
typealias OfflineTask = DriveTask

/**
 * Fetches one task by id (`GET /drive/v1/tasks/{id}`), of any type. Use this to
 * poll a task you submitted instead of listing every task on the account and
 * searching it — a poll loop over [listOfflineTasks] pulls the whole table
 * once every interval.
 *
 * The single-task response and the listing do not always agree. The listing
 * defaults to `with=reference_resource`, which overlays the state of the file
 * the task produced: a task whose output file was later deleted reads as
 * `PHASE_TYPE_ERROR` / "File deleted" in the listing while this endpoint still
 * reports the task's own `PHASE_TYPE_COMPLETE` / "Saved". Ask this endpoint
 * about the transfer, the listing about the file.
 */
suspend fun PikPakClient.getTask(taskId: String): DriveTask {
    require(taskId.isNotEmpty()) { "taskId must not be empty" }
    val response = http.request(
        method = HttpMethod.Get,
        url = "${PikPakConstants.DRIVE_BASE}/drive/v1/tasks/$taskId",
        captchaAction = "GET:/drive/v1/tasks",
    )
    return json.decodeFromJsonElement(DriveTask.serializer(), response).also {
        accountState.observeTasks(listOf(it), baseline = false)
    }
}

class DriveTaskFailedException(val task: DriveTask) : RuntimeException("Drive task ${task.id} failed")

/** The tasks may still finish remotely after this timeout. */
class DriveTasksTimeoutException(val taskIds: List<String>) : RuntimeException("Timed out waiting for ${taskIds.size} drive tasks")

/** Batch lookup, following any server-side pagination. Missing ids are not completed tasks. */
suspend fun PikPakClient.getTasks(taskIds: List<String>): List<DriveTask> {
    require(taskIds.all { it.isNotBlank() && ',' !in it })
    val result = LinkedHashMap<String, DriveTask>()
    for (chunk in taskIds.distinct().chunked(100)) {
        var token = ""
        val seen = HashSet<String>()
        do {
            val query = mutableMapOf(
                "with" to "reference_resource", "type" to "", "thumbnail_size" to THUMBNAIL_SIZE,
                "limit" to "10000", "filters" to buildJsonObject {
                    putJsonObject("id") { put("in", chunk.joinToString(",")) }
                }.toString(),
            )
            if (token.isNotEmpty()) query["page_token"] = token
            val raw = http.request(HttpMethod.Get, buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/tasks", query),
                captchaAction = "GET:/drive/v1/tasks")
            val page = json.decodeFromJsonElement(TaskListResponse.serializer(), raw)
            for (task in page.tasks) if (task.id in chunk) result[task.id] = task
            token = page.nextPageToken.orEmpty()
            check(token.isEmpty() || seen.add(token)) { "Repeated task page token" }
        } while (token.isNotEmpty())
    }
    return taskIds.distinct().mapNotNull { result[it] }.also {
        accountState.observeTasks(it, baseline = false)
    }
}

/** Cancellation stops waiting, not the remote operation. */
suspend fun PikPakClient.awaitTasks(
    taskIds: List<String>, pollIntervalMs: Long = 300, timeoutMs: Long = 60_000,
): List<DriveTask> {
    require(pollIntervalMs > 0 && timeoutMs > 0)
    require(taskIds.all { it.isNotBlank() && ',' !in it })
    val ids = taskIds.distinct()
    if (ids.isEmpty()) return emptyList()
    val completed = LinkedHashMap<String, DriveTask>()
    return withTimeoutOrNull(timeoutMs) {
        while (completed.size < ids.size) {
            for (listed in getTasks(ids.filterNot { it in completed })) {
                // The listing can overlay a deleted output onto a successfully completed task.
                val task = if (listed.phase == TaskPhase.ERROR) getTask(listed.id) else listed
                if (task.phase == TaskPhase.ERROR) throw DriveTaskFailedException(task)
                if (task.phase == TaskPhase.COMPLETE) completed[task.id] = task
            }
            if (completed.size < ids.size) delay(pollIntervalMs)
        }
        ids.map { completed.getValue(it) }
    } ?: throw DriveTasksTimeoutException(ids.filterNot { it in completed })
}
