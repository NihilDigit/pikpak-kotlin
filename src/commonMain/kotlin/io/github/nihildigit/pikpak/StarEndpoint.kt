package io.github.nihildigit.pikpak

/**
 * Stars [ids] (`POST /drive/v1/files:star`). No-op when [ids] is empty.
 *
 * The star is a system tag, not a flag. Observed 2026-09-24: a starred
 * item's listing entry carries `tags` `[{"name":"STAR",...}]` while its
 * `starred` field stays false, and [FileDetail.starred] reads false as well.
 * Read the star from [listStarred], not from those fields.
 */
suspend fun PikPakClient.starFiles(ids: List<String>) =
    batchOperate(ids, "star")

/** Removes the star from [ids] (`POST /drive/v1/files:unstar`). No-op when [ids] is empty. */
suspend fun PikPakClient.unstarFiles(ids: List<String>) =
    batchOperate(ids, "unstar")

/**
 * Lists the starred, non-trashed items under [parentId]. The default `*`
 * lists them from every folder of the drive; `""` is the root and any other
 * id one folder, children only (observed 2026-09-24: a starred grandchild of
 * the folder asked for is absent, and present under `*`).
 *
 * The web client only ever asks one folder at a time; `*` is what the trash
 * listing uses and works the same way here. [pageSize] is ignored by the
 * server with this filter: asking for 2 per page returned all 6 starred items
 * in one page with no `next_page_token`, under `*` and under a single folder
 * alike. Pages are still followed in case a larger set is split.
 */
suspend fun PikPakClient.listStarred(parentId: String = "*", pageSize: Int = 500): List<FileStat> =
    listFiles(parentId, pageSize, extraFilters = mapOf(FileFilter.starred()))
