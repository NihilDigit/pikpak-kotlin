package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Page of offline tasks returned by [listOfflineTasks]. */
@Serializable
data class TaskListResponse(
    val tasks: List<DriveTask> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

/**
 * Outcome of [createUrlFile]. PikPak's `POST /drive/v1/files` with
 * `UPLOAD_TYPE_URL` either enqueues a new offline task ([Queued]) or — in
 * the rarer "this URL was already fetched by some user so we have it"
 * path — returns a done signal with no task envelope ([InstantComplete]).
 * Model the two branches explicitly so callers can't confuse a real task
 * whose fields happen to be default-initialised with the no-task case.
 */
sealed class CreateUrlResult {
    /** PikPak accepted the URL and queued a task; poll it with [getTask]. */
    data class Queued(val task: DriveTask) : CreateUrlResult()

    /**
     * PikPak recognized the URL as already-fetched and did not create a task.
     *
     * The full response body is kept in [raw] because this branch is rare
     * enough that its shape is not pinned down. Probing on 2026-09-02 could
     * not reproduce it: submitting a magnet PikPak had already fetched for
     * this account, twice in a row, produced a fresh `task` node both times.
     * What is known is the shape of the *other* branch, which carries
     * `upload_type`, `url` = `{"kind":"upload#url"}` and `task`; the
     * no-task branch is whatever is left when `task` is absent. [file] is
     * decoded if a `file` node turns up, which is the field a caller would
     * otherwise have to guess at by picking the newest entry in the folder.
     */
    data class InstantComplete(
        val raw: JsonObject,
        val file: FileStat? = null,
    ) : CreateUrlResult()
}

/**
 * Submits a remote URL to PikPak's cloud-download (offline download) queue.
 * Returns [CreateUrlResult.Queued] when a task is created (the usual path;
 * poll it with [getTask]) or [CreateUrlResult.InstantComplete] when
 * PikPak recognized the URL and fulfilled it without a task. Pass `""`
 * for [parentId] to drop the result into the root drive.
 *
 * A magnet is always downloaded whole; to keep only some of its files, prune
 * the completed task with [pruneOfflineOutput]. The full size is charged to
 * the monthly offline allowance of [getTransferQuota].
 */
suspend fun PikPakClient.createUrlFile(parentId: String, url: String): CreateUrlResult {
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
    // as? rather than jsonObject: an explicit null must count as absent, not throw a cast failure
    val obj = response as? JsonObject ?: throw PikPakException(-1, "createUrlFile: bad response shape", rawBody = response.toString())
    val taskNode = obj["task"] as? JsonObject
        ?: return CreateUrlResult.InstantComplete(
            raw = obj,
            file = (obj["file"] as? JsonObject)?.let { json.decodeFromJsonElement(FileStat.serializer(), it) },
        )
    val task = json.decodeFromJsonElement(DriveTask.serializer(), taskNode)
    return CreateUrlResult.Queued(task)
}

// A retryOfflineTask (create_type RETRY) stood here with no caller, and did not work: every task
// retried on 2026-09-23 went back to ERROR "Save failed, retry please", while submitting
// params["url"] again through createUrlFile completed. That is the retry.

/**
 * Deletes offline-task records (`DELETE /drive/v1/tasks`). No-op when
 * [taskIds] is empty.
 *
 * The ids go out as one `task_ids` parameter per id. [deleteFiles] also
 * removes what the tasks produced. Observed 2026-09-23 with it false: a
 * finished task's file stays in the drive untouched, while an unfinished
 * task's placeholder file goes away with the task regardless.
 */
suspend fun PikPakClient.deleteOfflineTasks(taskIds: List<String>, deleteFiles: Boolean = false) {
    if (taskIds.isEmpty()) return
    val query = taskIds.map { "task_ids" to it } + ("delete_files" to deleteFiles.toString())
    http.request(
        method = HttpMethod.Delete,
        url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/tasks", query),
        captchaAction = "DELETE:/drive/v1/tasks",
    )
}

/**
 * Deletes every offline task on the account whose phase is in [phases]
 * (`POST /drive/v1/tasks:clear`) — the web client's "clear completed" and
 * "clear unfinished", which pass `[TaskPhase.COMPLETE]` and
 * `[TaskPhase.PENDING, TaskPhase.RUNNING, TaskPhase.ERROR]` respectively.
 * [deleteFiles] has the meaning it has in [deleteOfflineTasks].
 *
 * `client_id` goes out empty, as the web client sends it by default, which
 * reads as "tasks from every client"; the web client can scope it to its own
 * client id instead. The request shape comes from the web client alone and was
 * never run against a real account, since it cannot be aimed at test data.
 */
suspend fun PikPakClient.clearOfflineTasks(phases: Collection<String>, deleteFiles: Boolean = false) {
    require(phases.isNotEmpty()) { "phases must not be empty" }
    val body = buildJsonObject {
        put("type", "offline")
        putJsonArray("phases") { phases.forEach { add(it) } }
        put("client_id", "")
        put("delete_files", deleteFiles)
    }
    http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}/drive/v1/tasks:clear",
        captchaAction = "POST:/drive/v1/tasks:clear",
    ) { jsonBody(json, body) }
}

/**
 * Lists offline-download tasks on the account. Server-side `filters` is a JSON
 * string wrapping a `phase.in` match. The default, running and errored, is the
 * "what is in progress or broken" view; it leaves out PENDING, where a fresh
 * task starts, and COMPLETE, so it is no way to wait for one task to finish.
 * Pass e.g. `"PHASE_TYPE_COMPLETE,PHASE_TYPE_ERROR"` to inspect finished work.
 *
 * This is the raw listing and waits for nothing. To follow one known task use
 * [getTask]; the server has no `id` filter here and answers 400.
 *
 * The SDK exposes no polling loop over these — callers decide when a task
 * counts as done. For a magnet, prefer [resolveMagnet] and [instantCreate]
 * instead: they reach the same files in about a second without a task at all,
 * where an offline download takes five to ten and may queue behind the swarm.
 *
 * @param limit page size. The 10 000 default fetches the whole table in one
 *   request, which is right for a one-shot inventory and wrong for a poll
 *   loop; lower it when polling.
 * @param with extra data to join in, or null for none. `reference_resource`
 *   (the default) attaches the produced file, at the cost of reporting a task
 *   whose file was deleted as errored — see [getTask].
 */
suspend fun PikPakClient.listOfflineTasks(
    phaseFilter: String = "${TaskPhase.RUNNING},${TaskPhase.ERROR}",
    limit: Int = 10_000,
    pageToken: String? = null,
    with: String? = "reference_resource",
): TaskListResponse {
    val query = mutableMapOf(
        "type" to "offline",
        "thumbnail_size" to "SIZE_SMALL",
        "limit" to limit.toString(),
        "filters" to """{"phase":{"in":"$phaseFilter"}}""",
    )
    if (!with.isNullOrEmpty()) query["with"] = with
    if (!pageToken.isNullOrEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/tasks", query),
        captchaAction = "GET:/drive/v1/tasks",
    )
    return json.decodeFromJsonElement(TaskListResponse.serializer(), response)
}
