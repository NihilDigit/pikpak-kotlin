package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// PikPak reads archives on the server in two independent ways, both measured
// 2026-09-24 against zip (plain and ZipCrypto), 7z (plain and with encrypted
// headers) and rar:
//
//  - The decompress service (`/decompress/v1/...`) addresses an archive by
//    file id plus gcid and an entry by its path inside the archive. It lists
//    one directory level at a time and extracts into the drive as a task.
//  - The archive tree: once an archive has been extracted at least once, its
//    `params` carry a token under which the ordinary `/drive/v1/files`
//    endpoints list and fetch the entries as virtual `G__…` file objects.
//    That is how the web client browses an archive like a folder.
//
// Neither is a superset of the other. Only the decompress service works on an
// archive nobody has extracted yet; only the tree yields a download link for a
// single entry without extracting it.

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val DECOMPRESS = "/decompress/v1"
private const val FILES_PATH = "/drive/v1/files"
private const val GLOBAL_FILE_TOKEN_HEADER = "x-global-file-token"

/**
 * Raised when an archive needs a password that was not given, or was given
 * wrong. The decompress service reports this as a status inside a 200 body
 * (`PASS_WORD_EMPTY`, `PASS_WORD_ERROR`), the archive tree as HTTP 400 with
 * `error_code=9` and `unzip_password_required` / `unzip_password_incorrect`;
 * both arrive here so a caller prompts in one place.
 *
 * Which archives ask: a ZipCrypto zip does even though its entry names are
 * stored in the clear, so the server wants the password before it lists
 * anything. A plain archive ignores whatever password is sent.
 */
class ArchivePasswordException(
    /** False when no password was sent, true when the one sent was wrong. */
    val incorrect: Boolean,
    errorCode: Int,
    errorMessage: String,
    errorDescription: String?,
    httpStatus: Int? = null,
    rawBody: String? = null,
    cause: Throwable? = null,
) : PikPakException(errorCode, errorMessage, errorDescription, httpStatus, rawBody = rawBody, cause = cause)

/** One entry of [listArchive]. */
@Serializable
data class ArchiveEntry(
    /**
     * Not a usable address: a zip reported 0 for both entries of its top
     * level, and extracting with only an index or only a name extracts the
     * whole archive. Select by [path].
     */
    val index: Int = 0,
    @SerialName("filename") val name: String = "",
    @SerialName("filesize") val size: String = "0",
    @SerialName("mime_type") val mimeType: String = "",
    /**
     * gcid of the entry's content. Empty for folders, and empty for files of
     * an archive the server has not indexed yet (every fresh upload); filled
     * for archives that had been extracted before.
     */
    val gcid: String = "",
    val kind: String = "",
    /** Path inside the archive. Folders end with `/`; pass it back as `path` to list one. */
    val path: String = "",
) {
    val isFolder: Boolean get() = kind == FileKind.FOLDER
    val sizeBytes: Long get() = size.toLongOrNull() ?: 0L
}

/** One directory level of an archive, from [listArchive]. */
@Serializable
data class ArchiveListing(
    /** The archive's own file name. */
    val title: String = "",
    @SerialName("file_size") val fileSize: String = "0",
    val gcid: String = "",
    @SerialName("current_path") val currentPath: String = "",
    val files: List<ArchiveEntry> = emptyList(),
)

/** Response of [decompressArchive]. */
@Serializable
data class DecompressTask(
    @SerialName("task_id") val taskId: String = "",
    /** Files the task will write; the selected folders' contents are counted, the folders are not. */
    @SerialName("files_num") val fileCount: Int = 0,
)

/**
 * Response of [getDecompressProgress]. Recorded 2026-09-24: a 6 KB archive
 * went from 0 to 100 within four seconds.
 */
