package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
suspend fun PikPakClient.getDeepFolderId(parentId: String, path: String): String {
    folderIds.get(parentId, path)?.let { return it }
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    var current = parentId
    for (segment in segments) {
        try {
            current = getFolderId(current, segment)
        } catch (_: FolderNotFoundException) {
            // Report the path the caller asked for, not just the segment that
            // was missing — the segment alone reads as a different lookup.
            throw FolderNotFoundException(path)
        }
    }
    folderIds.put(parentId, path, current)
    return current
}

/** `getDeepFolderId("", path)`. */
suspend fun PikPakClient.getPathFolderId(path: String): String = getDeepFolderId("", path)

/**
 * `mkdir -p` for PikPak. Walks [path] from [parentId], creating any missing
 * folders, and returns the id of the deepest folder.
 *
 * Not atomic, and cannot be: PikPak has no create-if-absent call, so two
 * devices racing on the same missing segment both see "not found" and both
 * create it. PikPak permits duplicate names in one parent, so the result is
 * two folders with the same name and different ids rather than an error.
 * Callers that care should treat "first match wins" as the rule and reconcile
 * out of band; there is no server-side primitive that would let the SDK do
 * better.
 */
suspend fun PikPakClient.getOrCreateDeepFolderId(parentId: String, path: String): String {
    folderIds.get(parentId, path)?.let { return it }
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    var current = parentId
    for (segment in segments) {
        current = try {
            getFolderId(current, segment)
        } catch (_: FolderNotFoundException) {
            createFolder(current, segment)
        }
    }
    folderIds.put(parentId, path, current)
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
    return ((response as JsonObject)["file"]?.jsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        ?: throw PikPakException(-1, "createFolder: response missing file.id")
}

/**
 * Moves [fileId] to the PikPak trash. PikPak's `DELETE /drive/v1/files/{id}`
 * is a soft delete — items are recoverable from the trash UI until purged.
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
 * Moves multiple files/folders to the PikPak trash in one call. Items remain
 * recoverable from the trash UI for ~30 days. No-op when [ids] is empty.
 * For permanent removal that bypasses the trash, see [batchDelete].
 */
suspend fun PikPakClient.batchTrash(ids: List<String>) =
    batchOperate(ids, "batchTrash")

/**
 * Permanently removes multiple files/folders, bypassing the trash. Items are
 * not recoverable. No-op when [ids] is empty. For soft-delete semantics that
 * stage items in the trash for 30 days, use [batchTrash] instead.
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
 * order and a failing one leaves the chunks before it applied.
 */
private suspend fun PikPakClient.batchOperate(ids: List<String>, op: String) {
    if (ids.isEmpty()) return
    for (chunk in ids.chunked(BATCH_ID_LIMIT)) {
        val body = buildJsonObject {
            putJsonArray("ids") { chunk.forEach { add(it) } }
        }
        http.request(
            method = HttpMethod.Post,
            url = "$DRIVE$FILES_PATH:$op",
            captchaAction = "POST:/drive/v1/files:$op",
        ) { jsonBody(json, body) }
    }
    folderIds.invalidateAll()
}

/** Half of the smallest count measured to fail, so a future tightening has room. */
private const val BATCH_ID_LIMIT = 100

/**
 * Relocates [ids] to [toParentId] (empty string for the root drive). The "update"
 * leg of CRUD: changes a file/folder's `parent_id`. No-op when [ids] is empty.
 */
suspend fun PikPakClient.batchMove(ids: List<String>, toParentId: String) {
    if (ids.isEmpty()) return
    val body = buildJsonObject {
        putJsonArray("ids") { ids.forEach { add(it) } }
        putJsonObject("to") { put("parent_id", toParentId) }
    }
    http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$FILES_PATH:batchMove",
        captchaAction = "POST:/drive/v1/files:batchMove",
    ) { jsonBody(json, body) }
    folderIds.invalidateAll()
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
