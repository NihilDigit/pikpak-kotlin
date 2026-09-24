package io.github.nihildigit.pikpak

/**
 * Outcome of [pruneOfflineOutput]. Paths are relative to the task's output
 * root, in the form [resolveMagnet] reports them.
 */
data class PruneResult(
    /** Files and folders deleted. A folder is listed once, with a trailing `/`, and its contents are not. */
    val deleted: List<String>,
    /**
     * Kept paths with no file in the output. PikPak's offline download drops
     * some of a torrent's files on its own — advertising `.txt` and `.html`
     * files were missing from a task that had resolved them — so an entry here
     * is expected, not a failure.
     */
    val missing: Set<String>,
)

/**
 * Deletes everything in a completed offline task's output that is not in
 * [keep], permanently, bypassing the trash.
 *
 * This fills a gap in the API rather than adding policy: [createUrlFile] takes
 * a whole magnet and has no way to pick files, so the way to fetch part of a
 * torrent by offline download is to fetch all of it and remove the rest. What
 * to keep, and whether an offline download is worth it over [instantCreate],
 * stays with the caller.
 *
 * [keep] uses the paths [resolveMagnet] returns for the same magnet. Measured
 * 2026-09-24 they match the output one to one: a torrent with a single root
 * folder lands as a folder of that name holding the same relative paths,
 * subfolders included, and a single-file torrent lands as the file itself. A
 * torrent with several top-level entries was not observed; its entries are
 * expected under the output folder with the names [resolveMagnet] keeps.
 *
 * A folder with nothing to keep is deleted whole and not listed, so pruning a
 * pack down to one episode costs one listing per level on the kept path, not
 * one per folder. Polling the task until it completes is the caller's job, as
 * for every offline endpoint here.
 */
suspend fun PikPakClient.pruneOfflineOutput(task: OfflineTask, keep: Set<String>): PruneResult {
    require(task.phase == TaskPhase.COMPLETE) { "task ${task.id} is ${task.phase}, not complete" }
    require(task.fileId.isNotEmpty()) { "task ${task.id} has no output file" }

    val root = getFile(task.fileId)
    if (root.kind != FileKind.FOLDER) {
        // Single-file torrent: the output is the file, named as resolveMagnet names it
        if (root.name in keep) return PruneResult(deleted = emptyList(), missing = keep - root.name)
        batchDelete(listOf(root.id))
        return PruneResult(deleted = listOf(root.name), missing = keep)
    }

    val doomedIds = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val found = mutableSetOf<String>()

    suspend fun walk(folderId: String, prefix: String) {
        for (entry in listFiles(folderId)) {
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            when {
                entry.isFolder && keep.none { it.startsWith("$path/") } -> {
                    doomedIds += entry.id
                    deleted += "$path/"
                }
                entry.isFolder -> walk(entry.id, path)
                path in keep -> found += path
                else -> {
                    doomedIds += entry.id
                    deleted += path
                }
            }
        }
    }
    walk(task.fileId, "")

    batchDelete(doomedIds)
    return PruneResult(deleted = deleted, missing = keep - found)
}
