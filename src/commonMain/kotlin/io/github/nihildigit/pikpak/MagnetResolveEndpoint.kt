package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One leaf file inside a torrent, as PikPak's content index knows it. */
data class ResolvedFile(
    /** Path inside the torrent, `/` separated, e.g. `specials/S00E01.mkv`. A single-file torrent yields a bare name. */
    val path: String,
    val size: Long,
    /**
     * PikPak's gcid content hash, 40 upper-case hex digits, or null when PikPak
     * has never seen this file. A null gcid means the instant path is closed
     * for it and the caller has to fall back to an offline-download task.
     */
    val gcid: String?,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** A torrent as resolved by [resolveMagnet]. */
data class MagnetResource(
    /** Display name of the torrent. */
    val name: String,
    /**
     * Every leaf file in the torrent, in the order PikPak listed them,
     * including the ones with a null [ResolvedFile.gcid]. Callers match an
     * episode by name across the whole tree; hiding the unindexed entries
     * would turn "this file is not in the index" into "this file is not in
     * the torrent".
     */
    val files: List<ResolvedFile>,
)

/**
 * Depth cap on the inlined directory tree. PikPak sends the whole tree in one
 * response with no cursor between levels, so nothing but this cap bounds the
 * walk if a response ever contains a cycle. Entries below the cap are dropped,
 * not reported.
 */
internal const val MAX_MAGNET_TREE_DEPTH = 8

private const val RESOURCE_LIST_PATH = "/drive/v1/resource/list"

/**
 * Resolves a magnet link against PikPak's content index without creating an
 * offline-download task: the response carries each file's name, size and gcid,
 * which is everything [instantCreate] needs to materialise a file with zero
 * bytes transferred. Measured at 150-314 ms, against the 5-10x slower task
 * round trip.
 *
 * Returns null when PikPak has no record of the torrent. That is a normal
 * answer to a query, not a failure, so it is not an exception; transport,
 * auth and captcha failures still throw [PikPakException].
 *
 * The miss is detected by every leaf having an empty gcid. PikPak answers a
 * miss with a single placeholder entry named after the info hash, sized zero,
 * with an empty `meta.hash`. Some documentation claims a `meta.error` of
 * `406:E_BT_FILE_NOT_EXIST` marks this case; it was absent from every observed
 * miss, so the gcids are the signal and the error field is not consulted.
 */
suspend fun PikPakClient.resolveMagnet(magnet: String): MagnetResource? {
    require(magnet.isNotBlank()) { "magnet must not be blank" }
    val body = buildJsonObject {
        put("urls", magnet)
        put("page_size", 500)
        put("thumbnail_type", "FROM_HASH")
    }
    val response = http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}$RESOURCE_LIST_PATH",
        captchaAction = "POST:$RESOURCE_LIST_PATH",
    ) { jsonBody(json, body) }
    return parseMagnetResource(response as JsonObject)
}

/**
 * Flattens a `/drive/v1/resource/list` response. Split out from the request so
 * the tree walk can be tested against recorded responses without an account.
 */
internal fun parseMagnetResource(
    root: JsonObject,
    maxDepth: Int = MAX_MAGNET_TREE_DEPTH,
): MagnetResource? {
    val top = root.obj("list")?.arr("resources").orEmpty()
    if (top.isEmpty()) return null

    // A torrent with a folder at the root has exactly one top-level entry and
    // that entry's name is the torrent's name, not a path segment: dropping it
    // makes `specials/S00E01.mkv` come out the same shape as the bare filename
    // a single-file torrent produces. With several top-level entries there is
    // no such root, so every name stays in the path.
    val singleRootDir = top.size == 1 && top[0].asObject()?.isDir() == true

    val files = mutableListOf<ResolvedFile>()
    for (entry in top) {
        val obj = entry.asObject() ?: continue
        if (singleRootDir) {
            collect(obj.obj("dir")?.arr("resources").orEmpty(), prefix = "", depth = 1, maxDepth, files)
        } else {
            collect(listOf(obj), prefix = "", depth = 0, maxDepth, files)
        }
    }
    if (files.isEmpty()) return null
    if (files.all { it.gcid == null }) return null

    val name = top.firstOrNull()?.asObject()?.str("name").orEmpty()
    return MagnetResource(name = name, files = files)
}

private fun collect(
    entries: List<JsonElement>,
    prefix: String,
    depth: Int,
    maxDepth: Int,
    into: MutableList<ResolvedFile>,
) {
    if (depth > maxDepth) return
    for (element in entries) {
        val obj = element.asObject() ?: continue
        val name = obj.str("name").orEmpty()
        if (name.isEmpty()) continue
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        if (obj.isDir()) {
            collect(obj.obj("dir")?.arr("resources").orEmpty(), path, depth + 1, maxDepth, into)
        } else {
            into += ResolvedFile(
                path = path,
                // file_size arrives as a JSON string, not a number.
                size = obj.str("file_size")?.toLongOrNull() ?: 0L,
                gcid = obj.obj("meta")?.str("hash")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Creates a file from a content hash alone: PikPak recognizes the gcid, links
 * the existing blob into [parentId] and no bytes are transferred. Returns the
 * new file id.
 *
 * This is the init request of [upload] with the hash and size supplied by the
 * caller instead of read off a local file, which is why it can only ever
 * succeed on content PikPak already stores. A response whose phase is not
 * [TaskPhase.COMPLETE] means the server wants the bytes — and we have none —
 * so it throws rather than returning a half-created object.
 *
 * Known flake: creating the same gcid twice in one folder has been observed to
 * return a file node complete enough to carry an id but not yet resolvable —
 * an immediate `getFile` came back without a download link. Retrying is the
 * caller's call, and deliberately not done here.
 */
suspend fun PikPakClient.instantCreate(
    file: ResolvedFile,
    parentId: String,
    name: String = file.name,
): String {
    val gcid = requireNotNull(file.gcid) {
        "instantCreate needs a gcid; ${file.path} is not in PikPak's index and has to go through an offline task"
    }
    val body = buildJsonObject {
        put("kind", FileKind.FILE)
        put("name", name)
        put("size", file.size.toString())
        put("hash", gcid)
        put("upload_type", "UPLOAD_TYPE_RESUMABLE")
        if (parentId.isNotEmpty()) put("parent_id", parentId)
        putJsonObject("body") {
            put("duration", "")
            put("width", "")
            put("height", "")
        }
        putJsonObject("objProvider") { put("provider", "UPLOAD_TYPE_UNKNOWN") }
    }

    val response = http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}/drive/v1/files",
        captchaAction = "POST:/drive/v1/files",
    ) { jsonBody(json, body) }

    val fileNode = (response as JsonObject).obj("file")
        ?: throw PikPakException(-1, "instantCreate: missing file in response for $name")
    val phase = fileNode.str("phase").orEmpty()
    if (phase != TaskPhase.COMPLETE) {
        throw PikPakException(
            errorCode = -1,
            errorMessage = "instantCreate: PikPak does not hold hash $gcid (phase=$phase); " +
                "$name needs a real upload or an offline task",
        )
    }
    return fileNode.str("id")?.takeIf { it.isNotEmpty() }
        ?: throw PikPakException(-1, "instantCreate: completed response carries no file id for $name")
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.isDir(): Boolean = (this["is_dir"] as? JsonPrimitive)?.booleanOrNull == true
