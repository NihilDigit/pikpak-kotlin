package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val SHARE_PATH = "/drive/v1/share"

/**
 * Values of `share_status` on [ShareInfo], [ShareFolderPage] and [ShareSummary].
 * A share that cannot be read still answers HTTP 200 with no `error_code`;
 * this field is the only place the reason is given.
 */
object ShareStatus {
    const val OK = "OK"

    /** The share has a pass code and none was sent. */
    const val PASS_CODE_EMPTY = "PASS_CODE_EMPTY"

    /** The pass code sent does not match. */
    const val PASS_CODE_ERROR = "PASS_CODE_ERROR"

    /** Cancelled by its owner, or everything in it was deleted. */
    const val DELETED = "DELETED"
    const val EXPIRED = "EXPIRED"
    const val NOT_FOUND = "NOT_FOUND"
    const val AUDITING = "AUDITING"
    const val SENSITIVE_RESOURCE = "SENSITIVE_RESOURCE"
    const val SENSITIVE_WORD = "SENSITIVE_WORD"
    const val PROHIBITED = "PROHIBITED"
}

/**
 * A share could not be read: [status] is one of [ShareStatus] other than OK,
 * [statusText] the server's localised explanation.
 *
 * Thrown rather than returned because the unreadable answer is otherwise an
 * ordinary-looking page with no files, which a caller that forgot to check
 * would show as an empty share. [needsPassCode] covers the one case a caller
 * is expected to recover from, by asking the user.
 */
class ShareUnavailableException(
    val shareId: String,
    val status: String,
    val statusText: String,
) : PikPakException(
    errorCode = -1,
    errorMessage = "share $shareId unavailable: $status",
    errorDescription = statusText.ifEmpty { null },
) {
    val needsPassCode: Boolean
        get() = status == ShareStatus.PASS_CODE_EMPTY || status == ShareStatus.PASS_CODE_ERROR
}

/** Result of [createShare]. */
@Serializable
data class CreatedShare(
    @SerialName("share_id") val shareId: String = "",
    /** `https://mypikpak.com/s/<shareId>`. The pass code is not part of it. */
    @SerialName("share_url") val shareUrl: String = "",
    /** The pass code, generated (four lowercase alphanumerics) or as asked for; empty for a public share. */
    @SerialName("pass_code") val passCode: String = "",
)

/** One of the account's own shares, as [listMyShares] returns it. */
@Serializable
data class ShareSummary(
    @SerialName("share_id") val shareId: String = "",
    @SerialName("share_url") val shareUrl: String = "",
    /** [ShareStatus]. A share whose files were deleted stays listed as DELETED. */
    @SerialName("share_status") val shareStatus: String = "",
    @SerialName("share_status_text") val shareStatusText: String = "",
    /** Name of the first shared item. */
    val title: String = "",
    @SerialName("pass_code") val passCode: String = "",
    /** `publiclink`, `encryptedlink`, or the app it was sent to, e.g. `telegram`, `wechat`. */
    @SerialName("share_to") val shareTo: String = "",
    @SerialName("file_num") val fileNum: String = "0",
    /** The shared item when [fileNum] is 1; empty once the file is gone. */
    @SerialName("file_id") val fileId: String = "",
    @SerialName("file_kind") val fileKind: String = "",
    @SerialName("file_size") val fileSize: String = "0",
    /** `-1` for a share that never expires. */
    @SerialName("expiration_days") val expirationDays: String = "-1",
    /** RFC 3339, or `-1` for a share that never expires. */
    @SerialName("expiration_at") val expirationAt: String = "-1",
    @SerialName("restore_count") val restoreCount: String = "0",
    @SerialName("view_count") val viewCount: String = "0",
    @SerialName("create_time") val createTime: String = "",
) {
    val isOk: Boolean get() = shareStatus == ShareStatus.OK
}

/** Page of [listMyShares]. The wire field is `data`, not `shares`. */
@Serializable
data class ShareListPage(
    @SerialName("data") val shares: List<ShareSummary> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String = "",
)

/**
 * What [getShareInfo] learns from opening a share: its top-level items (first
 * page) and the [passCodeToken] every later call on it needs.
 */
