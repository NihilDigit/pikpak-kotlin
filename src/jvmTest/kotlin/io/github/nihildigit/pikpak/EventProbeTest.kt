package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.github.nihildigit.pikpak.internal.jsonBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Raw shape of `/drive/v1/events`, printed rather than asserted: the listing is
 * the account's real play history, so what it contains is not ours to pin.
 *
 * The first test is read-only. The second reports plays of one video that is
 * not in the history yet, then deletes exactly those events; it never calls
 * `events:clear`. Opt in with PIKPAK_PROBE=1.
 */
class EventProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `print the raw event listings`() = runBlocking<Unit> {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())

        val plays = rawList(client, mapOf("limit" to "2", "filters" to """{"type":{"in":"TYPE_PLAY"}}"""))
        println("[plays] ${redact(plays)}")

        // Does the returned token page forward, given that it arrives percent-encoded and buildUrl encodes it again?
        val oneShotPage = client.listPlayHistory(pageSize = 500)
        val oneShot = oneShotPage.events
        println("[limit 500] n=${oneShot.size} next=${oneShotPage.nextPageToken}")
        val paged = mutableListOf<DriveEvent>()
        var token = ""
        var pages = 0
        do {
            val page = client.listPlayHistory(pageSize = 3, pageToken = token)
            println("[page $pages] n=${page.events.size} next=${page.nextPageToken} updated=${page.events.map { it.updatedTime }}")
            paged += page.events
            token = page.nextPageToken
            pages++
        } while (token.isNotEmpty() && pages < 20)
        println("[paging] oneShot=${oneShot.size} paged=${paged.size} distinct=${paged.map { it.id }.distinct().size} sameOrder=${oneShot.map { it.id } == paged.map { it.id }}")
        println("[plays] created|updated|progress|pos/dur: " + oneShot.take(8).map { "${it.createdTime}|${it.updatedTime}|${it.progress}|${it.playSeconds}/${it.playDuration}" })
        println("[plays] withFile=" + oneShot.groupingBy { it.file != null }.eachCount() + " maxPerFile=" + oneShot.groupingBy { it.fileId }.eachCount().values.maxOrNull())

        val typeCounts = mutableMapOf<String, Int>()
        token = ""
        pages = 0
        do {
            val page = client.listEvents(pageSize = 100, pageToken = token)
            if (pages % 10 == 0 || page.nextPageToken.isEmpty()) println("[all page $pages] ${page.events.firstOrNull()?.updatedTime} .. ${page.events.lastOrNull()?.updatedTime}")
            page.events.forEach { typeCounts.merge("${it.type}/${it.typeName}", 1, Int::plus) }
            token = page.nextPageToken
            pages++
        } while (token.isNotEmpty() && pages < 30)
        println("[all] pages=$pages more=${token.isNotEmpty()} types=$typeCounts")

        val multi = rawList(client, mapOf("limit" to "100", "filters" to """{"type":{"in":"TYPE_PLAY,TYPE_UPLOAD"}}"""))
        val multiTypes = (multi.jsonObject["events"] as JsonArray).groupingBy { (it as JsonObject)["type"].toString() }.eachCount()
        println("[multi in] $multiTypes")

        // The web client reads event_params off a drive file; see whether the listing or the detail carries it.
        val played = oneShot.first()
        val detail = client.http.request(
            method = HttpMethod.Get,
            url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files/${played.fileId}", mapOf("thumbnail_size" to "SIZE_SMALL")),
            captchaAction = "GET:/drive/v1/files",
        )
        println("[detail] keys=${detail.jsonObject.keys} reference_events=${detail.jsonObject["reference_events"]}")
        val listing = client.http.request(
            method = HttpMethod.Get,
            url = buildUrl(
                PikPakConstants.DRIVE_BASE,
                "/drive/v1/files",
                mapOf("parent_id" to played.folderId, "limit" to "500", "thumbnail_size" to "SIZE_SMALL", "filters" to """{"trashed":{"eq":false}}"""),
            ),
            captchaAction = "GET:/drive/v1/files",
        )
        val row = (listing.jsonObject["files"] as JsonArray).map { it.jsonObject }.firstOrNull { it["id"] == JsonPrimitive(played.fileId) }
        println("[listing] keys=${row?.keys} reference_events=${row?.get("reference_events")}")
    }

    @Test
    fun `report plays and delete them`() = runBlocking<Unit> {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())

        val before = allPlays(client)
        val playedIds = before.map { it.fileId }.toSet()
        println("[probe] history holds ${before.size} plays")
        val video = findVideo(client, "", depth = 3, exclude = playedIds)
        Assumptions.assumeTrue(video != null, "no unplayed video found")
        println("[probe] video id=${video!!.id} name=${video.name.take(12)}… mime=${video.mimeType} duration=${video.params["duration"]}")

        val ours = mutableSetOf<String>()
        try {
            val reported = client.http.request(
                method = HttpMethod.Post,
                url = "${PikPakConstants.DRIVE_BASE}/drive/v1/events",
                captchaAction = "POST:/drive/v1/events",
            ) { jsonBody(client.json, playEventBody(video.id, 12, 100)) }
            println("[report] ${redact(reported)}")

            val first = client.listPlayHistory(pageSize = 100).events
            val mine = first.filter { it.fileId == video.id }
            ours += mine.map { it.id }
            println("[after report] position=${first.indexOfFirst { it.fileId == video.id }} of ${first.size}")
            println("[after report] ${mine.map { "${it.id} ${it.createdTime}|${it.updatedTime} ${it.params} progress=${it.progress}" }}")

            // Spaced reports: how soon a second one lands, and whether a smaller position replaces a larger one.
            for ((wait, position) in listOf(1_500L to 20L, 6_000L to 30L, 11_000L to 25L, 11_000L to 60L)) {
                delay(wait)
                client.reportPlay(video.id, positionSeconds = position, durationSeconds = 100)
                val again = client.listPlayHistory(pageSize = 100).events.filter { it.fileId == video.id }
                ours += again.map { it.id }
                println("[after ${wait}ms report $position] ${again.map { "${it.id} ${it.createdTime}|${it.updatedTime} ${it.params} progress=${it.progress}" }}")
            }

            val detail = client.http.request(
                method = HttpMethod.Get,
                url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files/${video.id}", mapOf("thumbnail_size" to "SIZE_SMALL")),
                captchaAction = "GET:/drive/v1/files",
            )
            println("[detail after] reference_events=${detail.jsonObject["reference_events"]}")

            val unfiltered = client.listEvents(pageSize = 100).events.filter { it.fileId == video.id }
            println("[unfiltered] our play in the unfiltered listing: ${unfiltered.map { it.type }}")
        } finally {
            val all = allPlays(client).filter { it.fileId == video.id }
            ours += all.map { it.id }
            client.deleteEvents(ours.toList())
            val left = allPlays(client)
            println("[cleanup] deleted ${ours.size}; left for our file ${left.count { it.fileId == video.id }}; history ${before.size} -> ${left.size}")
        }
    }

    private suspend fun allPlays(client: PikPakClient): List<DriveEvent> {
        val out = mutableListOf<DriveEvent>()
        var token = ""
        do {
            val page = client.listPlayHistory(pageSize = 100, pageToken = token)
            out += page.events
            token = page.nextPageToken
        } while (token.isNotEmpty())
        return out
    }

    private suspend fun rawList(client: PikPakClient, query: Map<String, String>): JsonElement =
        client.http.request(
            method = HttpMethod.Get,
            url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/events", query + ("thumbnail_size" to "SIZE_SMALL")),
            captchaAction = "GET:/drive/v1/events",
        )

    private suspend fun findVideo(client: PikPakClient, parentId: String, depth: Int, exclude: Set<String>): FileStat? {
        val entries = client.listFiles(parentId)
        entries.firstOrNull { !it.isFolder && it.mimeType.startsWith("video/") && it.id !in exclude }?.let { return it }
        if (depth == 0) return null
        for (folder in entries.filter { it.isFolder }) {
            findVideo(client, folder.id, depth - 1, exclude)?.let { return it }
        }
        return null
    }

    /** Names and links shortened, so the console log carries structure, not the library. */
    private fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (value is JsonPrimitive && value.isString && key in REDACTED) {
                    JsonPrimitive(value.content.take(12) + "…")
                } else {
                    redact(value)
                }
            },
        )
        is JsonArray -> JsonArray(element.map { redact(it) })
        else -> element
    }

    private companion object {
        val REDACTED = setOf("name", "file_name", "title", "thumbnail_link", "icon_link", "url", "web_content_link")
    }
}
