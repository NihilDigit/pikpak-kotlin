package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray

class AccountStateMockTest {

    @Test
    fun `concurrent requests deduplicate in flight and respect maxAge`() {
        runBlocking {
            var aboutCalls = 0
            var transferCalls = 0
            var profileCalls = 0

            val client = mockClient { path, _ ->
                when {
                    path.endsWith("/drive/v1/about") -> {
                        aboutCalls++
                        delay(40)
                        respondJson(aboutBody(usage = 1_000_000L, trash = 200_000L))
                    }
                    path.endsWith("/vip/v1/quantity/list") -> {
                        transferCalls++
                        delay(40)
                        respondJson(transferBody(uploadUsed = 100L, downloadUsed = 200L, offlineUsed = 300L, vipStatus = "ok"))
                    }
                    path.endsWith("/v1/user/me") -> {
                        profileCalls++
                        delay(40)
                        respondJson("""{"sub":"UID","name":"Tester","email":"t@example.com"}""")
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }

            // 6 concurrent callers per endpoint share 1 in-flight request each
            val quotas = (1..6).map { async { client.getQuota() } }.awaitAll()
            val transfers = (1..6).map { async { client.getTransferQuota() } }.awaitAll()
            val profiles = (1..6).map { async { client.getUserProfile() } }.awaitAll()

            assertEquals(1, aboutCalls)
            assertEquals(1, transferCalls)
            assertEquals(1, profileCalls)
            assertEquals(1_000_000L, quotas.first().quota.usageBytes)
            assertEquals(100L, transfers.first().account.upload.usedBytes)
            assertEquals("Tester", profiles.first().name)
            assertEquals(false, client.isFreeAccount)
            assertNotNull(client.leaseBudget)

            // Cached reads within maxAge do not hit the network
            assertEquals(1_000_000L, client.getQuota(maxAge = 5.minutes).quota.usageBytes)
            assertEquals(100L, client.getTransferQuota(maxAge = 5.minutes).account.upload.usedBytes)
            assertEquals("Tester", client.getUserProfile(maxAge = 5.minutes).name)
            assertEquals(1, aboutCalls)
            assertEquals(1, transferCalls)
            assertEquals(1, profileCalls)

            // Explicit fresh fetch (maxAge = 0) hits the network again
            client.refreshAccountState()
            assertEquals(2, aboutCalls)
            assertEquals(2, transferCalls)
            assertEquals(2, profileCalls)

            client.close()
        }
    }

    @Test
    fun `session store snapshot restores state on startup and installs free account lease budget`() {
        runBlocking {
            val store = InMemorySessionStore()
            val first = mockClient(store = store) { path, _ ->
                when {
                    path.endsWith("/drive/v1/about") -> respondJson(aboutBody(usage = 500_000L, trash = 50_000L, limit = 6_442_450_944L))
                    path.endsWith("/vip/v1/quantity/list") ->
                        respondJson(transferBody(uploadUsed = 1000L, downloadUsed = 2000L, offlineUsed = 0L, vipStatus = "novip", dailyLimit = 42_949_672_960L, dailyUsed = 5000L))
                    path.endsWith("/v1/user/me") -> respondJson("""{"sub":"UID","name":"FreeUser","email":"free@example.com"}""")
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }

            first.refreshAccountState()
            assertEquals(true, first.isFreeAccount)
            assertNotNull(first.leaseBudget)
            delay(350) // allow debounced snapshot persistence to complete
            first.close()

            val persisted = store.loadAccountSnapshot("mock@x")
            assertNotNull(persisted)
            assertEquals(500_000L, persisted.quota?.quota?.usageBytes)
            assertEquals("FreeUser", persisted.userProfile?.name)

            var networkHit = false
            val second = mockClient(store = store) { _, _ ->
                networkHit = true
                respond("", HttpStatusCode.InternalServerError)
            }

            // Wait briefly for async init snapshot load
            delay(50)
            assertFalse(networkHit)
            assertEquals(500_000L, second.currentQuota?.quota?.usageBytes)
            assertEquals(5000L, second.currentTransferQuota?.account?.downloadDaily?.usedBytes)
            assertEquals("FreeUser", second.currentUserProfile?.name)
            assertEquals(true, second.isFreeAccount)
            assertNotNull(second.leaseBudget)
            second.close()
        }
    }

    @Test
    fun `idempotent file projections update storage and transfer ledgers without double counting`() {
        runBlocking {
            var serverAboutUsage = 10_000L
            var serverAboutTrash = 1_000L

            val client = mockClient { path, body ->
                when {
                    path.endsWith("/drive/v1/about") ->
                        respondJson(aboutBody(usage = serverAboutUsage, trash = serverAboutTrash))
                    path.endsWith("/vip/v1/quantity/list") ->
                        respondJson(transferBody(uploadUsed = 1_000L, downloadUsed = 2_000L, offlineUsed = 500L, vipStatus = "novip", dailyLimit = 100_000L, dailyUsed = 3_000L))
                    path.endsWith("/drive/v1/files") && body.isEmpty() ->
                        respondJson(
                            """{"files":[{"id":"f1","kind":"drive#file","name":"a.mp4","size":"4000","trashed":false},
                                        {"id":"f2","kind":"drive#file","name":"b.mp4","size":"2000","trashed":false}],"next_page_token":""}"""
                        )
                    path.endsWith("/drive/v1/files:batchTrash") ||
                        path.endsWith("/drive/v1/files:batchUntrash") ||
                        path.endsWith("/drive/v1/files:batchDelete") ->
                        respondJson("""{"task_id":"","ids":["f1","f2"]}""")
                    path.endsWith("/drive/v1/files") && body.contains("UPLOAD_TYPE_RESUMABLE") ->
                        respondJson("""{"file":{"id":"inst1","kind":"drive#file","name":"new.bin","size":"1000","phase":"PHASE_TYPE_COMPLETE"}}""")
                    path.endsWith("/drive/v1/files") && body.contains("UPLOAD_TYPE_URL") ->
                        respondJson("""{"upload_type":"UPLOAD_TYPE_URL","url":{"kind":"upload#url"},"task":{"id":"task_url_1","file_size":"800","phase":"PHASE_TYPE_COMPLETE","file_id":"url_f1"}}""")
                    path.endsWith("/media.bin") ->
                        respond(
                            content = ByteReadChannel(ByteArray(1024)),
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentLength to listOf("1024"),
                                HttpHeaders.ContentRange to listOf("bytes 0-1023/1024"),
                            ),
                        )
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }

            client.getQuota()
            client.getTransferQuota()
            val listed = client.listFiles("")
            assertEquals(2, listed.size)

            // 1. Optimistic pre-announcement of trash moves 4000 bytes from usage to trash immediately
            client.noteFilesTrashed(listOf("f1"))
            assertEquals(6_000L, client.currentQuota?.quota?.usageBytes)
            assertEquals(5_000L, client.currentQuota?.quota?.usageInTrashBytes)

            // When batchTrash actually completes, it does NOT double-count f1
            // Also simulate PikPak's write-lag: /drive/v1/about still returns old 10_000L during background reconcile
            client.batchTrash(listOf("f1"))
            assertEquals(6_000L, client.currentQuota?.quota?.usageBytes)
            assertEquals(5_000L, client.currentQuota?.quota?.usageInTrashBytes)

            // Wait past the 300ms reconcile debounce while server still lags at 10_000L:
            // write-lag protection keeps the local projection intact!
            delay(400)
            assertEquals(6_000L, client.currentQuota?.quota?.usageBytes)
            assertEquals(5_000L, client.currentQuota?.quota?.usageInTrashBytes)

            // 2. Rollback via revertFiles restores f1 to active usage
            client.revertFiles(listed.filter { it.id == "f1" })
            assertEquals(10_000L, client.currentQuota?.quota?.usageBytes)
            assertEquals(1_000L, client.currentQuota?.quota?.usageInTrashBytes)

            // 3. Permanent delete of f2 (2000 bytes) reduces active usage without increasing trash
            client.noteFilesDeleted(listOf("f2"))
            assertEquals(8_000L, client.currentQuota?.quota?.usageBytes)
            assertEquals(1_000L, client.currentQuota?.quota?.usageInTrashBytes)
            client.batchDelete(listOf("f2"))
            assertEquals(8_000L, client.currentQuota?.quota?.usageBytes)

            // 4. Instant create of 1000 bytes adds +1000 storage and +150 (15%) upload transfer quota
            val created = client.instantCreate(
                file = ResolvedFile(path = "new.bin", size = 1_000L, gcid = "GCID"),
                parentId = "",
            )
            assertEquals("inst1", created)
            assertEquals(9_000L, client.currentQuota?.quota?.usageBytes)
            assertEquals(1_150L, client.currentTransferQuota?.account?.upload?.usedBytes)

            // 5. Offline URL task creation increments daily cloudDownload usage count and completed task adds storage + offline bytes
            client.createUrlFile(parentId = "", url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
            assertEquals("1", client.currentQuota?.quotas?.cloudDownload?.usage)
            assertEquals(2, client.currentQuota?.quotas?.cloudDownload?.remaining)
            assertEquals(9_800L, client.currentQuota?.quota?.usageBytes)
            assertEquals(1_300L, client.currentTransferQuota?.account?.offline?.usedBytes)

            // 6. Downstream range read updates download & daily download transfer quota immediately on flush
            val reader = RangeReader(
                client = client,
                urlProvider = { "https://cdn.example.com/media.bin" },
            )
            reader.read(0L, 1024L) { channel ->
                assertEquals(1024, channel.readRemaining().readByteArray().size)
            }
            reader.close()
            assertEquals(3_024L, client.currentTransferQuota?.account?.download?.usedBytes)
            assertEquals(4_024L, client.currentTransferQuota?.account?.downloadDaily?.usedBytes)

            // Now server catches up to 9_800L; next explicit getQuota adopts server truth cleanly
            serverAboutUsage = 9_800L
            serverAboutTrash = 1_000L
            assertEquals(9_800L, client.getQuota().quota.usageBytes)

            client.close()
        }
    }

    @Test
    fun multiAccountSnapshotsAndOptimisticRollbacksStayIsolated() {
        runBlocking {
            val snapshots = mutableMapOf<String, AccountSnapshot>()
            val store = object : SessionStore {
                override suspend fun load(account: String) =
                    Session("tok-$account", "ref-$account", "sub-$account", expiresAt = Long.MAX_VALUE / 2)
                override suspend fun save(account: String, session: Session) = Unit
                override suspend fun clear(account: String) = Unit
                override suspend fun loadAccountSnapshot(account: String): AccountSnapshot? = snapshots[account]
                override suspend fun saveAccountSnapshot(account: String, snapshot: AccountSnapshot) {
                    snapshots[account] = snapshot
                }
            }
            val engine = MockEngine { req ->
                val auth = req.headers[HttpHeaders.Authorization].orEmpty()
                val isFree = "tok-free@x" in auth
                when (req.url.encodedPath) {
                    "/drive/v1/about" -> respondJson(
                        if (isFree) aboutBody(usage = 2_000L, trash = 100L, limit = 6_442_450_944L)
                        else aboutBody(usage = 9_000L, trash = 500L, limit = 10_995_116_277_760L)
                    )
                    "/vip/v1/quantity/list" -> respondJson(
                        if (isFree) transferBody(uploadUsed = 0L, downloadUsed = 0L, offlineUsed = 0L, vipStatus = "invalid")
                        else transferBody(uploadUsed = 100L, downloadUsed = 200L, offlineUsed = 300L, vipStatus = "ok")
                    )
                    else -> error("unexpected ${req.url}")
                }
            }
            val freeClient = PikPakClient("free@x", "pw", sessionStore = store, httpClient = HttpClient(engine)).apply {
                reconcileAccountStateInBackground = false
            }
            val vipClient = PikPakClient("vip@x", "pw", sessionStore = store, httpClient = HttpClient(engine)).apply {
                reconcileAccountStateInBackground = false
            }

            freeClient.getQuota(Duration.ZERO)
            freeClient.getTransferQuota(Duration.ZERO)
            vipClient.getQuota(Duration.ZERO)
            vipClient.getTransferQuota(Duration.ZERO)
            delay(260.milliseconds)

            assertEquals(true, freeClient.isFreeAccountFlow.value)
            assertEquals(false, vipClient.isFreeAccountFlow.value)
            assertEquals(2_000L, snapshots["free@x"]?.quota?.quota?.usageBytes)
            assertEquals(9_000L, snapshots["vip@x"]?.quota?.quota?.usageBytes)

            // Pre-request optimistic trash on freeClient updates live quotaFlow but does NOT pollute persisted snapshot
            val fileA = FileStat(id = "f-free", name = "a.mp4", size = "500", trashed = false)
            freeClient.observeFiles(listOf(fileA))
            freeClient.noteFilesTrashed(listOf("f-free"))
            assertEquals(1_500L, freeClient.quotaFlow.value?.quota?.usageBytes)
            delay(260.milliseconds)
            assertEquals(2_000L, snapshots["free@x"]?.quota?.quota?.usageBytes)

            // Simulating account switch cancelling in-flight write: revertFiles restores freeClient's live flow
            freeClient.revertFiles(listOf(fileA))
            assertEquals(2_000L, freeClient.quotaFlow.value?.quota?.usageBytes)
            assertEquals(9_000L, vipClient.quotaFlow.value?.quota?.usageBytes)

            freeClient.close()
            vipClient.close()
        }
    }

    private fun mockClient(
        store: SessionStore = InMemorySessionStore(),
        handler: suspend MockRequestHandleScope.(path: String, body: String) -> io.ktor.client.request.HttpResponseData,
    ): PikPakClient {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/v1/shield/captcha/init") -> respondJson("""{"captcha_token":"CAP"}""")
                path.endsWith("/v1/auth/signin") ->
                    respondJson("""{"access_token":"AT","refresh_token":"RT","sub":"UID","expires_in":3600}""")
                else -> handler(path, (req.body as? TextContent)?.text.orEmpty())
            }
        }
        return PikPakClient(
            account = "mock@x",
            password = "pw",
            sessionStore = store,
            httpClient = HttpClient(engine),
        )
    }

    private fun aboutBody(
        usage: Long,
        trash: Long,
        limit: Long = 10_995_116_277_760L,
        cloudDownloadUsed: Long = 0L,
    ): String = """
        {
          "kind": "drive#about",
          "quota": {
            "kind": "drive#quota",
            "limit": "$limit",
            "usage": "$usage",
            "usage_in_trash": "$trash",
            "play_times_limit": "-1",
            "play_times_usage": "0"
          },
          "expires_at": "2027-04-17T02:40:40+08:00",
          "quotas": {
            "cloud_download": {
              "limit": "3",
              "usage": "$cloudDownloadUsed",
              "is_unlimited": false,
              "complimentary": "0"
            }
          }
        }
    """.trimIndent()

    private fun transferBody(
        uploadUsed: Long,
        downloadUsed: Long,
        offlineUsed: Long,
        vipStatus: String,
        dailyLimit: Long = 0L,
        dailyUsed: Long = 0L,
    ): String = """
        {
          "transfer": {"offline": {"info": "", "total_assets": 0, "assets": 0}},
          "base": {
            "info": "",
            "vip_status": "$vipStatus",
            "expire_time": "2027-04-17T02:40:40+08:00",
            "assets": "10T",
            "size": 0,
            "offline": {"total_assets": 43980465111040, "assets": $offlineUsed, "size": $offlineUsed},
            "download": {"total_assets": 4398046511104, "assets": $downloadUsed, "size": $downloadUsed},
            "upload": {"total_assets": 1099511627776, "assets": $uploadUsed, "size": $uploadUsed},
            "download_daily": {"total_assets": $dailyLimit, "assets": $dailyUsed, "size": $dailyUsed}
          },
          "apps_summary": {
            "offline": {"total_assets": 10995116277760, "assets": 0, "size": 0},
            "download": {"total_assets": 1099511627776, "assets": 0, "size": 0}
          }
        }
    """.trimIndent()

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