@Serializable
data class ShareInfo(
    @SerialName("share_status") val shareStatus: String = "",
    @SerialName("share_status_text") val shareStatusText: String = "",
    /**
     * Stands in for the pass code in [listShareFiles] and [restoreShare]. Issued
     * for public shares too, so a caller never has to branch on whether the
     * share had a pass code.
     */
    @SerialName("pass_code_token") val passCodeToken: String = "",
    val title: String = "",
    @SerialName("file_num") val fileNum: String = "0",
    /** RFC 3339, or `-1` for a share that never expires. */
    @SerialName("expiration_at") val expirationAt: String = "",
    /** `-1` for unlimited. */
    @SerialName("restore_count_left") val restoreCountLeft: String = "",
    @SerialName("user_info") val owner: ShareOwner = ShareOwner(),
    /**
     * Top-level items. Ids are the owner's own file ids, the same ones [restoreShare]
     * takes; `parent_id` of these is the owner's folder, not something the reader
     * can open.
     */
    val files: List<FileStat> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String = "",
)

@Serializable
data class ShareOwner(
    @SerialName("user_id") val userId: String = "",
    /** Masked for anyone but the owner, e.g. `Ry**eI`. */
    val nickname: String = "",
    val avatar: String = "",
)

/** One page of a folder inside a share, from [listShareFiles]. */
@Serializable
data class ShareFolderPage(
    @SerialName("share_status") val shareStatus: String = "",
    @SerialName("share_status_text") val shareStatusText: String = "",
    val files: List<FileStat> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String = "",
)

/**
 * Answer to [restoreShare]. The copy is queued, not done: poll
 * `getTask(restoreTaskId)` until [TaskPhase.TERMINAL], then read
 * [restoredFileIds] off the task.
 */
@Serializable
data class ShareRestore(
    @SerialName("share_status") val shareStatus: String = "",
    @SerialName("share_status_text") val shareStatusText: String = "",
    /** `RESTORE_START` when a task was queued. */
    @SerialName("restore_status") val restoreStatus: String = "",
    @SerialName("restore_task_id") val restoreTaskId: String = "",
    @SerialName("file_id") val fileId: String = "",
)

/**
 * Shares [fileIds] as one link (`POST /drive/v1/share`).
 *
 * [requirePassCode] makes the server generate a four-character pass code.
 * [customPassCode] sets one instead and implies [requirePassCode]; the web
 * client only offers 4 to 10 letters or digits. Either way the code comes back
 * in [CreatedShare.passCode], and the link itself does not carry it.
 *
 * [expirationDays] is `-1` for a share that never expires, the web client's
 * default; the web client offers 7, 14 and 30, and 1 was accepted on
 * 2026-09-24. Items can be files or folders and need not share a parent.
 */
suspend fun PikPakClient.createShare(
    fileIds: List<String>,
    requirePassCode: Boolean = false,
    customPassCode: String = "",
    expirationDays: Int = -1,
): CreatedShare {
    require(fileIds.isNotEmpty()) { "fileIds must not be empty" }
    val locked = requirePassCode || customPassCode.isNotEmpty()
    val body = buildJsonObject {
        putJsonArray("file_ids") { fileIds.forEach { add(it) } }
        // share_to and pass_code_option say the same thing twice; the web
        // client always sends the pair, and the list reports share_to back.
        put("share_to", if (locked) "encryptedlink" else "publiclink")
        put("pass_code_option", if (locked) "REQUIRED" else "NOT_REQUIRED")
        // The web client sends this only on PATCH, when a code is changed
        // later. Creation honours it too (2026-09-24), which saves a request.
        if (customPassCode.isNotEmpty()) put("custom_pass_code", customPassCode)
        put("expiration_days", expirationDays)
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$SHARE_PATH",
        captchaAction = "POST:$SHARE_PATH",
    ) { jsonBody(json, body) }
    return json.decodeFromJsonElement(CreatedShare.serializer(), response)
}

/**
 * One page of the account's own shares, newest first
 * (`GET /drive/v1/share/list`). A share cancelled with [deleteShares] drops
 * out of the list; one whose files were deleted stays, as
 * [ShareStatus.DELETED] (both observed 2026-09-24).
 */
