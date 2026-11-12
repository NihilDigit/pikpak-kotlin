package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.PriorityGate
import io.github.nihildigit.pikpak.internal.SignedRequestPriority
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoiSchedulingTest {
    @Test
    fun `background reserve admits foreground immediately and refills before background`() = runTest {
        val limiter = RateLimiter(24, 20.0, timeSource = testScheduler.timeSource)
        repeat(16) { limiter.acquire(ApiRequestPriority.BACKGROUND) }
        val background = async { limiter.acquire(ApiRequestPriority.BACKGROUND) }
        runCurrent()
        assertFalse(background.isCompleted)
        val foreground = async { repeat(8) { limiter.acquire() } }
        runCurrent()
        assertTrue(foreground.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
        advanceTimeBy(449)
        runCurrent()
        assertFalse(background.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(background.isCompleted)
        background.await()
    }

    @Test
    fun `cancelled background reservations preserve waterline and small buckets retain old behavior`() = runTest {
        val limiter = RateLimiter(12, 10.0, timeSource = testScheduler.timeSource)
        repeat(8) { limiter.acquire(-10) }
        val abandoned = List(10) { async { limiter.acquire(-20) } }
        runCurrent()
        abandoned.forEach { it.cancelAndJoin() }
        repeat(4) { limiter.acquire(10) }
        val next = async { limiter.acquire(-10) }
        advanceTimeBy(499)
        runCurrent()
        assertFalse(next.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        next.await()
        val small = RateLimiter(5, 5.0, timeSource = testScheduler.timeSource)
        repeat(5) { small.acquire(-10) }
        val sixth = async { small.acquire(-10) }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(sixth.isCompleted)
    }

    @Test
    fun `endpoint defaults leave background scan queued while interactive requests pass`() = runTest {
        val limiter = RateLimiter(24, 20.0, timeSource = testScheduler.timeSource)
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
            val body = when {
                request.url.encodedPath.endsWith("/shield/captcha/init") -> """{"captcha_token":"C"}"""
                request.url.encodedPath.endsWith("/auth/signin") -> """{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}"""
                request.url.encodedPath == "/drive/v1/files" && request.method.value == "POST" -> """{"file":{"id":"F"}}"""
                else -> """{"sync_time":"S","files":[]}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }))
        val client = PikPakClient("mock", "pw", httpClient = http, sessionStore = InMemorySessionStore(), rateLimiter = limiter)
        try {
            client.login()
            advanceTimeBy(1000)
            repeat(16) { limiter.acquire(-10) }
            val scan = async { client.listFileChangesPage() }
            runCurrent()
            assertFalse(scan.isCompleted)
            val delta = async { client.listFileChangesPage("S") }
            val create = async { client.createFolder("", "test") }
            val rename = async { client.rename("F", "new") }
            runCurrent()
            assertTrue(delta.isCompleted && create.isCompleted && rename.isCompleted)
            assertFalse(scan.isCompleted)
            val explicit = async {
                withApiPriority(-20) { client.listFileChangesPage("S") }
            }
            runCurrent()
            assertFalse(explicit.isCompleted)
            scan.cancelAndJoin()
            explicit.cancelAndJoin()
            withApiPriority(-20) {
                withDefaultApiPriority(10) {
                    assertEquals(-20, currentCoroutineContext()[ApiRequestPriority]?.priority)
                }
            }
        } finally { client.close(); http.close() }
    }

    @Test
    fun `API activity does not change data admission without an owned demand`() = runTest {
        val http = HttpClient(MockEngine { respond("{}") })
        val client = PikPakClient("mock", "pw", httpClient = http,
            sessionStore = InMemorySessionStore(), accountConnectionBudget = 8,
            timeSource = testScheduler.timeSource)
        client.concurrencyMode = ConcurrencyMode.Fixed(8)
        val reg = client.scheduler.register(connectionBudget = 8, onScheduleChanged = {})
        try {
            assertEquals(8, client.scheduler.connectionAllowanceFor(reg))
            withApiPriority(ApiRequestPriority.BACKGROUND) { client.withInteractiveApiPriority {} }
            assertEquals(8, client.scheduler.connectionAllowanceFor(reg))
            client.withInteractiveApiPriority {}
            assertEquals(8, client.scheduler.connectionAllowanceFor(reg))
            advanceTimeBy(501)
            assertEquals(8, client.scheduler.connectionAllowanceFor(reg))
        } finally { client.scheduler.unregister(reg); client.close(); http.close() }
    }

    @Test
    fun `warm for next file overtakes comfortable far read ahead at shared gate`() = runTest {
        val gate = PriorityGate(1)
        val served = mutableListOf<Pair<String, Int>>()
        fun source(name: String) = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
                gate.acquire(priority)
                try {
                    served += name to priority
                    return block(ByteReadChannel(ByteArray(length.toInt())))
                } finally { gate.release() }
            }
        }
        fun cache(name: String) = BlockCache(source(name), 1024, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 256, memoryCapBytes = 512, wideBlockThresholdBytes = 64,
            timeSource = testScheduler.timeSource)
        val current = cache("current")
        val next = cache("next")
        val owner = TransferDemand(ConsumerIntent(service = ServiceIntent.PLAYING))
        try {
            current.warm(listOf(0L..127L), StreamRole.FOREGROUND, null).await()
            served.clear()
            gate.acquire(100)
            val ticket = current.beginRead(owner, 0L)
            assertEquals(1, ticket.readSome(ByteArray(1), 0, 1))
            runCurrent()
            assertEquals(1, gate.queued)
            val warm = next.warm(listOf(0L..63L), StreamRole.FOREGROUND, null)
            runCurrent()
            assertEquals(2, gate.queued)
            gate.release()
            runCurrent()
            warm.await()
            assertEquals("next" to PikPakStreamReader.WARM_PRIORITY, served.first())
            assertTrue(served.drop(1).any { it == "current" to PikPakStreamReader.FAR_READ_AHEAD_PRIORITY })
        } finally {
            owner.close()
            current.close(); next.close()
            current.awaitClosedForTest(); next.awaitClosedForTest()
        }
    }

    @Test
    fun `near range stops before it can carry its priority into far prefetch`() = runTest {
        val requests = mutableListOf<Triple<Long, Long, Int>>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
                requests += Triple(start, length, priority)
                return block(ByteReadChannel(ByteArray(length.toInt())))
            }
        }
        val cache = BlockCache(source, 1024, 1, coroutineContext, null,
            blockSize = 64, readAheadBytes = 256, memoryCapBytes = 512, wideBlockThresholdBytes = 64,
            timeSource = testScheduler.timeSource)
        val owner = TransferDemand(ConsumerIntent(service = ServiceIntent.PLAYING))
        try {
            cache.warm(listOf(0L..63L), StreamRole.FOREGROUND, null).await()
            requests.clear()
            val ticket = cache.beginRead(owner, 0L)
            assertEquals(1, ticket.readSome(ByteArray(1), 0, 1))
            runCurrent()
            assertEquals(Triple(64L, 64L, PikPakStreamReader.READ_AHEAD_PRIORITY), requests.first())
            assertTrue(requests.drop(1).any { it.first == 128L && it.third == PikPakStreamReader.FAR_READ_AHEAD_PRIORITY })
        } finally { owner.close(); cache.close(); cache.awaitClosedForTest() }
    }

    @Test
    fun `in flight near blocks do not qualify as comfortable cached bytes`() = runTest {
        val priorities = mutableListOf<Int>()
        val source = object : RangeSource {
            override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
                priorities += priority
                awaitCancellation()
            }
        }
        val cache = BlockCache(source, 1024, 2, coroutineContext, null,
            blockSize = 64, readAheadBytes = 256, memoryCapBytes = 1024, wideBlockThresholdBytes = 64,
            timeSource = testScheduler.timeSource)
        val owner = TransferDemand(ConsumerIntent(service = ServiceIntent.PLAYING))
        try {
            val ticket = cache.beginRead(owner, 0L)
            val read = async { ticket.readSome(ByteArray(1), 0, 1) }
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertTrue(PikPakStreamReader.READ_AHEAD_PRIORITY in priorities)
            assertFalse(PikPakStreamReader.FAR_READ_AHEAD_PRIORITY in priorities)
            read.cancelAndJoin()
        } finally { owner.close(); cache.close(); cache.awaitClosedForTest() }
    }

    @Test
    fun `micro ranges overtake background at signed gate but explicit priority wins`() = runTest {
        val http = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
            respond(ByteReadChannel(ByteArray(1)), HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange to listOf("bytes 0-0/1"), HttpHeaders.ContentLength to listOf("1")))
            }
        }))
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http, sessionStore = InMemorySessionStore())
        val release = CompletableDeferred<Unit>()
        val holdOthers = CompletableDeferred<Unit>()
        val url = "https://cdn/file?sign=roi"
        val holders = List(8) { index -> async {
            client.signedUrlGates.withSlot(url) { if (index == 0) release.await() else holdOthers.await() }
        } }
        try {
            runCurrent()
            val order = mutableListOf<String>()
            val background = async {
                withContext(SignedRequestPriority(1)) {
                    client.streamRangeFromUrl(url, 0, 1) { order += "background" }
                }
            }
            val large = async { client.streamRangeFromUrl(url, 0, 65537) { order += "large" } }
            val open = async { client.streamRangeFromUrl(url, 0) { order += "open" } }
            val micro = async { client.streamRangeFromUrl(url, 0, 65536) { order += "micro" } }
            val explicit = async {
                withContext(SignedRequestPriority(50)) {
                    client.streamRangeFromUrl(url, 0, 1) { order += "explicit" }
                }
            }
            runCurrent()
            release.complete(Unit)
            explicit.await(); micro.await(); background.await(); large.await(); open.await()
            assertEquals(listOf("explicit", "micro", "background", "large", "open"), order)
        } finally {
            release.complete(Unit)
            holdOthers.complete(Unit)
            holders.forEach { it.cancelAndJoin() }
            client.close(); http.close()
        }
    }
}
