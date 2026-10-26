package io.github.nihildigit.pikpak

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

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
suspend fun PikPakClient.listTrash(pageSize: Int = 500): List<FileStat> =
    listFiles(parentId = "*", pageSize = pageSize, extraFilters = mapOf(FileFilter.trashed(true)))

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
 *   Default 2000, a backstop for a raised [timeout] rather than the limit that
 *   binds by default: the default rate limit refills five requests a second,
 *   so about 300 listings fit in the default minute, fewer when a folder takes
 *   several pages.
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
                    budgeted.map { folder ->
                        async(SuppressFolderPrefetch) { folder to listFiles(folder.id) }
                    }.awaitAll()
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

/** Lists live folders across every parent using the server's flat listing. */
suspend fun PikPakClient.listAllDriveFolders(pageSize: Int = 500): List<FileStat> =
    listFiles(parentId = "*", pageSize = pageSize, extraFilters = mapOf(FileFilter.kind(FileKind.FOLDER)))

/** Streams non-empty pages of live entries across every parent. */
fun PikPakClient.listAllDriveEntriesFlow(
    pageSize: Int = 500,
    extraFilters: Map<String, JsonElement> = emptyMap(),
): Flow<List<FileStat>> = flow {
    var token = ""
    do {
        val page = listFilesPaged(parentId = "*", pageSize = pageSize, pageToken = token, extraFilters = extraFilters)
        if (page.files.isNotEmpty()) emit(page.files)
        token = page.nextPageToken
    } while (token.isNotEmpty())
}

/**
 * Case-insensitive name search using flat listings, folders first and then file pages.
 * Breadcrumbs exclude [parentId] itself. Entries with missing or cyclic ancestry
 * are skipped because their path and membership in the subtree cannot be established.
 * Listings are not a snapshot: concurrent moves can change ancestry during a search.
 */
fun PikPakClient.searchDriveFlat(
    keyword: String,
    parentId: String = "",
    pageSize: Int = 500,
): Flow<SearchHit> {
    require(keyword.isNotEmpty()) { "keyword must not be empty" }
    return flow {
        val folders = listAllDriveFolders(pageSize)
        val folderMap = folders.associateBy { it.id }
        fun breadcrumb(entry: FileStat): List<String>? {
            if (entry.id == parentId) return null
            val names = mutableListOf<String>()
            val seen = mutableSetOf(entry.id)
            var ancestor = entry.parentId
            while (ancestor != parentId) {
                if (ancestor.isEmpty() || !seen.add(ancestor)) return null
                val folder = folderMap[ancestor] ?: return null
                names += folder.name
                ancestor = folder.parentId
            }
            return names.asReversed()
        }
        suspend fun match(entry: FileStat) {
            if (entry.name.contains(keyword, ignoreCase = true)) {
                breadcrumb(entry)?.let { emit(SearchHit(entry, it)) }
            }
        }
        folders.forEach { match(it) }
        listAllDriveEntriesFlow(pageSize, mapOf(FileFilter.kind(FileKind.FILE))).collect { entries ->
            entries.forEach { match(it) }
        }
    }
}

private class PendingFolder(val id: String, val breadcrumb: List<String>)
