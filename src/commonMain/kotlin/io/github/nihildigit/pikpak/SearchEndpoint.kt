package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/**
 * Substring search (case-insensitive) over entry names under [parentId]. Scope
 * is one folder deep — PikPak's server-side `filters` API only supports `eq`,
 * so substring matching is done client-side after paginating the listing. For
 * recursive searches, call this per-folder and merge.
 *
 * @param keyword substring to match. Empty string is rejected.
 * @param parentId folder whose direct children to search. `""` (default) is
 *   the root drive.
 * @param includeTrashed include entries currently in the trash.
 */
suspend fun PikPakClient.searchFiles(
    keyword: String,
    parentId: String = "",
    includeTrashed: Boolean = false,
): List<FileStat> {
    require(keyword.isNotEmpty()) { "keyword must not be empty" }
    val pool = if (includeTrashed) listFiles(parentId) + listTrash() else listFiles(parentId)
    return pool.filter { it.name.contains(keyword, ignoreCase = true) }
}

/**
 * Lists every item currently in the account's trash (any parent). Pairs with
 * [batchUntrash] to restore and [batchDelete] to purge permanently.
 */
suspend fun PikPakClient.listTrash(pageSize: Int = 500): List<FileStat> {
    val all = mutableListOf<FileStat>()
    var pageToken = ""
    do {
        val page = listTrashPaged(pageSize, pageToken)
        all += page.files
        pageToken = page.nextPageToken
    } while (pageToken.isNotEmpty())
    return all
}

private suspend fun PikPakClient.listTrashPaged(
    pageSize: Int,
    pageToken: String,
): FileListPage {
    val query = mutableMapOf(
        "thumbnail_size" to "SIZE_MEDIUM",
        "limit" to pageSize.toString(),
        "with_audit" to "false",
        "filters" to """{"trashed":{"eq":true}}""",
    )
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, FILES_PATH, query),
        captchaAction = "GET:/drive/v1/files",
    )
    return json.decodeFromJsonElement(FileListPage.serializer(), response)
}
