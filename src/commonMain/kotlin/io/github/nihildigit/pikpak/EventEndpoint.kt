package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val EVENTS_PATH = "/drive/v1/events"

/** Values of [DriveEvent.type], as the web client and the server spell them. */
object EventType {
    /** One per played file; see [reportPlay]. Shown as "Play". */
    const val PLAY = "TYPE_PLAY"

    /** A file uploaded to the drive. Shown as "Upload". */
    const val UPLOAD = "TYPE_UPLOAD"

    /** A file added by an offline task or an instant copy. Shown as "Add". */
    const val RESTORE = "TYPE_RESTORE"
}

/**
 * One entry of `/drive/v1/events`.
 *
 * For [EventType.PLAY] the resume position lives in this event's own [params]
 * (`play_seconds`, `play_duration`, both decimal strings), not on the file:
 * neither the file detail nor the folder listing carries it, and their
 * `reference_events` array was empty for a played file (2026-09-24). The web
 * client reads it as `file.event_params`, a name it gives the event's params
 * on its side.
 */
@Serializable
data class DriveEvent(
    val id: String = "",
    /** One of [EventType]. */
    val type: String = "",
    /** Display label: "Play", "Upload", "Add". */
    @SerialName("type_name") val typeName: String = "",
    @SerialName("file_id") val fileId: String = "",
    /** The file's name when the event was last written; [file] has the current one. */
    @SerialName("file_name") val fileName: String = "",
    @SerialName("mime_type") val mimeType: String = "",
    /** Parent folder of the file. */
    @SerialName("folder_id") val folderId: String = "",
    /** First report, RFC 3339. A play event keeps it across later reports. */
    @SerialName("created_time") val createdTime: String = "",
    /** Last report, RFC 3339. The listing is sorted by this, newest first. */
    @SerialName("updated_time") val updatedTime: String = "",
    /**
     * Event payload. Plays carry `play_seconds` and `play_duration`; plays from
     * the official apps also carry a `media_id`. Uploads and adds carry nothing.
     */
    val params: Map<String, String> = emptyMap(),
    /**
     * Percent watched as the official apps report it. Plays sent through
     * [reportPlay] stayed at 0 (2026-09-24), so derive progress from
     * [playSeconds] and [playDuration] instead.
     */
    val progress: Int = 0,
    /**
     * The file itself, decoded in the listing shape: name, size, kind, thumbnail.
     * Links and media variants are not kept; ask [getFile] for those. Null when
     * the server has nothing to embed.
     */
    @SerialName("reference_resource") val file: FileStat? = null,
) {
    /** Resume position of a play, in whole seconds. */
    val playSeconds: Long? get() = params["play_seconds"]?.toLongOrNull()

    /** Media duration the player reported with the play, in whole seconds. */
    val playDuration: Long? get() = params["play_duration"]?.toLongOrNull()
}

/**
 * One page of [listEvents]. [nextPageToken] is the `updated_time` of the next
 * page's first entry, percent-encoded by the server; pass it back unchanged.
 * Empty on the last page.
 */
@Serializable
data class EventPage(
    @SerialName("next_page_token") val nextPageToken: String = "",
    val events: List<DriveEvent> = emptyList(),
)

/**
 * Lists the account's events (`GET /drive/v1/events`), newest `updated_time`
 * first.
 *
 * With no [types] the server leaves plays out: 3000 unfiltered entries reaching
 * back seven months held only uploads and adds, while the play filter over the
 * same span returned plays (2026-09-24). This unfiltered view is what the web
 * client calls recent. [types] becomes `{"type":{"in":"A,B"}}`; a comma list
 * of two types returns both, interleaved by time.
 *
 * [pageSize] above 100 is served as 100 (asked 500, got 100 and a token).
 */
suspend fun PikPakClient.listEvents(
    types: List<String> = emptyList(),
    pageSize: Int = 100,
    pageToken: String = "",
): EventPage {
    val query = mutableMapOf("thumbnail_size" to THUMBNAIL_SIZE, "limit" to pageSize.toString())
    if (types.isNotEmpty()) {
        query["filters"] = buildJsonObject { putJsonObject("type") { put("in", types.joinToString(",")) } }.toString()
    }
    if (pageToken.isNotEmpty()) query["page_token"] = pageToken
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(PikPakConstants.DRIVE_BASE, EVENTS_PATH, query),
        captchaAction = "GET:$EVENTS_PATH",
    )
    return json.decodeFromJsonElement(EventPage.serializer(), response)
}

/**
 * The play history: [listEvents] filtered to [EventType.PLAY]. There is one
 * event per file, most recently played first, each with its resume position in
 * [DriveEvent.playSeconds].
 */
suspend fun PikPakClient.listPlayHistory(pageSize: Int = 100, pageToken: String = ""): EventPage =
    listEvents(listOf(EventType.PLAY), pageSize, pageToken)

internal fun playEventBody(fileId: String, positionSeconds: Long, durationSeconds: Long): JsonElement = buildJsonObject {
    putJsonObject("event") {
        put("type", EventType.PLAY)
        put("file_id", fileId)
        putJsonObject("params") {
            put("play_duration", durationSeconds.toString())
            put("play_seconds", positionSeconds.toString())
        }
    }
}

/**
 * Records that [fileId] was played up to [positionSeconds] of [durationSeconds]
 * (`POST /drive/v1/events`). The server answers `{}`.
 *
 * Measured 2026-09-24:
 * - The first report creates the file's play event; later ones overwrite its
 *   position in place and move it to the top. A smaller position replaces a
 *   larger one, so the caller decides what counts as progress.
 * - A report arriving about 1.5 s after the previous one for the same file is
 *   accepted and discarded; one 6 s later is applied. The web client reports at
 *   most every 5 s and skips a zero position or duration, which is the pattern
 *   to follow.
 */
suspend fun PikPakClient.reportPlay(fileId: String, positionSeconds: Long, durationSeconds: Long) {
    http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}$EVENTS_PATH",
        captchaAction = "POST:$EVENTS_PATH",
    ) { jsonBody(json, playEventBody(fileId, positionSeconds, durationSeconds)) }
}

/**
 * Deletes events by [DriveEvent.id] (`POST /drive/v1/events:delete`). For a play
 * this removes the file from the history and drops its resume position; the
 * file is untouched. No-op when [ids] is empty.
 */
suspend fun PikPakClient.deleteEvents(ids: List<String>) {
    if (ids.isEmpty()) return
    http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}$EVENTS_PATH:delete",
        captchaAction = "POST:$EVENTS_PATH:delete",
    ) { jsonBody(json, buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }) }
}

/**
 * Deletes every event of the given [types] (`POST /drive/v1/events:clear`), as
 * the web client's "clear history" does with `[TYPE_PLAY]`. Not exercised
 * against the live API, since it would wipe the account's real history. No-op
 * when [types] is empty.
 */
suspend fun PikPakClient.clearEvents(types: List<String>) {
    if (types.isEmpty()) return
    http.request(
        method = HttpMethod.Post,
        url = "${PikPakConstants.DRIVE_BASE}$EVENTS_PATH:clear",
        captchaAction = "POST:$EVENTS_PATH:clear",
    ) { jsonBody(json, buildJsonObject { putJsonArray("types") { types.forEach { add(it) } } }) }
}
