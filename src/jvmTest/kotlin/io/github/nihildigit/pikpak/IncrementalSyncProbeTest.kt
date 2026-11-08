package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Probes the server's incremental sync protocol (`sync_time` + `filters.modified_time.gt`),
 * `files:batchGet`, and `batchCopy` task completion metadata (`params.file_ids`), as well
 * as how folder move / rename / star / trash / untrash / delete affect child items in
 * incremental delta streams.
 *
 * Opt in with PIKPAK_PROBE=1. All mutations occur inside a temporary `piko-sync-probe-<ts>`
 * folder that is permanently deleted in `finally`.
 */
class IncrementalSyncProbeTest {

    @Test
    fun `public sync APIs track completed batch operations`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no live credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var root: String? = null
        try {
            var cursor = client.getCurrentSyncTime()
            check(cursor.isNotBlank())
            root = client.createFolder("", "piko-sync-api-probe-${System.currentTimeMillis()}")
            val source = client.createFolder(root, "source")
            val child = client.createFolder(source, "child")
            val target = client.createFolder(root, "target")
            suspend fun changes(): FileDeltaSyncResult {
                val result = client.syncFileChanges(cursor, pageSize = 2)
                cursor = result.syncTime
                println("[sync-api] upserts=${result.upserted.size} removals=${result.deletedIds.size} cursor=${result.syncTime}")
                return result
            }
            check(changes().upserted.map { it.id }.containsAll(listOf(root, source, child, target)))
            check(client.batchGetFiles(listOf(source, child)).map { it.id }.toSet() == setOf(source, child))
            client.batchMove(listOf(source), target)
            check(client.batchGetFiles(listOf(source)).single().parentId == target)
            check(changes().upserted.map { it.id }.containsAll(listOf(source, child)))
            client.starFiles(listOf(child))
            check(changes().upserted.any { it.id == child && it.isStarred })
            client.unstarFiles(listOf(child))
            check(changes().upserted.any { it.id == child && !it.isStarred })
            client.rename(source, "renamed")
            check(changes().upserted.any { it.id == source && it.name == "renamed" })
            val copies = client.batchCopy(listOf(source), root)
            check(copies.isNotEmpty())
            check(client.awaitTasks(copies).all { it.phase == TaskPhase.COMPLETE })
            val copied = changes().upserted.filter { it.id !in setOf(root, source, child, target) }
            check(copied.size >= 2)
            client.batchTrash(listOf(source))
            check(changes().deletedIds.containsAll(listOf(source, child)))
            client.batchUntrash(listOf(source))
            check(changes().upserted.map { it.id }.containsAll(listOf(source, child)))
            client.batchDelete(listOf(source))
            check(changes().deletedIds.containsAll(listOf(source, child)))
        } finally {
            try {
                root?.let { client.batchDelete(listOf(it)) }
            } finally { client.close() }
        }
    }

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    private data class DeltaResult(
        val syncTime: String,
        val files: List<JsonObject>,
    )

    private suspend fun fetchDelta(
        client: PikPakClient,
        sinceSyncTime: String,
        includeTrashedFilter: Boolean? = null,
    ): DeltaResult {
        val filterStr = when (includeTrashedFilter) {
            null -> """{"phase":{"eq":"PHASE_TYPE_COMPLETE"},"modified_time":{"gt":"$sinceSyncTime"}}"""
            true -> """{"phase":{"eq":"PHASE_TYPE_COMPLETE"},"trashed":{"eq":true},"modified_time":{"gt":"$sinceSyncTime"}}"""
            false -> """{"phase":{"eq":"PHASE_TYPE_COMPLETE"},"trashed":{"eq":false},"modified_time":{"gt":"$sinceSyncTime"}}"""
        }
        val raw = client.http.request(
            method = HttpMethod.Get,
            url = buildUrl(
                PikPakConstants.DRIVE_BASE,
                "/drive/v1/files",
                mapOf(
                    "parent_id" to "*",
                    "limit" to "500",
                    "with_audit" to "true",
                    "filters" to filterStr,
                ),
            ),
            captchaAction = "GET:/drive/v1/files",
        ).jsonObject
        val syncTime = raw["sync_time"]?.jsonPrimitive?.content.orEmpty()
        val nextPageToken = raw["next_page_token"]?.jsonPrimitive?.content.orEmpty()
        println("  [raw top-level keys=${raw.keys}, sync_time='$syncTime', next_page_token='$nextPageToken']")
        val files = raw["files"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        return DeltaResult(syncTime = syncTime, files = files)
    }

    private fun JsonObject.summary(): String {
        val id = this["id"]?.jsonPrimitive?.content
        val name = this["name"]?.jsonPrimitive?.content
        val parentId = this["parent_id"]?.jsonPrimitive?.content
        val trashed = this["trashed"]?.jsonPrimitive?.booleanOrNull
        val modified = this["modified_time"]?.jsonPrimitive?.content
        val dbModify = this["params"]?.jsonObject?.get("db_modify_time")?.jsonPrimitive?.content
        val tags = this["tags"]?.toString()
        return "id=$id name=$name parent=$parentId trashed=$trashed modified=$modified db_modify=$dbModify tags=$tags"
    }

    private suspend fun waitTaskIfAny(client: PikPakClient, actionName: String, resp: JsonObject) {
        val taskId = resp["task_id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        println("  [$actionName raw response] $resp")
        if (taskId == null) return
        for (attempt in 1..15) {
            val tasksRaw = client.http.request(
                method = HttpMethod.Get,
                url = buildUrl(
                    PikPakConstants.DRIVE_BASE,
                    "/drive/v1/tasks",
                    mapOf(
                        "with" to "reference_resource",
                        "type" to "",
                        "thumbnail_size" to "SIZE_SMALL",
                        "limit" to "10000",
                        "filters" to """{"id":{"in":"$taskId"}}""",
                    ),
                ),
                captchaAction = "GET:/drive/v1/tasks",
            ).jsonObject
            val tasksArr = tasksRaw["tasks"]?.jsonArray.orEmpty()
            val phase = tasksArr.firstOrNull()?.jsonObject?.get("phase")?.jsonPrimitive?.content
            println("  [$actionName poll $attempt] phase=$phase task=${tasksArr.firstOrNull()}")
            if (phase == "PHASE_TYPE_COMPLETE") break
            delay(500)
        }
    }

    @Test
    fun `probe incremental sync and batch endpoints`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        var probeRootId: String? = null
        try {
            client.login()

            // Step 0: Get initial server sync_time by querying parent_id=* with a recent modified_time.gt
            val oneMinAgo = java.time.OffsetDateTime.now()
                .minusMinutes(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .toString()
            val initDelta = fetchDelta(client, oneMinAgo)
            println("=== [Step 0] Initial fetchDelta(since=$oneMinAgo) returned sync_time='${initDelta.syncTime}', count=${initDelta.files.size} ===")
            var cursor = initDelta.syncTime.ifEmpty { oneMinAgo }

            // Step 1: Create probe hierarchy:
            // probeRoot
            //   ├─ folderA
            //   │    └─ childFolder
            //   └─ folderB
            probeRootId = client.createFolder("", "piko-sync-probe-${System.currentTimeMillis()}")
            val folderA = client.createFolder(probeRootId, "folderA")
            val childFolder = client.createFolder(folderA, "childFolder")
            val folderB = client.createFolder(probeRootId, "folderB")
            println("=== [Step 1] Created probeRoot=$probeRootId, folderA=$folderA, childFolder=$childFolder, folderB=$folderB ===")

            val delta1 = fetchDelta(client, cursor)
            println("[Delta 1 after create] sync_time=${delta1.syncTime}, count=${delta1.files.size}")
            delta1.files.forEach { println("  -> ${it.summary()}") }
            cursor = delta1.syncTime.ifEmpty { cursor }

            // Step 2: Test files:batchGet with repeated ids= query params
            val batchGetUrl = "${PikPakConstants.DRIVE_BASE}/drive/v1/files:batchGet?ids=$folderA&ids=$childFolder&ids=$folderB"
            val batchGetRaw = runCatching {
                client.http.request(
                    method = HttpMethod.Get,
                    url = batchGetUrl,
                    captchaAction = "GET:/drive/v1/files:batchGet",
                )
            }
            println("=== [Step 2] files:batchGet response ===")
            println("  -> ${batchGetRaw.getOrNull() ?: batchGetRaw.exceptionOrNull()}")

            // Step 3: Move non-empty folderA into folderB
            val moveResp = client.http.request(
                method = HttpMethod.Post,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files:batchMove",
                captchaAction = "POST:/drive/v1/files:batchMove",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderA))))
                        put("to", kotlinx.serialization.json.buildJsonObject {
                            put("parent_id", kotlinx.serialization.json.JsonPrimitive(folderB))
                        })
                    }.toString()
                )
            }.jsonObject
            waitTaskIfAny(client, "batchMove", moveResp)
            val deltaMove = fetchDelta(client, cursor)
            println("=== [Step 3] Delta after moving folderA into folderB (count=${deltaMove.files.size}, sync_time=${deltaMove.syncTime}) ===")
            deltaMove.files.forEach { println("  -> ${it.summary()}") }
            cursor = deltaMove.syncTime.ifEmpty { cursor }

            // Step 4: Rename folderA -> folderA_renamed
            client.rename(folderA, "folderA_renamed")
            val deltaRename = fetchDelta(client, cursor)
            println("=== [Step 4] Delta after renaming folderA (count=${deltaRename.files.size}, sync_time=${deltaRename.syncTime}) ===")
            deltaRename.files.forEach { println("  -> ${it.summary()}") }
            cursor = deltaRename.syncTime.ifEmpty { cursor }

            // Step 5: Star & Unstar childFolder — does it bump db_modify_time and appear in delta?
            client.starFiles(listOf(childFolder))
            val deltaStar = fetchDelta(client, cursor)
            println("=== [Step 5a] Delta after starring childFolder (count=${deltaStar.files.size}, sync_time=${deltaStar.syncTime}) ===")
            deltaStar.files.forEach { println("  -> ${it.summary()}") }
            cursor = deltaStar.syncTime.ifEmpty { cursor }

            client.unstarFiles(listOf(childFolder))
            val deltaUnstar = fetchDelta(client, cursor)
            println("=== [Step 5b] Delta after unstarring childFolder (count=${deltaUnstar.files.size}, sync_time=${deltaUnstar.syncTime}) ===")
            deltaUnstar.files.forEach { println("  -> ${it.summary()}") }
            cursor = deltaUnstar.syncTime.ifEmpty { cursor }

            // Step 6: batchCopy non-empty folderA into probeRootId + inspect task & delta
            val copyResp = client.http.request(
                method = HttpMethod.Post,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files:batchCopy",
                captchaAction = "POST:/drive/v1/files:batchCopy",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderA))))
                        put("to", kotlinx.serialization.json.buildJsonObject {
                            put("parent_id", kotlinx.serialization.json.JsonPrimitive(probeRootId))
                        })
                    }.toString()
                )
            }.jsonObject
            waitTaskIfAny(client, "batchCopy", copyResp)
            val deltaCopy = fetchDelta(client, cursor)
            println("=== [Step 6b] Delta after batchCopy (count=${deltaCopy.files.size}, sync_time=${deltaCopy.syncTime}) ===")
            deltaCopy.files.forEach { println("  -> ${it.summary()}") }
            cursor = deltaCopy.syncTime.ifEmpty { cursor }

            // Step 7: Trash non-empty folderA (which contains childFolder)
            val trashResp = client.http.request(
                method = HttpMethod.Post,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files:batchTrash",
                captchaAction = "POST:/drive/v1/files:batchTrash",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderA))))
                    }.toString()
                )
            }.jsonObject
            waitTaskIfAny(client, "batchTrash", trashResp)
            val deltaTrash = fetchDelta(client, cursor)
            println("=== [Step 7] Delta after batchTrash(folderA) (count=${deltaTrash.files.size}, sync_time=${deltaTrash.syncTime}) ===")
            deltaTrash.files.forEach { println("  -> ${it.summary()}") }
            val childAfterTrash = runCatching { client.getFile(childFolder) }
            val folderAAfterTrash = runCatching { client.getFile(folderA) }
            println("  -> Direct getFile(folderA): ${folderAAfterTrash.getOrNull()?.let { "trashed=${it.trashed}" } ?: folderAAfterTrash.exceptionOrNull()?.message}")
            println("  -> Direct getFile(childFolder): ${childAfterTrash.getOrNull()?.let { "trashed=${it.trashed}" } ?: childAfterTrash.exceptionOrNull()?.message}")
            cursor = deltaTrash.syncTime.ifEmpty { cursor }

            // Step 8: Untrash folderA
            val untrashResp = client.http.request(
                method = HttpMethod.Post,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files:batchUntrash",
                captchaAction = "POST:/drive/v1/files:batchUntrash",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderA))))
                    }.toString()
                )
            }.jsonObject
            waitTaskIfAny(client, "batchUntrash", untrashResp)
            val deltaUntrash = fetchDelta(client, cursor)
            println("=== [Step 8] Delta after batchUntrash(folderA) (count=${deltaUntrash.files.size}, sync_time=${deltaUntrash.syncTime}) ===")
            deltaUntrash.files.forEach { println("  -> ${it.summary()}") }
            cursor = deltaUntrash.syncTime.ifEmpty { cursor }

            // Step 9: Permanent delete (batchDelete) folderA
            val deleteResp = client.http.request(
                method = HttpMethod.Post,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files:batchDelete",
                captchaAction = "POST:/drive/v1/files:batchDelete",
            ) {
                contentType(ContentType.Application.Json)
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(folderA))))
                    }.toString()
                )
            }.jsonObject
            waitTaskIfAny(client, "batchDelete", deleteResp)
            val deltaDelete = fetchDelta(client, cursor)
            println("=== [Step 9] Delta after batchDelete(folderA) (count=${deltaDelete.files.size}, sync_time=${deltaDelete.syncTime}) ===")
            deltaDelete.files.forEach { println("  -> ${it.summary()}") }
            val getDeletedFolderA = runCatching { client.getFile(folderA) }
            val getDeletedChild = runCatching { client.getFile(childFolder) }
            println("  -> Direct getFile(folderA) after batchDelete: ${getDeletedFolderA.getOrNull()?.let { "trashed=${it.trashed}" } ?: getDeletedFolderA.exceptionOrNull()?.message}")
            println("  -> Direct getFile(childFolder) after batchDelete: ${getDeletedChild.getOrNull()?.let { "trashed=${it.trashed}" } ?: getDeletedChild.exceptionOrNull()?.message}")
        } finally {
            if (probeRootId != null) {
                runCatching { client.batchDelete(listOf(probeRootId)) }
                println("=== [Cleanup] Deleted probeRoot=$probeRootId ===")
            }
            client.close()
        }
    }
}