suspend fun PikPakClient.listMyShares(pageToken: String = "", pageSize: Int = 100): ShareListPage {
    val query = mutableMapOf(
        "limit" to pageSize.toString(),
        "thumbnail_size" to THUMBNAIL_SIZE,
    )
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, "$SHARE_PATH/list", query),
        captchaAction = "GET:$SHARE_PATH/list",
    )
    return json.decodeFromJsonElement(ShareListPage.serializer(), response)
}

/**
 * Cancels shares (`POST /drive/v1/share:batchDelete`). The shared files stay
 * in the drive. Afterwards a reader of the link gets [ShareStatus.DELETED]
 * rather than an error. No-op when [shareIds] is empty.
 */
suspend fun PikPakClient.deleteShares(shareIds: List<String>) {
    if (shareIds.isEmpty()) return
    val body = buildJsonObject { putJsonArray("ids") { shareIds.forEach { add(it) } } }
    http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$SHARE_PATH:batchDelete",
        captchaAction = "POST:$SHARE_PATH:batchDelete",
    ) { jsonBody(json, body) }
}

/**
 * Opens a share (`GET /drive/v1/share`): its top-level items and the
 * [ShareInfo.passCodeToken] that [listShareFiles] and [restoreShare] take in
 * place of the pass code. Pass [passCode] for a share that has one; it is
 * ignored by a share that has none.
 *
 * Throws [ShareUnavailableException] for anything but [ShareStatus.OK]. A
 * missing and a wrong pass code are told apart (`PASS_CODE_EMPTY`,
 * `PASS_CODE_ERROR`); both come back as HTTP 200 with no files.
 *
 * Observed 2026-09-24:
 * - No login is needed. A request with no `Authorization` header succeeds
 *   given `X-Device-Id` and `X-Client-Id`; without the client id it asks for
 *   a captcha token instead. This client always logs in, so the note is for
 *   callers building their own.
 * - A `parent_id` is ignored here: the top level comes back whatever it names.
 *   Folders are opened with [listShareFiles].
 * - The owner can read their own share like anyone else.
 */
suspend fun PikPakClient.getShareInfo(shareId: String, passCode: String = ""): ShareInfo {
    require(shareId.isNotEmpty()) { "shareId must not be empty" }
    val query = mutableMapOf(
        "share_id" to shareId,
        "limit" to SHARE_PAGE_SIZE,
        "thumbnail_size" to THUMBNAIL_SIZE,
    )
    if (passCode.isNotEmpty()) query["pass_code"] = passCode
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, SHARE_PATH, query),
        captchaAction = "GET:$SHARE_PATH",
    ) { sendClientId() }
    val info = json.decodeFromJsonElement(ShareInfo.serializer(), response)
    if (info.shareStatus != ShareStatus.OK) {
        throw ShareUnavailableException(shareId, info.shareStatus, info.shareStatusText)
    }
    return info
}

/**
 * One page of a folder inside a share (`GET /drive/v1/share/detail`). An empty
 * [parentId] lists the top level, which is how to page past the first page
 * [getShareInfo] returned. A folder is named by its own id, without the path
 * to it: a second-level folder opened directly on 2026-09-24.
 *
 * [passCodeToken] comes from [getShareInfo]. Only a share with a pass code
 * checks it: without it that share answers [ShareStatus.PASS_CODE_EMPTY],
 * thrown as [ShareUnavailableException], while a public share lists its
 * folders with an empty token (both observed 2026-09-24).
 */
suspend fun PikPakClient.listShareFiles(
    shareId: String,
    passCodeToken: String,
    parentId: String = "",
    pageToken: String = "",
): ShareFolderPage {
    require(shareId.isNotEmpty()) { "shareId must not be empty" }
    val query = mutableMapOf(
        "share_id" to shareId,
        "pass_code_token" to passCodeToken,
        "limit" to SHARE_PAGE_SIZE,
        "thumbnail_size" to THUMBNAIL_SIZE,
    )
    if (parentId.isNotEmpty()) query["parent_id"] = parentId
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, "$SHARE_PATH/detail", query),
        captchaAction = "GET:$SHARE_PATH/detail",
    ) { sendClientId() }
    val page = json.decodeFromJsonElement(ShareFolderPage.serializer(), response)
    if (page.shareStatus != ShareStatus.OK) {
        throw ShareUnavailableException(shareId, page.shareStatus, page.shareStatusText)
    }
    return page
}

