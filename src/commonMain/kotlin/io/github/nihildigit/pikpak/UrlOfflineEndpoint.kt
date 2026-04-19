package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Snapshot of an offline-download task. PikPak surfaces the same shape from both
 * the URL submission endpoint (`POST /drive/v1/files` with `UPLOAD_TYPE_URL`) and
 * the task listing endpoint (`GET /drive/v1/tasks`), so this model is unified.
 * `fileId` / `fileName` / `fileSize` populate once the task finishes resolving.
 */
@Serializable
data class OfflineTask(
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

/** Page of offline tasks returned by [listOfflineTasks]. */
@Serializable
data class TaskListResponse(
    val tasks: List<OfflineTask> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

/**
 * Submits a remote URL to PikPak's cloud-download (offline download) queue.
 * The returned [OfflineTask] reflects the task's initial phase — typically
 * `PHASE_TYPE_RUNNING`. Pass `""` for [parentId] to drop the result into the
 * root drive.
 */
suspend fun PikPakClient.createUrlFile(parentId: String, url: String): OfflineTask {
    val body = buildJsonObject {
        put("kind", FileKind.FILE)
        put("upload_type", "UPLOAD_TYPE_URL")
        if (parentId.isNotEmpty()) put("parent_id", parentId)
        putJsonObject("url") { put("url", url) }
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files",
        captchaAction = "POST:/drive/v1/files",
    ) { jsonBody(json, body) }
    val taskNode = (response as JsonObject)["task"]?.jsonObject
        ?: return OfflineTask() // some responses omit task on instant completion
    return json.decodeFromJsonElement(OfflineTask.serializer(), taskNode)
}

/**
 * Lists offline-download tasks on the account. Server-side `filters` is a JSON
 * string wrapping a `phase.in` match — the default catches running + errored
 * tasks, which is what callers polling for completion usually want. Pass
 * e.g. `"PHASE_TYPE_COMPLETE,PHASE_TYPE_ERROR"` to inspect finished work.
 *
 * The SDK intentionally exposes no polling/timeout loop — callers decide when
 * a task counts as "done" (phase transition, disappearance from the running
 * list, a side-effect file landing in the drive, etc.).
 */
suspend fun PikPakClient.listOfflineTasks(
    phaseFilter: String = "PHASE_TYPE_RUNNING,PHASE_TYPE_ERROR",
    limit: Int = 10_000,
    pageToken: String? = null,
): TaskListResponse {
    val query = mutableMapOf(
        "type" to "offline",
        "thumbnail_size" to "SIZE_SMALL",
        "limit" to limit.toString(),
        "filters" to """{"phase":{"in":"$phaseFilter"}}""",
        "with" to "reference_resource",
    )
    if (!pageToken.isNullOrEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/tasks", query),
        captchaAction = "GET:/drive/v1/tasks",
    )
    return json.decodeFromJsonElement(TaskListResponse.serializer(), response)
}
