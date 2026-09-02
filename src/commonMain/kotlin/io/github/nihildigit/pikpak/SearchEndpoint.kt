package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/**
 * Substring search (case-insensitive) over entry names under [parentId]. Scope
 * is one folder deep, and the matching is client-side after paginating the
 * listing: PikPak has no name search on this endpoint at all — `name` as a
 * filter field answers 404, and `q` / `search_text` are accepted and ignored.
 * For recursive searches, call this per-folder and merge.
 *
 * @param keyword substring to match. Empty string is rejected.
 * @param parentId folder whose direct children to search. `""` (default) is
 *   the root drive.
 * @param includeTrashed also match entries in the trash whose original parent
 *   was [parentId]. The trash listing is account-wide, so it is filtered back
 *   down to [parentId] here — otherwise a search scoped to one folder returned
 *   deleted files from anywhere in the drive.
 */
suspend fun PikPakClient.searchFiles(
    keyword: String,
    parentId: String = "",
    includeTrashed: Boolean = false,
): List<FileStat> {
    require(keyword.isNotEmpty()) { "keyword must not be empty" }
    val live = listFiles(parentId)
    val pool = if (includeTrashed) live + listTrash().filter { it.parentId == parentId } else live
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
        "thumbnail_size" to THUMBNAIL_SIZE,
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