/**
 * Copies items of someone else's share into this drive
 * (`POST /drive/v1/share/restore`). Returns at once with a task id; the copy
 * runs as a task of type `restore`, polled with [getTask] like an offline
 * download, and [restoredFileIds] on the finished task maps each shared id to
 * the new file's id.
 *
 * [fileIds] are ids from [ShareInfo.files] or [listShareFiles]. When they sit
 * below the top level, [ancestorIds] lists the shared folders from the top
 * level down to their parent. [toParentId] is the destination; empty means
 * the drive root. The copied items keep a
 * pointer back: `original_share_id`, `original_file_id` and `url` =
 * `https://mypikpak.com/s/<shareId>` in [FileStat.params].
 *
 * Restoring one's own share fails with `file_restore_own`, which PikPak sends
 * as error_code 9 like a captcha failure (observed 2026-09-24); the client
 * tells them apart by name and does not retry it.
 *
 * Not verified live: the probe account has no share but its own to restore
 * from. The request body is copied from a recorded exchange with Xunlei's
 * drive API, which is the same service under another host; the response
 * fields (`restore_task_id`, then `trace_file_ids` on the task) match what the
 * PikPak web client reads.
 */
suspend fun PikPakClient.restoreShare(
    shareId: String,
    passCodeToken: String,
    fileIds: List<String>,
    toParentId: String = "",
    ancestorIds: List<String> = emptyList(),
): ShareRestore {
    require(shareId.isNotEmpty()) { "shareId must not be empty" }
    require(fileIds.isNotEmpty()) { "fileIds must not be empty" }
    val body = buildJsonObject {
        put("share_id", shareId)
        put("pass_code_token", passCodeToken)
        putJsonArray("file_ids") { fileIds.forEach { add(it) } }
        putJsonArray("ancestor_ids") { ancestorIds.forEach { add(it) } }
        put("parent_id", toParentId)
        // Sent together with parent_id in the recorded exchange. Clients that
        // send neither report their copies landing in "Pack From Shared".
        put("specify_parent_id", true)
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "$DRIVE$SHARE_PATH/restore",
        captchaAction = "POST:$SHARE_PATH/restore",
    ) {
        sendClientId()
        jsonBody(json, body)
    }
    val result = json.decodeFromJsonElement(ShareRestore.serializer(), response)
    if (result.shareStatus.isNotEmpty() && result.shareStatus != ShareStatus.OK) {
        throw ShareUnavailableException(shareId, result.shareStatus, result.shareStatusText)
    }
    return result
}

/**
 * For a finished `restore` task from [restoreShare]: shared file id to the id
 * of its copy in this drive, read from the `trace_file_ids` param, which holds
 * a JSON object as a string. Empty for any other task, or while the task has
 * not written it yet.
 */
val OfflineTask.restoredFileIds: Map<String, String>
    get() {
        val raw = params["trace_file_ids"] ?: return emptyMap()
        val obj = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return emptyMap()
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }.toMap()
    }

/**
 * The share id in a share link: `https://mypikpak.com/s/<shareId>`, with or
 * without a trailing folder segment, query or fragment. Null for anything
 * else. [FileStat.params] carries the same link as `url` on files restored
 * from a share, and the bare id as `original_share_id`.
 *
 * A pass code is never part of the link; it travels beside it.
 */
fun shareIdFromUrl(url: String): String? {
    val match = SHARE_URL.find(url.trim()) ?: return null
    return match.groupValues[1]
}

private val SHARE_URL = Regex("""^https?://(?:www\.)?mypikpak\.com/s/([A-Za-z0-9_-]+)""")

private const val SHARE_PAGE_SIZE = "100"

/**
 * The share reads accept anonymous callers and take the caller's client id
 * from this header, not from the access token. Without it a fresh session's
 * first read fails with captcha_invalid, detail "client_id not match", and the
 * captcha refresh does not cure it; with it the same read succeeds (observed
 * 2026-09-24). Restore gets it too because Xunlei's web client sends it on
 * every request; that one is not verified.
 */
private fun HttpRequestBuilder.sendClientId() {
    headers.set("X-Client-Id", PikPakConstants.CLIENT_ID)
}
