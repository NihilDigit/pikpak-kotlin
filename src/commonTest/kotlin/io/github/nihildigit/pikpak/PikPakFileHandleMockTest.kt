package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The healing ladder, driven through [PikPakFileHandle.provideUrl] rather than
 * through a CDN that has to be made to answer 404.
 *
 * What is worth asserting is which rung each kind of failure lands on. An
 * expired signature must cost one detail lookup; a dead file object must cost
 * an instant create as well. Getting that backwards is invisible until a
 * user's folder is swept and every read starts failing — or, the other way,
 * until every expiry silently creates a spare file object.
 */
class PikPakFileHandleMockTest {

    private val calls = mutableListOf<String>()

    /** Ids the mock drive considers alive. A dead id answers 404 on detail. */
    private val live = mutableSetOf("f1")
    private var nextId = 2

    /**
     * When set, an instant create returns an id the drive immediately denies —
     * the drive losing a file between the create and the detail read. Only a
     * stand-in for a race, but it is the shape that would spin forever if the
     * 404 fallback recursed.
     */
    private var createsAreBornDead = false

    private fun newClient(): PikPakClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            when {
                path.endsWith("/v1/shield/captcha/init") ->
                    json("""{"captcha_token":"C","expires_in":300,"url":""}""")

                path.endsWith("/v1/auth/signin") ->
                    json("""{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""")

                // Instant create: a gcid becomes a brand new live file object.
                request.method.value == "POST" && path.endsWith("/drive/v1/files") -> {
                    val id = "f${nextId++}"
                    if (!createsAreBornDead) live += id
                    json(
                        """{"upload_type":"UPLOAD_TYPE_RESUMABLE","file":{"kind":"drive#file",
                            "id":"$id","name":"ep.mkv","size":"1000","phase":"PHASE_TYPE_COMPLETE",
                            "hash":"${GCID}"}}""",
                    )
                }

                path.contains("/drive/v1/files/") -> {
                    val id = path.substringAfterLast('/')
                    if (id in live) {
                        json(
                            """{"kind":"drive#file","id":"$id","name":"ep.mkv","size":"1000",
                                "phase":"PHASE_TYPE_COMPLETE","hash":"${GCID}",
                                "links":{"application/octet-stream":{"url":"https://cdn/$id","expire":""}},
                                "medias":[{"media_id":"${GCID}","media_name":"Original","is_origin":true,
                                           "link":{"url":"https://cdn/$id","expire":""}}]}""",
                        )
                    } else {
                        json("""{"error_code":3,"error":"file_not_found"}""", HttpStatusCode.NotFound)
                    }
                }

                else -> respond("", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun PikPakClient.handle(initialFileId: String? = "f1") = PikPakFileHandle(
        client = this,
        gcid = GCID,
        size = 1000,
        name = "ep.mkv",
        initialFileId = initialFileId,
    )

    private fun creates() = calls.count { it == "POST /drive/v1/files" }
    private fun details() = calls.count { it.startsWith("GET /drive/v1/files/") }

    // Each reader's first link used to cost a detail lookup even when the caller had just done one
    @Test
    fun `a link handed in is used until it goes bad`() = runBlocking<Unit> {
        val client = newClient()
        val handle = PikPakFileHandle(
            client = client,
            gcid = GCID,
            size = 1000,
            name = "ep.mkv",
            initialFileId = "f1",
            initialLink = VariantLink("https://cdn/f1", expiresAt = null),
        )
        try {
            assertEquals("https://cdn/f1", handle.provideUrl(UrlRequest.Initial))
            assertEquals("https://cdn/f1", handle.provideUrl(UrlRequest.Initial))
            assertEquals(0, details(), "a usable link in hand still cost a lookup")

            // Another file saw this host fail. Every mint here lands on the same host, so what
            // is left to check is that the handle tried to get off it, and gave up in bounded time.
            client.hostHealth.answered("https://elsewhere/f9")
            client.hostHealth.markSilent("https://cdn/f1")
            handle.provideUrl(UrlRequest.Initial)
            assertEquals(1 + PikPakFileHandle.MAX_HOST_REMINTS, details(), "a link on a failing host was not replaced")
        } finally {
            handle.close(); client.close()
        }
    }

    @Test
    fun `an expired signature resolves the same file again`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        try {
            handle.provideUrl(UrlRequest.Initial)
            val creationsBefore = creates()
            val detailsBefore = details()
            handle.provideUrl(UrlRequest.Expired("https://cdn/f1"))

            assertEquals("f1", handle.currentFileId, "an expiry stays on the same file object")
            assertEquals(creationsBefore, creates(), "an expiry must not create a spare file object")
            assertEquals(detailsBefore + 1, details(), "and costs exactly one detail lookup")
        } finally {
            handle.close(); client.close()
        }
    }

    @Test
    fun `a rejected url rebuilds from the gcid before resolving`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        try {
            val url = handle.provideUrl(UrlRequest.Rejected("https://cdn/f1", status = 403))

            assertEquals("f2", handle.currentFileId, "the handle adopts the rebuilt object")
            assertEquals("https://cdn/f2", url, "the link comes from the replacement, not the rejected id")
            assertEquals(1, creates())
        } finally {
            handle.close(); client.close()
        }
    }

    @Test
    fun `an id that stopped existing is rebuilt even without a rejection`() = runBlocking<Unit> {
        // The reader cannot report a rejection here: it never got a URL to reject,
        // the detail lookup itself failed. Without this the same failing lookup
        // repeats on every read and the ladder is never reached.
        val client = newClient()
        val handle = client.handle()
        live -= "f1"
        try {
            val url = handle.provideUrl(UrlRequest.Initial)
            assertEquals("https://cdn/f2", url)
            assertEquals(1, creates())
        } finally {
            handle.close(); client.close()
        }
    }

