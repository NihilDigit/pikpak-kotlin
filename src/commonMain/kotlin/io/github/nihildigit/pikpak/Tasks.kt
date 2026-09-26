package io.github.nihildigit.pikpak

import io.ktor.http.HttpMethod
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
    return json.decodeFromJsonElement(DriveTask.serializer(), response)
}
