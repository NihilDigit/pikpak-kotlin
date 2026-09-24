package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * How stars, the starred listing and batchCopy behave. Every write lands on a
 * `piko-probe-<timestamp>` folder this test creates and permanently deletes;
 * existing files are only read. Opt in with PIKPAK_PROBE=1.
 *
 * emptyTrash and clearOfflineTasks are deliberately absent: neither can be
 * aimed at test data, and both would take the account's real items with them.
 */
class StarCopyProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `star copy and list starred on a probe folder`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())

        println("[probe] starred before: drive-wide=${client.listStarred().size} root=${client.listStarred("").size}")
        val probeId = client.createFolder("", "piko-probe-${System.currentTimeMillis()}")
        println("[probe] folder=$probeId")
        try {
            val child = client.createFolder(probeId, "child")
            val sub = client.createFolder(probeId, "sub")
            val deep = client.createFolder(sub, "deep")
            val extra = (1..4).map { client.createFolder(probeId, "s$it") }

            client.starFiles(listOf(child, deep) + extra)
            printStarFields(client, probeId)
            println("[detail] starred=${client.getFile(child).starred}")

            val wide = client.listStarred(pageSize = 2).map { it.id }
            println("[star *] n=${wide.size} child=${child in wide} deep=${deep in wide}")
            val inProbe = client.listStarred(probeId, pageSize = 2).map { it.id }
            println("[star probe] n=${inProbe.size} child=${child in inProbe} deep=${deep in inProbe}")
            println("[star root] child=${child in client.listStarred("").map { it.id }}")

            client.unstarFiles(listOf(child, deep) + extra)
            println("[after unstar] probe=${client.listStarred(probeId).size}")

            val tasks = client.batchCopy(listOf(child), sub)
            val task = client.getTask(tasks.single())
            println("[copy] type=${task.type} phase=${task.phase} message=${task.message}")
            println("[copy] sub=${client.listFiles(sub).map { "${it.name}:${it.id == child}" }}")
            val self = runCatching { client.batchCopy(listOf(child), probeId) }
            println("[copy into own parent] ${self.exceptionOrNull()?.message}")
        } finally {
            client.batchDelete(listOf(probeId))
            println("[cleanup] probe folder left in root: ${client.listFiles("").count { it.id == probeId }}")
            client.close()
        }
    }

    /** The typed model drops `tags`, which is where the star actually shows. */
    private suspend fun printStarFields(client: PikPakClient, parentId: String) {
        val q = mapOf("parent_id" to parentId, "limit" to "20", "filters" to """{"trashed":{"eq":false}}""")
        val page = client.http.request(
            HttpMethod.Get,
            buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files", q),
            captchaAction = "GET:/drive/v1/files",
        )
        page.jsonObject["files"]!!.jsonArray.map { it.jsonObject }.forEach { o ->
            println("[listing] ${o["name"]!!.jsonPrimitive.content} starred=${o["starred"]} tags=${o["tags"]}")
        }
    }
}
