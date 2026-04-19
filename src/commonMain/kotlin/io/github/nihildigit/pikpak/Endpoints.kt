package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/** Returns the storage quota for the authenticated account (`GET /drive/v1/about`). */
suspend fun PikPakClient.getQuota(): QuotaResponse {
    val response = http.request(
        method = HttpMethod.Get,
        url = "$DRIVE/drive/v1/about",
    )
    return json.decodeFromJsonElement(QuotaResponse.serializer(), response)
}

/**
 * Lists all non-trashed entries directly under [parentId]. Pass an empty
 * string for the user's root drive. Pages are followed automatically and
 * concatenated; for very large folders prefer [listFilesPaged] to stream.
 */
suspend fun PikPakClient.listFiles(parentId: String = "", pageSize: Int = 500): List<FileStat> {
    val all = mutableListOf<FileStat>()
    var pageToken = ""
    do {
        val page = listFilesPaged(parentId, pageSize, pageToken)
        all += page.files
        pageToken = page.nextPageToken
    } while (pageToken.isNotEmpty())
    return all
}

/**
 * Single-page list call. Useful when you want to stream pages or stop early.
 * Pass [pageToken] = "" for the first call; subsequent calls should pass the
 * value of [FileListPage.nextPageToken].
 */
internal suspend fun PikPakClient.listFilesPaged(
    parentId: String,
    pageSize: Int,
    pageToken: String,
): FileListPage {
    val query = mutableMapOf(
        "thumbnail_size" to "SIZE_MEDIUM",
        "limit" to pageSize.toString(),
        "parent_id" to parentId,
        "with_audit" to "false",
        "filters" to """{"trashed":{"eq":false}}""",
    )
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(DRIVE, FILES_PATH, query),
        captchaAction = "GET:/drive/v1/files",
    )
    return json.decodeFromJsonElement(FileListPage.serializer(), response)
}
