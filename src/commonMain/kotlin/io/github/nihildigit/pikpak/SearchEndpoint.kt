package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

private const val DRIVE = PikPakConstants.DRIVE_BASE
private const val FILES_PATH = "/drive/v1/files"

/**
 * Substring search (case-insensitive) over entry names under [parentId]. Scope
 * is one folder deep, and the matching is client-side after paginating the
 * listing: PikPak has no name search on this endpoint at all — `name` as a
 * filter field answers 404, and `q` / `search_text` are accepted and ignored.
 * For the whole subtree under [parentId], use [searchFilesRecursive].
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
 *
 * `parent_id=*` is what makes it any parent. Without it the server lists only
 * items trashed from the root (observed 2026-09-23): a file trashed from a
 * subfolder is in the trash, restorable, and missing from this listing.
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
        "parent_id" to "*",
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

/**
 * One match from [searchFilesRecursive], with enough context to tell two
 * files of the same name apart.
 */
data class SearchHit(
    val file: FileStat,
    /**
     * Folder names from the search root down to [file]'s parent, the root
     * itself excluded. Empty for a direct child of the search root.
     *
     * Names, not ids: PikPak permits duplicate names in one parent, so this is
     * for display and not a path that can be resolved back. Use
     * [FileStat.parentId] to address the containing folder.
     */
    val breadcrumb: List<String>,
) {
    /** The breadcrumb joined with `/`, relative to the search root. Empty at the root. */
    val parentPath: String get() = breadcrumb.joinToString("/")

    /** [parentPath] with the entry's own name appended. */
    val path: String get() = (breadcrumb + file.name).joinToString("/")
}

/**
 * Traversal budget for [searchFilesRecursive].
 *
 * A drive-wide search has no server-side endpoint behind it — see
 * [searchFilesRecursive] — so its cost is one listing request per folder and
 * the tree decides how many that is. Every default here is chosen to keep an
 * interactive search interactive on a large drive rather than to guarantee
 * completeness; raise them deliberately.
 *
 * @param maxDepth folder levels below the search root to descend into. `0`
 *   lists only the root, which is what [searchFiles] does. Default 8.
 * @param maxFolders folders listed in total, the search root included.
 *   Default 2000, i.e. at the default rate limit roughly the number of
 *   requests that fit in [timeout] anyway.
 * @param timeout wall-clock budget for the whole traversal. Checked between
 *   batches, so an in-flight listing is allowed to finish. Default 60s.
 * @param concurrency folders listed at once. The ceiling that matters is the
 *   client's [RateLimiter] — this only decides how many requests are queued
 *   against it, and raising it past the refill rate buys nothing.
 *   [PikPakClient.accountConnectionBudget] is deliberately not consulted: it
 *   budgets CDN connections for range reads, and a listing is an API call
 *   that takes a rate-limiter token instead.
 */
data class RecursiveSearchLimits(
    val maxDepth: Int = 8,
    val maxFolders: Int = 2_000,
    val timeout: Duration = 60.seconds,
    val concurrency: Int = 4,
) {
    init {
        require(maxDepth >= 0) { "maxDepth must be >= 0, got $maxDepth" }
        require(maxFolders >= 1) { "maxFolders must be >= 1, got $maxFolders" }
        require(timeout > Duration.ZERO) { "timeout must be positive, got $timeout" }
        require(concurrency >= 1) { "concurrency must be >= 1, got $concurrency" }
    }
}

/**
 * Case-insensitive substring search over every entry in the subtree under
 * [parentId], emitted as it is found.
 *
 * This is a client-side breadth-first walk, because PikPak offers nothing
 * else: `name` is refused as a filter field on `/drive/v1/files` under every
 * operator, `q` / `search_text` are accepted and ignored, and no separate
 * search endpoint exists. One listing request per folder is the floor.
 *
 * Breadth-first rather than depth-first so that shallow matches — the ones a
 * user is usually looking for — arrive first. Folders are listed
 * [RecursiveSearchLimits.concurrency] at a time; results of a batch are
 * emitted before the next goes out, so a collector sees hits throughout
 * rather than at the end.
 *
 * Exhausting any budget in [limits] **ends the flow normally** with the hits
 * found so far. Partial results are the useful answer for a search box, and a
 * caller that needs to know whether the walk completed should compare against
 * the budget it set. Cancelling the collector cancels the in-flight listings.
 *
 * Folders match on their own name and are still descended into. Trashed
 * entries are never included — unlike [searchFiles] there is no opt-in, since
 * the trash is account-wide and has no subtree to walk.
 *
 * @param keyword substring to match. Empty string is rejected.
 * @param parentId subtree root. `""` (default) is the whole drive.
 */
fun PikPakClient.searchFilesRecursive(
    keyword: String,
    parentId: String = "",
    limits: RecursiveSearchLimits = RecursiveSearchLimits(),
): Flow<SearchHit> {
    // Eagerly, not inside the builder: a flow that only rejects its argument
    // once someone collects it reports the mistake at the wrong call site.
    require(keyword.isNotEmpty()) { "keyword must not be empty" }
    return flow {
        val deadline = TimeSource.Monotonic.markNow() + limits.timeout
        // One set for folders and files alike. A folder already queued is not
        // queued twice, and a file already emitted is not emitted twice —
        // PikPak gives each entry a single parent, so a repeat means the
        // server sent one, not that the walk went round in a circle.
        val seen = mutableSetOf(parentId)
        var listed = 0
        var level = listOf(PendingFolder(parentId, emptyList()))
        var depth = 0
        while (level.isNotEmpty() && depth <= limits.maxDepth) {
            val next = mutableListOf<PendingFolder>()
            for (batch in level.chunked(limits.concurrency)) {
                if (deadline.hasPassedNow()) return@flow
                val budgeted = batch.take(limits.maxFolders - listed)
                if (budgeted.isEmpty()) return@flow
                listed += budgeted.size
                val listings = coroutineScope {
                    budgeted.map { folder -> async { folder to listFiles(folder.id) } }.awaitAll()
                }
                for ((folder, entries) in listings) {
                    for (entry in entries) {
                        if (!seen.add(entry.id)) continue
                        if (entry.name.contains(keyword, ignoreCase = true)) {
                            emit(SearchHit(entry, folder.breadcrumb))
                        }
                        if (entry.isFolder) {
                            next += PendingFolder(entry.id, folder.breadcrumb + entry.name)
                        }
                    }
                }
            }
            level = next
            depth++
        }
    }
}

/**
 * [searchFilesRecursive] collected into a list. The same walk with the same
 * budgets; the caller just waits for all of it.
 */
suspend fun PikPakClient.searchFilesRecursiveList(
    keyword: String,
    parentId: String = "",
    limits: RecursiveSearchLimits = RecursiveSearchLimits(),
): List<SearchHit> = searchFilesRecursive(keyword, parentId, limits).toList()

private class PendingFolder(val id: String, val breadcrumb: List<String>)