@Serializable
data class DecompressProgress(
    /** 0–100. */
    val progress: Int = 0,
    /** `PHASE_TYPE_RUNNING`, `PHASE_TYPE_COMPLETE` or `PHASE_TYPE_ERROR`; see [TaskPhase]. */
    val phase: String = "",
    /**
     * The folder the task created, set once [phase] is complete. It is named
     * after the archive without its extension and holds the extracted entries
     * at their paths inside the archive.
     */
    @SerialName("file_id") val fileId: String = "",
    /** Bytes to write and bytes written so far, decimal strings. */
    @SerialName("task_size") val taskSize: String = "0",
    @SerialName("task_size_completed") val taskSizeCompleted: String = "0",
    /**
     * Read as a hint for when to poll again: 1–2 while running, 999 once
     * complete. The web client treats it the same way.
     */
    @SerialName("expires_in") val expiresIn: Int = 0,
    /** Set when [phase] is `PHASE_TYPE_ERROR`. */
    @SerialName("error_description") val errorDescription: String = "",
)

/**
 * The server-side tree of an archive's entries, taken from the archive's
 * `params`. Pass it to [listArchiveTreePaged], [getArchiveTreeFile] and
 * [copyFromArchiveTree].
 *
 * The token is per archive: presenting one archive's token for another's
 * entries answers 404, and sending none answers `global_file_token_invalid`
 * (error_code 9). How long a token stays valid was not measured; re-reading
 * the archive yields the one to use.
 */
data class ArchiveTree(
    /** Id of the tree's root, a virtual folder named after the archive without its extension. */
    val rootId: String,
    val token: String,
)

/**
 * The archive's entry tree, or null when the server has none for it.
 *
 * Observed 2026-09-24 on four fresh uploads: the params stayed without a tree
 * after [listArchive] and gained one with the first [decompressArchive], a
 * single-entry extraction included. All five older archives checked on the
 * same account carried one already.
 */
val FileStat.archiveTree: ArchiveTree? get() = archiveTreeOf(params)

/** See [FileStat.archiveTree]. */
val FileDetail.archiveTree: ArchiveTree? get() = archiveTreeOf(params)

private fun archiveTreeOf(params: Map<String, String>): ArchiveTree? {
    val root = params["global_file_root"]?.takeIf { it.isNotEmpty() } ?: return null
    val token = params["global_file_token"]?.takeIf { it.isNotEmpty() } ?: return null
    return ArchiveTree(root, token)
}

/**
 * Lists one directory level of the archive [fileId] (`POST /decompress/v1/list`).
 * [path] is `""` for the top level or a folder's [ArchiveEntry.path], which
 * ends with `/`. The response carries no page token.
 *
 * [gcid] is required alongside the id; either alone is `invalid_argument`.
 * Works on an archive the server has never seen extracted, which the
 * [archiveTree] does not.
 *
 * Throws [ArchivePasswordException] when the archive needs a password. A tar
 * (the one [packFolder] produces was tried) answers HTTP 500, which the client
 * retries with backoff before it surfaces.
 */
suspend fun PikPakClient.listArchive(
    fileId: String,
    gcid: String,
    path: String = "",
    password: String = "",
): ArchiveListing {
    val body = buildJsonObject {
        put("gcid", gcid)
        put("path", path)
        put("file_id", fileId)
        put("password", password)
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$DECOMPRESS/list",
        captchaAction = "POST:$DECOMPRESS/list",
    ) { jsonBody(json, body) }
    requireArchiveStatusOk(response)
    return json.decodeFromJsonElement(ArchiveListing.serializer(), response)
}

/**
 * Extracts the archive [fileId] into the drive as a task
 * (`POST /decompress/v1/decompress`); follow it with [getDecompressProgress].
 *
 * The task creates one folder named after the archive without its extension
 * under [toParentId], or beside the archive when [toParentId] is null, and
 * writes the entries at their paths inside the archive.
 *
 * [paths] selects entries by [ArchiveEntry.path]; a folder's path takes its
 * whole subtree. Empty extracts everything. Entries go on the wire as
 * `{"path": …}` only, because an entry without a path (index or name alone)
 * is ignored and the whole archive extracted instead.
 *
 * Throws [ArchivePasswordException] for a missing or wrong password, and a
 * [PikPakException] carrying the status for any other refusal; the web client
 * knows `NEED_MORE_QUOTA` there, which a premium account did not meet.
 */
