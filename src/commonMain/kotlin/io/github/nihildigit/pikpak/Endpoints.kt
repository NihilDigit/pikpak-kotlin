package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/**
 * Thumbnail variant asked for on every listing. SIZE_SMALL rather than
 * SIZE_MEDIUM: the SDK never decodes a thumbnail, and a 500-entry page carries
 * one signed thumbnail URL per row either way. The parameter cannot simply be
 * dropped — PikPak then picks its own default.
 */
internal const val THUMBNAIL_SIZE = "SIZE_SMALL"

/**
 * Server-side filter fragments for [listFiles] and [listFilesPaged].
 *
 * PikPak's `filters` query parameter is a JSON object of
 * `field -> {operator: value}`. What the server actually accepts is narrow:
 * probing on 2026-09-02 found `kind` and `trashed` working, and `name`
 * rejected with a 404 under every operator tried (eq, like, contains, in,
 * prefix) — the field itself is refused, not the operator. The `q`,
 * `search_text` and `name` query parameters are accepted and then ignored,
 * returning the unfiltered page. There is therefore no server-side name
 * search on this endpoint; matching a name still means paging and comparing
 * locally, which is what [getFolderId] and [searchFiles] do.
 *
 * [eq] is public so a caller can try a field this object does not name. Expect
 * a 404 rather than an empty page when the server does not know it.
 */
object FileFilter {
    /** `{"eq": value}` — build your own field entry when you need one this object does not name. */
    fun eq(value: String): JsonElement = buildJsonObject { put("eq", value) }

    /** Match one of [FileKind.FOLDER] / [FileKind.FILE]. Verified working. */
    fun kind(value: String): Pair<String, JsonElement> = "kind" to eq(value)
}

/** Returns the storage quota for the authenticated account (`GET /drive/v1/about`). */
suspend fun PikPakClient.getQuota(): QuotaResponse {
    val response = http.request(
        method = HttpMethod.Get,
        url = "$DRIVE/drive/v1/about",
        captchaAction = "GET:/drive/v1/about",
    )
    return json.decodeFromJsonElement(QuotaResponse.serializer(), response)
}

/**
 * Lists all non-trashed entries directly under [parentId]. Pass an empty
 * string for the user's root drive. Pages are followed automatically and
 * concatenated; for very large folders prefer [listFilesPaged] to stream.
 *
 * [extraFilters] narrows the query on the server — see [FileFilter] for what
 * the server will actually accept:
 *
 *     listFiles(parentId, extraFilters = mapOf(FileFilter.kind(FileKind.FOLDER)))
 */
suspend fun PikPakClient.listFiles(
    parentId: String = "",
    pageSize: Int = 500,
    extraFilters: Map<String, JsonElement> = emptyMap(),
): List<FileStat> {
    val all = mutableListOf<FileStat>()
    var pageToken = ""
    do {
        val page = listFilesPaged(parentId, pageSize, pageToken, extraFilters)
        all += page.files
        pageToken = page.nextPageToken
    } while (pageToken.isNotEmpty())
    return all
}

/**
 * Single-page list call. Useful when you want to stream pages or stop early.
 * Pass [pageToken] = "" for the first call; subsequent calls should pass the
 * value of [FileListPage.nextPageToken].
 *
 * [extraFilters] is merged into the built-in `trashed=false` filter. Passing a
 * `trashed` entry of your own overrides it — see [listTrash] for the account's
 * trash, which is not scoped to a parent.
 */
suspend fun PikPakClient.listFilesPaged(
    parentId: String = "",
    pageSize: Int = 500,
    pageToken: String = "",
    extraFilters: Map<String, JsonElement> = emptyMap(),
): FileListPage {
    val filters = buildJsonObject {
        // trashed is a real boolean on the wire, not the string "false".
        putJsonObject("trashed") { put("eq", false) }
        for ((field, expr) in extraFilters) put(field, expr)
    }
    val query = mutableMapOf(
        "thumbnail_size" to THUMBNAIL_SIZE,
        "limit" to pageSize.toString(),
        "parent_id" to parentId,
        "with_audit" to "false",
        "filters" to filters.toString(),
    )
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, FILES_PATH, query),
        captchaAction = "GET:/drive/v1/files",
    )
    return json.decodeFromJsonElement(FileListPage.serializer(), response)
}
