package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull

class FolderNotFoundException(val path: String) : RuntimeException("PikPak folder not found: $path")

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/**
 * Looks up the file id of an immediate child folder of [parentId] named [name].
 * Throws [FolderNotFoundException] if no such folder exists. Pass `""` for the
 * root drive.
 *
 * Folders are selected on the server, so a parent holding thousands of files
 * and a handful of folders costs one page instead of all of them. The name
 * itself is still compared locally: PikPak refuses `name` as a filter field
 * (404 under every operator) and ignores the `q` / `search_text` query
 * parameters, so there is nothing server-side to match against.
 */
suspend fun PikPakClient.getFolderId(parentId: String, name: String): String {
    var pageToken = ""
    val filters = mapOf(FileFilter.kind(FileKind.FOLDER))
    do {
        val page = listFilesPaged(parentId, 500, pageToken, filters)
        page.files.firstOrNull { it.isFolder && it.name == name && !it.trashed }?.let { return it.id }
        pageToken = page.nextPageToken
    } while (pageToken.isNotEmpty())
    throw FolderNotFoundException(name)
}

/**
 * Resolves a slash-separated path (e.g. `"a/b/c"` or `"/a/b"`) to a folder id,
 * starting from [parentId]. Pass `""` for the root drive. Throws
 * [FolderNotFoundException] for the first missing segment.
 *
 * Resolved paths are memoized on the client, so a second call for the same
 * path costs nothing. Any mutating call in this file drops the memo; see
 * [PikPakClient.clearFolderIdCache] to drop it after a change made elsewhere.
 */
suspend fun PikPakClient.getDeepFolderId(parentId: String, path: String): String =
    // Report the path the caller asked for, not just the segment that was
    // missing — the segment alone reads as a different lookup.
    walkFolders(parentId, path) { _, _ -> throw FolderNotFoundException(path) }

/** `getDeepFolderId("", path)`. */
suspend fun PikPakClient.getPathFolderId(path: String): String = getDeepFolderId("", path)

/**
 * `mkdir -p` for PikPak. Walks [path] from [parentId], creating any missing
 * folders, and returns the id of the deepest folder.
 *
 * Not atomic across devices, and cannot be: PikPak has no create-if-absent
 * call, so two devices racing on the same missing segment both see "not
 * found" and both create it. PikPak permits duplicate names in one parent, so
 * the result is two folders with the same name and different ids rather than
 * an error. Callers that care should treat "first match wins" as the rule and
 * reconcile out of band. Within one client the walks are serialised, so two
 * coroutines of the same process do not do this to each other.
 */
suspend fun PikPakClient.getOrCreateDeepFolderId(parentId: String, path: String): String {
    folderIds.get(parentId, path)?.let { return it }
    return folderIds.creation.withLock {
        walkFolders(parentId, path) { current, segment -> createFolder(current, segment) }
    }
}

/**
 * Resolves [path] one segment at a time from [parentId], asking [onMissing] for a segment that
 * is not there, and memoizes the result unless the memo was dropped meanwhile; see FolderIdCache.
 */
private suspend fun PikPakClient.walkFolders(
    parentId: String,
    path: String,
    onMissing: suspend (parentId: String, segment: String) -> String,
): String {
    folderIds.get(parentId, path)?.let { return it }
    val generation = folderIds.generation()
    var current = parentId
    for (segment in path.trim('/').split('/').filter { it.isNotEmpty() }) {
        current = try {
            getFolderId(current, segment)
        } catch (_: FolderNotFoundException) {
            onMissing(current, segment)
        }
    }
    folderIds.put(parentId, path, current, generation)
    return current
}

