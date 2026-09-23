package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test

/**
 * Does batchTrash put a file in the trash, and does listTrash find it when the
 * file sat in a subfolder? listTrash sends no parent_id, and the files listing
 * may default that to the root. Works only on a folder and files it creates,
 * and purges them at the end. Opt in with PIKPAK_PROBE=1.
 */
class TrashListingProbeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `trash from root and from a subfolder`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        val purge = mutableListOf<String>()
        try {
            client.login()
            val folderId = client.createFolder(parentId = "", name = "piko-probe-trash")
            purge += folderId
            println("[folder] $folderId")

            val nested = download(client, folderId)
            val atRoot = download(client, "")
            purge += listOfNotNull(nested, atRoot)
            println("[files] nested=$nested root=$atRoot")
            if (nested == null || atRoot == null) return@runBlocking

            client.batchTrash(listOf(nested, atRoot))
            for (id in listOf(nested, atRoot)) {
                println("[detail] $id -> ${runCatching { client.getFile(id).let { "trashed=${it.trashed} parent=${it.parentId}" } }}")
            }

            val listed = client.listTrash().map { it.id }.toSet()
            println("[listTrash] total=${listed.size} nested=${nested in listed} root=${atRoot in listed}")

            // Same request with an explicit wildcard parent, to see whether the default narrows it
            val wildcard = client.http.request(
                method = HttpMethod.Get,
                url = buildUrl(
                    PikPakConstants.DRIVE_BASE, "/drive/v1/files",
                    mapOf(
                        "parent_id" to "*",
                        "limit" to "500",
                        "with_audit" to "false",
                        "filters" to """{"trashed":{"eq":true}}""",
                    ),
                ),
                captchaAction = "GET:/drive/v1/files",
            ).let { client.json.decodeFromJsonElement(FileListPage.serializer(), it) }
            val wildIds = wildcard.files.map { it.id }.toSet()
            println("[parent_id=*] page=${wildcard.files.size} next=${wildcard.nextPageToken.isNotEmpty()} nested=${nested in wildIds} root=${atRoot in wildIds}")
        } finally {
            if (purge.isNotEmpty()) {
                println("[purge] ${runCatching { client.batchDelete(purge) }}")
            }
            client.close()
        }
    }

    /** Read-only: what each way of listing the trash returns, item by item. */
    @Test
    fun `compare trash listings`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            for (f in client.listTrash()) println("[default] ${f.id} parent=${f.parentId} kind=${f.kind} name=${f.name} modified=${f.modifiedTime}")
            for (parent in listOf("*", "")) {
                val page = client.http.request(
                    method = HttpMethod.Get,
                    url = buildUrl(
                        PikPakConstants.DRIVE_BASE, "/drive/v1/files",
                        mapOf("parent_id" to parent, "limit" to "500", "with_audit" to "false", "filters" to """{"trashed":{"eq":true}}"""),
                    ),
                    captchaAction = "GET:/drive/v1/files",
                ).let { client.json.decodeFromJsonElement(FileListPage.serializer(), it) }
                for (f in page.files) println("[parent_id='$parent'] ${f.id} parent=${f.parentId} kind=${f.kind} name=${f.name} modified=${f.modifiedTime}")
                if (parent == "*") {
                    val raw = client.http.request(
                        method = HttpMethod.Get,
                        url = buildUrl(
                            PikPakConstants.DRIVE_BASE, "/drive/v1/files",
                            mapOf("parent_id" to "*", "limit" to "3", "with_audit" to "false", "filters" to """{"trashed":{"eq":true}}"""),
                        ),
                        captchaAction = "GET:/drive/v1/files",
                    )
                    println("[raw] $raw")
                }
            }
        } finally {
            client.close()
        }
    }

    /** Read-only: where a file removed with deleteFile ended up. */
    @Test
    fun `where did a deleteFile go`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val id = System.getenv("PROBE_FILE_ID") ?: return@runBlocking
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            println("[deleted file] ${runCatching { client.getFile(id).let { "trashed=${it.trashed} parent=${it.parentId} name=${it.name}" } }}")
        } finally {
            client.close()
        }
    }

    /** Read-only: what the server says about a folder's size, raw. */
    @Test
    fun `dump a folder raw`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val client = PikPakClient(account = username!!, password = password!!, sessionStore = InMemorySessionStore())
        try {
            client.login()
            val listing = client.http.request(
                method = HttpMethod.Get,
                url = buildUrl(
                    PikPakConstants.DRIVE_BASE, "/drive/v1/files",
                    mapOf("parent_id" to "", "limit" to "5", "filters" to """{"trashed":{"eq":false},"kind":{"eq":"drive#folder"}}"""),
                ),
                captchaAction = "GET:/drive/v1/files",
            )
            val first = (listing as kotlinx.serialization.json.JsonObject)["files"]!!.let { it as kotlinx.serialization.json.JsonArray }.first()
            println("[list entry] $first")
            val id = (first as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"')
            val detail = client.http.request(
                method = HttpMethod.Get,
                url = buildUrl(PikPakConstants.DRIVE_BASE, "/drive/v1/files/$id", mapOf("thumbnail_size" to "SIZE_SMALL")),
                captchaAction = "GET:/drive/v1/files",
            )
            println("[detail] $detail")
        } finally {
            client.close()
        }
    }

    private suspend fun download(client: PikPakClient, parentId: String): String? {
        val r = client.createUrlFile(parentId, "https://raw.githubusercontent.com/NihilDigit/piko/main/LICENSE")
        val taskId = (r as? CreateUrlResult.Queued)?.task?.id ?: return (r as? CreateUrlResult.InstantComplete)?.file?.id
        repeat(30) {
            val t = client.getTask(taskId)
            if (t.phase == TaskPhase.COMPLETE) {
                runCatching { client.deleteOfflineTasks(listOf(taskId)) }
                return t.fileId
            }
            if (t.phase == TaskPhase.ERROR) return null
            delay(2_000)
        }
        return null
    }
}
