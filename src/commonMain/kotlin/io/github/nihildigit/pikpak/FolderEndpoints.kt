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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull

class FolderNotFoundException(val path: String) : RuntimeException("PikPak folder not found: $path")

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/**
 * Looks up the file id of an immediate child folder of [parentId] named [name].
 * Throws [FolderNotFoundException] if no such folder exists. Pass `""` for the
 * root drive.
 */
suspend fun PikPakClient.getFolderId(parentId: String, name: String): String {
    var pageToken = ""
    do {
        val page = listFilesPaged(parentId, 500, pageToken)
        page.files.firstOrNull { it.isFolder && it.name == name && !it.trashed }?.let { return it.id }
        pageToken = page.nextPageToken
    } while (pageToken.isNotEmpty())
    throw FolderNotFoundException(name)
}

/**
 * Resolves a slash-separated path (e.g. `"a/b/c"` or `"/a/b"`) to a folder id,
 * starting from [parentId]. Pass `""` for the root drive. Throws
 * [FolderNotFoundException] for the first missing segment.
 */
suspend fun PikPakClient.getDeepFolderId(parentId: String, path: String): String {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    var current = parentId
    for (segment in segments) {
        try {
            current = getFolderId(current, segment)
        } catch (e: FolderNotFoundException) {
            throw FolderNotFoundException(path)
        }
    }
    return current
}

/** `getDeepFolderId("", path)`. */
suspend fun PikPakClient.getPathFolderId(path: String): String = getDeepFolderId("", path)

/**
 * `mkdir -p` for PikPak. Walks [path] from [parentId], creating any missing
 * folders, and returns the id of the deepest folder.
 */
suspend fun PikPakClient.getOrCreateDeepFolderId(parentId: String, path: String): String {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    var current = parentId
    for (segment in segments) {
        current = try {
            getFolderId(current, segment)
        } catch (_: FolderNotFoundException) {
            createFolder(current, segment)
        }
    }
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
}

/**
 * Moves multiple files/folders to the PikPak trash in one call. Items remain
 * recoverable from the trash UI for ~30 days. No-op when [ids] is empty.
 * For permanent removal that bypasses the trash, see [batchDelete].
 */
suspend fun PikPakClient.batchTrash(ids: List<String>) {
    if (ids.isEmpty()) return
    val body = buildJsonObject {
        putJsonArray("ids") { ids.forEach { add(it) } }
    }
    http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$FILES_PATH:batchTrash",
        captchaAction = "POST:/drive/v1/files:batchTrash",
    ) { jsonBody(json, body) }
}

/**
 * Permanently removes multiple files/folders, bypassing the trash. Items are
 * not recoverable. No-op when [ids] is empty. For soft-delete semantics that
 * stage items in the trash for 30 days, use [batchTrash] instead.
 */
suspend fun PikPakClient.batchDelete(ids: List<String>) {
    if (ids.isEmpty()) return
    val body = buildJsonObject {
        putJsonArray("ids") { ids.forEach { add(it) } }
    }
    http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$FILES_PATH:batchDelete",
        captchaAction = "POST:/drive/v1/files:batchDelete",
    ) { jsonBody(json, body) }
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
}

/**
 * Returns full file metadata including signed download links. For directory
 * listings prefer [listFiles] — this endpoint is per-id and slower at scale.
 */
suspend fun PikPakClient.getFile(fileId: String): FileDetail {
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, "$FILES_PATH/$fileId", mapOf("thumbnail_size" to "SIZE_MEDIUM")),
        captchaAction = "GET:/drive/v1/files",
    )
    return json.decodeFromJsonElement(FileDetail.serializer(), response)
}