/** Creates a folder under [parentId]. Returns the new folder's id. */
suspend fun PikPakClient.createFolder(parentId: String, name: String): String {
    val body = buildJsonObject {
        put("kind", FileKind.FOLDER)
        if (parentId.isNotEmpty()) put("parent_id", parentId)
        put("name", name)
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$FILES_PATH",
        captchaAction = "POST:/drive/v1/files",
    ) { jsonBody(json, body) }
    // as? rather than jsonObject: an explicit "file": null must reach the error below, not throw a cast failure
    return ((response as? JsonObject)?.get("file") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        ?: throw PikPakException(-1, "createFolder: response missing file.id", rawBody = response.toString())
}

/**
 * Deletes [fileId] permanently. `DELETE /drive/v1/files/{id}` bypasses the
 * trash: observed 2026-09-23, the file answers 404 right after and is not in
 * [listTrash]. Use [batchTrash] for a recoverable delete.
 */
suspend fun PikPakClient.deleteFile(fileId: String) {
    http.request(
        method = HttpMethod.Delete,
        url = "$DRIVE$FILES_PATH/$fileId",
        captchaAction = "DELETE:/drive/v1/files",
    )
    folderIds.invalidateAll()
}

/**
 * Moves multiple files/folders to the PikPak trash. Items stay recoverable
 * until their [FileStat.deleteTime], which the server sets per account (fifteen
 * days on a platinum account, measured) — read it rather than assume a period.
 * No-op when [ids] is empty. For permanent removal that bypasses the trash, see
 * [batchDelete].
 */
suspend fun PikPakClient.batchTrash(ids: List<String>) =
    batchOperate(ids, "batchTrash")

/**
 * Permanently removes multiple files/folders, bypassing the trash. Items are
 * not recoverable. No-op when [ids] is empty. For a recoverable delete, use
 * [batchTrash].
 */
suspend fun PikPakClient.batchDelete(ids: List<String>) =
    batchOperate(ids, "batchDelete")

/**
 * Restores trashed items back to their original parent folder. Counterpart to
 * [batchTrash]. No-op when [ids] is empty. Ids must reference items currently
 * in the trash; untrashing a non-trashed item is a no-op on PikPak's side.
 */
suspend fun PikPakClient.batchUntrash(ids: List<String>) =
    batchOperate(ids, "batchUntrash")

/**
 * One `files:<op>` call per [BATCH_ID_LIMIT] ids.
 *
 * PikPak caps how many ids one call may name: measured 2026-09-12, 200 are
 * accepted and 1000 answer `operating_file_count_exceeded` (error_code 11).
 * Callers that hand over a whole folder listing cannot know how long it is, so
 * the split happens here rather than at each call site. Chunks are sent in
 * order and a failing one leaves the chunks before it applied — which is why
 * the folder memo is dropped however the loop ends, not only after the last.
 */
internal suspend fun PikPakClient.batchOperate(
    ids: List<String>,
    op: String,
    to: String? = null,
) {
    if (ids.isEmpty()) return
    try {
        for (chunk in ids.chunked(BATCH_ID_LIMIT)) {
            val body = buildJsonObject {
                putJsonArray("ids") { chunk.forEach { add(it) } }
                if (to != null) putJsonObject("to") { put("parent_id", to) }
            }
            http.request(
                method = HttpMethod.Post,
                url = "$DRIVE$FILES_PATH:$op",
                captchaAction = "POST:/drive/v1/files:$op",
            ) { jsonBody(json, body) }
        }
    } finally {
        folderIds.invalidateAll()
    }
}

/** Half of the smallest count measured to fail, so a future tightening has room. */
internal const val BATCH_ID_LIMIT = 100

/**
 * Relocates [ids] to [toParentId] (empty string for the root drive). The "update"
 * leg of CRUD: changes a file/folder's `parent_id`. No-op when [ids] is empty.
 * Split into calls of [BATCH_ID_LIMIT] ids like the other batch operations; one
 * call naming a whole large listing was refused with nothing moved.
 */
suspend fun PikPakClient.batchMove(ids: List<String>, toParentId: String) =
    batchOperate(ids, "batchMove", to = toParentId)

/**
 * Copies [ids] into [toParentId] (empty string for the root drive) and returns
 * the server's task id for each [BATCH_ID_LIMIT]-sized chunk. No-op returning
 * an empty list when [ids] is empty.
 *
 * The copy runs as a server task (`type` `copy`) that [getTask] can read.
 * Observed 2026-09-24 on a folder with one empty subfolder: the task was
 * already `PHASE_TYPE_COMPLETE` and the copy listed in the target when the
 * call returned; nothing was measured for larger trees, so a caller that
 * needs the copy in place should check the task rather than assume it.
 * Copying into the item's own parent or into its own subtree is refused with
 * `file_move_or_copy_to_cur` (error_code 9, same as [batchMove]).
 *
 * [unzipPassword] goes out as the `unzip_password` query parameter the web
 * client sends when copying out of an archive; leave it null otherwise.
 */
suspend fun PikPakClient.batchCopy(
    ids: List<String>,
    toParentId: String,
    unzipPassword: String? = null,
): List<String> {
    if (ids.isEmpty()) return emptyList()
    val query = if (unzipPassword != null) mapOf("unzip_password" to unzipPassword) else emptyMap()
    val taskIds = ids.chunked(BATCH_ID_LIMIT).map { chunk ->
        val body = buildJsonObject {
            putJsonArray("ids") { chunk.forEach { add(it) } }
            putJsonObject("to") { put("parent_id", toParentId) }
        }
        val response = http.request(
            method = HttpMethod.Post,
            url = buildUrl(DRIVE, "$FILES_PATH:batchCopy", query),
            captchaAction = "POST:/drive/v1/files:batchCopy",
        ) { jsonBody(json, body) }
        (response as? JsonObject)?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?: throw PikPakException(-1, "batchCopy: response missing task_id", rawBody = response.toString())
    }
    folderIds.invalidateAll()
    return taskIds
}

/** Renames [fileId] to [newName]. Empty names are rejected client-side. */
suspend fun PikPakClient.rename(fileId: String, newName: String) {
    require(newName.isNotEmpty()) { "newName must not be empty" }
    val body = buildJsonObject { put("name", newName) }
    http.request(
        method = HttpMethod.Patch,
        url = "$DRIVE$FILES_PATH/$fileId",
        captchaAction = "PATCH:/drive/v1/files",
    ) { jsonBody(json, body) }
    folderIds.invalidateAll()
}

/**
 * Returns full file metadata including signed download links. For directory
 * listings prefer [listFiles] — this endpoint is per-id and slower at scale.
 */
suspend fun PikPakClient.getFile(fileId: String): FileDetail {
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, "$FILES_PATH/$fileId", mapOf("thumbnail_size" to THUMBNAIL_SIZE)),
        captchaAction = "GET:/drive/v1/files",
    )
    return json.decodeFromJsonElement(FileDetail.serializer(), response)
}