suspend fun PikPakClient.decompressArchive(
    fileId: String,
    gcid: String,
    toParentId: String?,
    password: String = "",
    paths: List<String> = emptyList(),
): DecompressTask {
    val body = buildJsonObject {
        put("gcid", gcid)
        put("file_id", fileId)
        put("password", password)
        putJsonArray("files") { paths.forEach { addJsonObject { put("path", it) } } }
        put("default_parent", toParentId == null)
        if (toParentId != null) put("parent_id", toParentId)
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$DECOMPRESS/decompress",
        captchaAction = "POST:$DECOMPRESS/decompress",
    ) { jsonBody(json, body) }
    requireArchiveStatusOk(response)
    return json.decodeFromJsonElement(DecompressTask.serializer(), response)
}

/**
 * Progress of a [decompressArchive] task (`GET /decompress/v1/progress`).
 * An unknown id is `invalid_argument` ("task not found").
 *
 * The same id is also a drive task of type `decompress`, so [getTask] reads it
 * too; this endpoint is the one with byte counts.
 */
suspend fun PikPakClient.getDecompressProgress(taskId: String): DecompressProgress {
    require(taskId.isNotEmpty()) { "taskId must not be empty" }
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, "$DECOMPRESS/progress", mapOf("task_id" to taskId)),
        captchaAction = "GET:$DECOMPRESS/progress",
    )
    return json.decodeFromJsonElement(DecompressProgress.serializer(), response)
}

/**
 * One page of the entries under [parentId] in an [ArchiveTree]
 * (`GET /drive/v1/files` with the tree token). [parentId] defaults to the
 * tree's root, whose children are the archive's top level.
 *
 * Entries are ordinary [FileStat]s with `G__…` ids; files carry their gcid in
 * [FileStat.hash]. They exist only under the token: [getFile] on one answers
 * `global_file_token_invalid`, which is why [getArchiveTreeFile] exists.
 *
 * The password goes with every request, not once per session. Throws
 * [ArchivePasswordException] when it is missing or wrong.
 */
suspend fun PikPakClient.listArchiveTreePaged(
    tree: ArchiveTree,
    parentId: String = tree.rootId,
    password: String = "",
    pageSize: Int = 500,
    pageToken: String = "",
): FileListPage {
    val query = mutableMapOf(
        "thumbnail_size" to THUMBNAIL_SIZE,
        "limit" to pageSize.toString(),
        "parent_id" to parentId,
        "unzip_password" to password,
    )
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = archiveTreeRequest {
        http.request(
            method = HttpMethod.Get,
            url = buildUrl(DRIVE, FILES_PATH, query),
            captchaAction = "GET:$FILES_PATH",
        ) { treeToken(tree) }
    }
    return json.decodeFromJsonElement(FileListPage.serializer(), response)
}

/**
 * Detail of one entry of an [ArchiveTree], including signed [FileDetail.links]
 * to the entry's own bytes. Reading one file out of an archive therefore needs
 * no extraction: the link fetched on 2026-09-24 returned exactly the bytes of
 * the original file.
 *
 * Throws [ArchivePasswordException] when the password is missing or wrong.
 */
suspend fun PikPakClient.getArchiveTreeFile(
    tree: ArchiveTree,
    fileId: String,
    password: String = "",
): FileDetail {
    val query = mapOf("thumbnail_size" to THUMBNAIL_SIZE, "usage" to "FETCH", "unzip_password" to password)
    val response = archiveTreeRequest {
        http.request(
            method = HttpMethod.Get,
            url = buildUrl(DRIVE, "$FILES_PATH/$fileId", query),
            captchaAction = "GET:$FILES_PATH",
        ) { treeToken(tree) }
    }
    return json.decodeFromJsonElement(FileDetail.serializer(), response)
}

/**
 * Copies entries of an [ArchiveTree] into the drive folder [toParentId]
 * (`POST /drive/v1/files:batchCopy` with the tree token) and returns the task
 * id. This is how the web client extracts a selection today.
 *
 * Unlike [decompressArchive] it creates no folder named after the archive:
 * the selected entries land directly under [toParentId], folders with their
 * subtree. Two entries were in place within five seconds.
 *
 * Throws [ArchivePasswordException] when the password is missing or wrong.
 */
