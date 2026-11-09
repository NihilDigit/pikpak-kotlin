package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A stale server version requires a new scan; never commit this page as a successful delta. */
class FileSyncVersionOutdatedException : RuntimeException("File sync version is outdated")

/**
 * Completed rows changed since [sinceSyncTime], including trash and permanent-delete markers.
 * The filter named modified_time compares params.db_modify_time. Keep the same lower bound
 * while following page tokens. An empty lower bound starts a full scan.
 */
suspend fun PikPakClient.listFileChangesPage(
    sinceSyncTime: String = "", pageToken: String = "", pageSize: Int = 500,
): FileListPage = withDefaultApiPriority(
    if (sinceSyncTime.isEmpty()) ApiRequestPriority.BACKGROUND else ApiRequestPriority.INTERACTIVE,
) {
    require(pageSize in 1..500)
    val query = mutableMapOf(
        "parent_id" to "*", "limit" to pageSize.toString(), "with_audit" to "true",
        "filters" to buildJsonObject {
            putJsonObject("phase") { put("eq", TaskPhase.COMPLETE) }
            if (sinceSyncTime.isNotEmpty()) putJsonObject("modified_time") { put("gt", sinceSyncTime) }
        }.toString(),
    )
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val raw = http.request(HttpMethod.Get, buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files", query),
        captchaAction = "GET:/drive/v1/files")
    json.decodeFromJsonElement(FileListPage.serializer(), raw).also {
        if (it.versionOutdated) throw FileSyncVersionOutdatedException()
        check(it.syncTime.isNotBlank()) { "File sync response missing sync_time" }
        accountState.observeFiles(it.files)
    }
}

/**
 * Emits every page, including an empty final page. Stage rows until normal completion;
 * cancellation, failure or version invalidation must not commit a scan or advance a cursor.
 * Page sync times are exposed verbatim; use [syncFileChanges] for a conservative checkpoint.
 */
fun PikPakClient.fileChangesPages(sinceSyncTime: String = "", pageSize: Int = 500): Flow<FileListPage> = flow {
    var token = ""
    val seen = HashSet<String>()
    do {
        val page = listFileChangesPage(sinceSyncTime, token, pageSize)
        emit(page)
        token = page.nextPageToken
        check(token.isEmpty() || seen.add(token)) { "Repeated file sync page token" }
    } while (token.isNotEmpty())
}

/** Full scan of completed rows, including trashed rows; not a server snapshot transaction. */
fun PikPakClient.scanFilesPages(pageSize: Int = 500): Flow<FileListPage> = fileChangesPages(pageSize = pageSize)

data class FileSyncCheckpoint(val firstSyncTime: String, val lastSyncTime: String) {
    /** Terminal db_modify_time scan watermark; publish only after all pages succeed.
     * Live pagination with concurrent updates was verified on 2026-11-10 (wiki Measurements).
     */
    val syncTime: String get() = lastSyncTime
}

/** Bounded-memory ingestion. Persist the returned checkpoint only after all page writes succeed. */
suspend fun PikPakClient.consumeFileChanges(
    sinceSyncTime: String = "", pageSize: Int = 500,
    onPage: suspend (FileListPage) -> Unit,
): FileSyncCheckpoint {
    var first: String? = null
    var last = ""
    fileChangesPages(sinceSyncTime, pageSize).collect { page ->
        if (first == null) first = page.syncTime
        last = page.syncTime
        onPage(page)
    }
    return FileSyncCheckpoint(requireNotNull(first), last)
}

data class FileDeltaSyncResult(
    val upserted: List<FileStat>,
    /** Removal from the active-file view; this does NOT distinguish trash from permanent deletion. */
    val deletedIds: List<String>,
    val trashed: List<FileStat>,
    val checkpoint: FileSyncCheckpoint,
) {
    val syncTime: String get() = checkpoint.syncTime
}

/** Convenience collector. Large SQLite replicas should use [consumeFileChanges] instead. */
suspend fun PikPakClient.syncFileChanges(sinceSyncTime: String, pageSize: Int = 500): FileDeltaSyncResult {
    val rows = LinkedHashMap<String, FileStat>()
    val checkpoint = consumeFileChanges(sinceSyncTime, pageSize) { page ->
        for (file in page.files) rows[file.id] = file
    }
    val trashed = rows.values.filter { it.trashed }
    return FileDeltaSyncResult(rows.values.filterNot { it.trashed }, trashed.map { it.id }, trashed, checkpoint)
}

/**
 * Lightweight page watermark. This is a conservative starting point, not necessarily
 * the newest row. Never construct a sync cursor from the client clock: an empty server
 * response can echo that lower bound.
 */
suspend fun PikPakClient.getFileSyncWatermark(sinceSyncTime: String = ""): String =
    listFileChangesPage(sinceSyncTime, pageSize = 1).syncTime

/**
 * Drains changes to obtain the terminal server watermark without retaining rows.
 * With no known lower bound this scans the drive. For an existing replica, ingest those
 * changes with [consumeFileChanges] instead of discarding them through this helper.
 */
suspend fun PikPakClient.getCurrentSyncTime(sinceSyncTime: String = ""): String =
    consumeFileChanges(sinceSyncTime) {}.lastSyncTime

/** Repeated ids query parameters, not a comma-separated ids value. Missing ids stay missing. */
suspend fun PikPakClient.batchGetFiles(ids: List<String>, usage: String? = null): List<FileStat> {
    require(ids.all { it.isNotBlank() })
    val result = LinkedHashMap<String, FileStat>()
    for (chunk in ids.distinct().chunked(100)) {
        val params = buildList {
            for (id in chunk) add("ids" to id)
            if (!usage.isNullOrBlank()) add("usage" to usage)
        }
        val raw = http.request(HttpMethod.Get,
            buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files:batchGet", params),
            captchaAction = "GET:/drive/v1/files:batchGet")
        val page = json.decodeFromJsonElement(FileListPage.serializer(), raw)
        accountState.observeFiles(page.files)
        for (file in page.files) if (file.id in chunk) result[file.id] = file
    }
    return ids.distinct().mapNotNull { result[it] }
}