    @Test
    fun `a handle with no file id creates one on first use`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle(initialFileId = null)
        try {
            assertNull(handle.currentFileId, "nothing is created before a read asks for it")
            handle.provideUrl(UrlRequest.Initial)
            assertEquals("f2", handle.currentFileId)
            assertEquals(1, creates())
        } finally {
            handle.close(); client.close()
        }
    }

    /**
     * A 404 on the rebuilt object would loop forever if the fallback recursed,
     * so the second failure has to surface.
     */
    @Test
    fun `a rebuild whose replacement is also gone fails instead of looping`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        live.clear()
        createsAreBornDead = true
        try {
            assertFailsWith<PikPakException> { handle.provideUrl(UrlRequest.Initial) }
            assertTrue(creates() <= 1, "one rebuild attempt, not a loop: made ${creates()}")
        } finally {
            handle.close(); client.close()
        }
    }

    /**
     * A rebuild replaces the object the handle was holding, and that object's
     * id is about to be forgotten. Overwriting the slot with the replacement
     * used to lose it, which is how two racing rebuilds stranded one object per
     * race. The object left over when the ladder gives up is owed too, and only
     * [PikPakFileHandle.closeAndReport] can collect that one.
     */
    @Test
    fun `a rebuild reports the object it replaces and close reports the last one`() = runBlocking<Unit> {
        val client = newClient()
        val minted = mutableListOf<String>()
        val handle = PikPakFileHandle(
            client = client,
            gcid = GCID,
            size = 1000,
            name = "ep.mkv",
            initialFileId = "f1",
            onObjectMinted = { minted += it },
        )
        live.clear()
        createsAreBornDead = true
        try {
            assertFailsWith<PikPakException> { handle.provideUrl(UrlRequest.Initial) }
            assertEquals(listOf("f1"), minted, "the object the rebuild replaced was not handed over")

            handle.closeAndReport()
            assertEquals(listOf("f1", "f2"), minted, "the rebuilt object was left with nobody able to name it")
        } finally {
            client.close()
        }
    }

    /**
     * A variant that is not in the detail response must fail rather than fall
     * back to another one: a different variant is different bytes, and a caller
     * reading at a committed offset would get corruption instead of an error.
     * Rebuilding cannot help either — the replacement has the same variants.
     */
    @Test
    fun `a missing variant fails without rebuilding`() = runBlocking<Unit> {
        val client = newClient()
        val handle = PikPakFileHandle(
            client, gcid = GCID, size = 1000, name = "ep.mkv",
            initialFileId = "f1", mediaId = "no-such-media",
        )
        try {
            val e = assertFailsWith<PikPakException> { handle.provideUrl(UrlRequest.Initial) }
            assertTrue(
                e.message.orEmpty().contains("no-such-media"),
                "the message names the missing variant: ${e.message}",
            )
            assertEquals(0, creates(), "a missing variant is not a dead file")
        } finally {
            handle.close(); client.close()
        }
    }

    @Test
    fun `variants reports what the drive holds`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        try {
            val v = handle.variants()
            assertEquals(1, v.size, "only the original in this fixture")
            assertTrue(v.single().isOrigin)
        } finally {
            handle.close(); client.close()
        }
    }

    /**
     * Every path that reads metadata has to heal, not just the one that
     * resolves a URL. A caller listing representations before it starts
     * playing hits the same dead id, and a 404 there would make the file id
     * behave like the identity again.
     */
    @Test
    fun `variants rebuilds a dead file id instead of failing`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        live -= "f1"
        try {
            val v = handle.variants()
            assertEquals(1, v.size)
            assertEquals("f2", handle.currentFileId, "the handle should have rebuilt")
            assertEquals(1, creates())
        } finally {
            handle.close(); client.close()
        }
    }

    /**
     * Replacing a reader must not break a read that already holds the old one.
     *
     * The handle releases its mutex before the caller starts reading, so
     * another coroutine can cross the refresh margin in between. Closing the
     * outgoing reader would turn that caller's read into "RangeReader is
     * closed"; dropping it lets the read finish on a URL that is still valid.
     */
    @Test
    fun `a retired reader still serves a caller that already holds it`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        try {
            val old = handle.readerForTest()
            handle.expireForTest()
            handle.readerForTest()

            assertTrue(!old.isClosedForTest(), "the outgoing reader was closed under a caller holding it")
        } finally {
            handle.close(); client.close()
        }
    }

    /**
     * Replacing an expired reader must not hand the file a second budget.
     * Closing a reader does not stop the reads already running on it, so two
     * gates would let one signed URL carry twice the budget until the old
     * reads drain — which the CDN answers with 503. Asserted by identity
     * because the count is only observable through the gate itself.
     */
    @Test
    fun `a replaced reader keeps the file's one connection gate`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        try {
            val first = handle.readerForTest()
            handle.expireForTest()
            val second = handle.readerForTest()

            assertTrue(first !== second, "the expired reader should have been replaced")
            assertTrue(
                first.gateForTest() === second.gateForTest(),
                "the replacement opened a second budget on the same URL",
            )
        } finally {
            handle.close(); client.close()
        }
    }

    @Test
    fun `a closed handle refuses to read`() = runBlocking<Unit> {
        val client = newClient()
        val handle = client.handle()
        handle.close()
        try {
            assertFailsWith<IllegalStateException> { handle.readBytes(0, 1) }
        } finally {
            client.close()
        }
    }

    private companion object {
        const val GCID = "A3C62CB153B61CF265298986AD97EA07A85112DF"
    }
}