suspend fun PikPakClient.copyFromArchiveTree(
    tree: ArchiveTree,
    ids: List<String>,
    toParentId: String,
    password: String = "",
): String {
    require(ids.isNotEmpty()) { "ids must not be empty" }
    val body = buildJsonObject {
        putJsonArray("ids") { ids.forEach { add(it) } }
        putJsonObject("to") { put("parent_id", toParentId) }
    }
    val response = archiveTreeRequest {
        http.request(
            method = HttpMethod.Post,
            url = buildUrl(DRIVE, "$FILES_PATH:batchCopy", mapOf("unzip_password" to password)),
            captchaAction = "POST:$FILES_PATH:batchCopy",
        ) {
            treeToken(tree)
            jsonBody(json, body)
        }
    }
    return response.taskIdOrThrow("copyFromArchiveTree")
}

/** The `task_id` a task-starting call answers with. "" would only fail later, in getTask, without context. */
private fun JsonElement.taskIdOrThrow(op: String): String =
    ((this as? JsonObject)?.get("task_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?: throw PikPakException(-1, "$op: response missing task_id", rawBody = toString())

/**
 * Turns the folder [folderId] into a single `.tar` file in place
 * (`POST /drive/v1/files/pack`) and returns the task id.
 *
 * Measured 2026-09-24: the id is kept, the name gains `.tar`, the kind becomes
 * a file and the children disappear from listings but are not deleted —
 * [unpackFolder] brings the same folder and the same child ids back. The tar
 * is served from its own CDN mirror, so the whole folder downloads as one
 * file. Right after the call the file has size 0 and no gcid; both are set
 * once the task completes, which [getTask] reports (type `pack_files`, the
 * gcid, file count and total size in its params). A few kilobytes completed
 * in under a second.
 *
 * Refusals, all `error_code=3`: an empty folder is `cannot_pack_empty_folder`,
 * a file (a packed folder included) is `invalid_argument` "must be a folder".
 * The [listArchive] endpoint cannot read the resulting tar.
 */
suspend fun PikPakClient.packFolder(folderId: String): String {
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$FILES_PATH/pack",
        captchaAction = "POST:$FILES_PATH/pack",
    ) { jsonBody(json, buildJsonObject { put("folderId", folderId) }) }
    // A cached path may now resolve to a file.
    folderIds.invalidateAll()
    return response.taskIdOrThrow("packFolder")
}

/**
 * Reverses [packFolder] (`POST /drive/v1/files/unpack`): the tar becomes the
 * original folder again, same id and children. Synchronous; the response's
 * task id is empty. Anything [packFolder] did not produce, an uploaded
 * archive included, is `invalid_argument` "must be a tar folder" — this does
 * not extract archives, [decompressArchive] does.
 */
suspend fun PikPakClient.unpackFolder(folderId: String) {
    http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$FILES_PATH/unpack",
        captchaAction = "POST:$FILES_PATH/unpack",
    ) { jsonBody(json, buildJsonObject { put("folderId", folderId) }) }
    folderIds.invalidateAll()
}

private fun HttpRequestBuilder.treeToken(tree: ArchiveTree) {
    header(GLOBAL_FILE_TOKEN_HEADER, tree.token)
}

/**
 * The decompress service answers 200 with a `status` instead of an error
 * envelope, so [HttpEngine][io.github.nihildigit.pikpak.internal.HttpEngine]
 * lets a refusal through as success.
 */
private fun requireArchiveStatusOk(response: JsonElement) {
    val obj = response as? JsonObject ?: throw PikPakException(-1, "unexpected response", response.toString())
    val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val text = obj["status_text"]?.jsonPrimitive?.contentOrNull
    when (status) {
        "OK" -> return
        "PASS_WORD_EMPTY", "PASS_WORD_ERROR" ->
            throw ArchivePasswordException(status == "PASS_WORD_ERROR", -1, status, text, rawBody = obj.toString())
        else -> throw PikPakException(-1, status.ifEmpty { "missing status" }, text, rawBody = obj.toString())
    }
}

private inline fun archiveTreeRequest(block: () -> JsonElement): JsonElement = try {
    block()
} catch (e: PikPakException) {
    when (e.errorMessage) {
        "unzip_password_required", "unzip_password_incorrect" -> throw ArchivePasswordException(
            incorrect = e.errorMessage == "unzip_password_incorrect",
            errorCode = e.errorCode,
            errorMessage = e.errorMessage,
            errorDescription = e.errorDescription,
            httpStatus = e.httpStatus,
            rawBody = e.rawBody,
            cause = e,
        )
        else -> throw e
    }
}
