package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The tree the walk is measured against. Four levels, one match per level, so
 * a depth cap that is off by one shows up as a different hit rather than as
 * the same list.
 *
 *     ""   → A (folder)        + rootmatch.txt
 *     A    → B (folder)        + amatch.txt
 *     B    → C (folder)        + bmatch.txt
 *     C    → cmatch.txt
 */
private val TREE: Map<String, String> = mapOf(
    "" to """{"files":[
        {"kind":"drive#folder","id":"A","name":"A","parent_id":""},
        {"kind":"drive#file","id":"r1","name":"rootmatch.txt","parent_id":""}
    ]}""",
    "A" to """{"files":[
        {"kind":"drive#folder","id":"B","name":"B","parent_id":"A"},
        {"kind":"drive#file","id":"a1","name":"amatch.txt","parent_id":"A"}
    ]}""",
    "B" to """{"files":[
        {"kind":"drive#folder","id":"C","name":"C","parent_id":"B"},
        {"kind":"drive#file","id":"b1","name":"bmatch.txt","parent_id":"B"}
    ]}""",
    "C" to """{"files":[
        {"kind":"drive#file","id":"c1","name":"cmatch.txt","parent_id":"C"}
    ]}""",
)

class RecursiveSearchMockTest {

    @Test
    fun `depth cap stops the walk before the folders past it are listed`() = runBlocking {
        val listed = mutableListOf<String>()
        val client = treeClient(listed)

        val hits = client.searchFilesRecursiveList(
            keyword = "match",
            limits = limits(maxDepth = 1),
        )

        assertEquals(listOf("rootmatch.txt", "amatch.txt"), hits.map { it.file.name })
        // The point of the cap is the requests it does not make, not just the
        // rows it drops: B is queued at depth 2 and must never be listed.
        assertEquals(listOf("", "A"), listed)
        client.close()
    }

    @Test
    fun `a hit carries the folder names between the search root and itself`() = runBlocking {
        val client = treeClient(mutableListOf())

        val hits = client.searchFilesRecursiveList("cmatch", limits = limits(maxDepth = 5))

        assertEquals(1, hits.size)
        assertEquals(listOf("A", "B", "C"), hits[0].breadcrumb)
        assertEquals("A/B/C/cmatch.txt", hits[0].path)
        assertEquals("A/B/C", hits[0].parentPath)
        client.close()
    }

    @Test
    fun `a direct child of the search root has an empty breadcrumb`() = runBlocking {
        val client = treeClient(mutableListOf())

        val hits = client.searchFilesRecursiveList("rootmatch", limits = limits(maxDepth = 0))

        assertEquals(listOf(emptyList<String>()), hits.map { it.breadcrumb })
        assertEquals("rootmatch.txt", hits[0].path)
        client.close()
    }

    @Test
    fun `the folder budget caps how many listings go out`() = runBlocking {
        val listed = mutableListOf<String>()
        val client = treeClient(listed)

        val hits = client.searchFilesRecursiveList(
            keyword = "match",
            limits = limits(maxDepth = 10, maxFolders = 2),
        )

        assertEquals(2, listed.size)
        assertEquals(listOf("rootmatch.txt", "amatch.txt"), hits.map { it.file.name })
        client.close()
    }

    @Test
    fun `an id served under two parents is emitted once`() = runBlocking {
        // Same file id in both folders. Without the dedup set the walk emits it
        // twice, which is what a caller building a result list would show.
        val duplicated = mapOf(
            "" to """{"files":[
                {"kind":"drive#folder","id":"A","name":"A","parent_id":""},
                {"kind":"drive#file","id":"dup","name":"match.txt","parent_id":""}
            ]}""",
            "A" to """{"files":[
                {"kind":"drive#file","id":"dup","name":"match.txt","parent_id":""}
            ]}""",
        )
        val client = treeClient(mutableListOf(), duplicated)

        val hits = client.searchFilesRecursiveList("match", limits = limits(maxDepth = 3))

        assertEquals(1, hits.size)
        client.close()
    }

    @Test
    fun `the first hit arrives before the deeper folders are listed`() = runBlocking {
        val listed = mutableListOf<String>()
        val client = treeClient(listed)

        val first = client.searchFilesRecursive("match", limits = limits(maxDepth = 10)).first()

        assertEquals("rootmatch.txt", first.file.name)
        // A suspend function returning the whole list would have walked all
        // four levels to produce this one row.
        assertEquals(listOf(""), listed)
        client.close()
    }

    @Test
    fun `an empty keyword is rejected before anything is collected`() {
        val client = treeClient(mutableListOf())
        var threw = false
        try {
            client.searchFilesRecursive("")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "empty keyword must be rejected at the call site")
        client.close()
    }

    /** Generous depth and time so each test constrains exactly the one thing it names. */
    private fun limits(
        maxDepth: Int = 10,
        maxFolders: Int = 100,
    ) = RecursiveSearchLimits(
        maxDepth = maxDepth,
        maxFolders = maxFolders,
        timeout = 5.minutes,
        concurrency = 2,
    )

    @Test
    fun `the trash listing asks for every parent`() = runBlocking {
        val listed = mutableListOf<String>()
        val client = treeClient(listed, tree = mapOf("*" to """{"files":[{"kind":"drive#file","id":"t1","name":"t","parent_id":"A","trashed":true}]}"""))
        val trash = client.listTrash()
        // Without parent_id=* the server answers only for the root, and a file
        // trashed from a subfolder never shows up in the trash screen
        assertEquals(listOf("*"), listed)
        assertEquals(listOf("t1"), trash.map { it.id })
        client.close()
    }

    private fun treeClient(
        listed: MutableList<String>,
        tree: Map<String, String> = TREE,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") -> respondJson(
                    """{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""",
                )
                path.endsWith("/drive/v1/files") -> {
                    val parent = req.url.parameters["parent_id"].orEmpty()
                    listed += parent
                    respondJson(tree[parent] ?: """{"files":[]}""")
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter.unlimited(),
            httpClient = HttpClient(engine),
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
