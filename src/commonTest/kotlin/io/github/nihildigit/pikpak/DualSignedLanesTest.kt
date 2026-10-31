package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.SignedUrlGates
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DualSignedLanesTest {
    private class Fixture(val scope: TestScope, val sameIdentity: Boolean = false, val failDetail: Boolean = false) {
        var details = 0
        val detailTimes = mutableListOf<Long>()
        var hold = false
        var bodyDelay = 100L
        var rejectSecondary = false
        var detailReply: (suspend (Int) -> String)? = null
        val release = CompletableDeferred<Unit>()
        val active = mutableMapOf<String, Int>()
        val peaks = mutableMapOf<String, Int>()
        var accountPeak = 0
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
            when {
                request.url.encodedPath.endsWith("/shield/captcha/init") -> respond("""{"captcha_token":"C","expires_in":300,"url":""}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/auth/signin") -> respond("""{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.method.value == "DELETE" -> respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.contains("/drive/v1/files/") -> {
                    details++
                    detailTimes += scope.testScheduler.currentTime
                    if (failDetail) respond("{}", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                    else respond(detailReply?.invoke(details) ?: """{"id":"f1","size":"32768","links":{"application/octet-stream":{"url":"https://cdn/file?sign=${if (sameIdentity) "a" else "b"}"}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                rejectSecondary && request.url.parameters["sign"] == "b" -> respond("", HttpStatusCode.Forbidden)
                else -> {
                    val signature = request.url.parameters["sign"]!!
                    active[signature] = (active[signature] ?: 0) + 1
                    peaks[signature] = maxOf(peaks[signature] ?: 0, active[signature]!!)
                    accountPeak = maxOf(accountPeak, active.values.sum())
                    if (hold) release.await()
                    val channel = ByteChannel(autoFlush = true)
                    scope.launch {
                        try {
                            channel.writeFully(ByteArray(16384))
                            delay(bodyDelay)
                            channel.writeFully(ByteArray(8192))
                            delay(bodyDelay)
                            channel.writeFully(ByteArray(8192))
                            channel.flushAndClose()
                        } finally { active[signature] = active[signature]!! - 1 }
                    }
                    respond(channel, HttpStatusCode.PartialContent, Headers.build {
                        append(HttpHeaders.ContentRange, "bytes 0-32767/32768")
                        append(HttpHeaders.ContentLength, "32768")
                    })
                }
            }
            }
        })
        val http = HttpClient(engine)
        val client = PikPakClient("mock", "pw", sessionStore = InMemorySessionStore(),
            httpClient = http, cdnHttpClient = http, accountConnectionBudget = 24,
            timeSource = scope.testScheduler.timeSource)
        fun handle(leased: Boolean = false) = PikPakFileHandle(client,
            gcid = "A3C62CB153B61CF265298986AD97EA07A85112DF", size = 32768, name = "file",
            initialFileId = "f1", initialLink = VariantLink("https://cdn/file?sign=a", null), leased = leased)
    }

    @Test
    fun `two slow samples with persistent demand mint and balance eight slots per signature`() = runTest {
        val f = Fixture(this)
        val handle = f.handle()
        try {
            handle.updateConnectionAllowance(16)
            assertEquals(8, handle.connectionLimit.value)
            handle.readBytes(0, 32768)
            assertEquals(0, f.details)
            handle.readBytes(0, 32768)
            advanceUntilIdle()
            assertEquals(1, f.details)
            assertTrue(f.detailTimes.single() >= 1500)
            assertEquals(16, handle.connectionLimit.value)
            f.hold = true
            val reads = List(24) { async { handle.readBytes(0, 32768) } }
            runCurrent()
            assertEquals(mapOf("a" to 8, "b" to 8), f.active)
            f.release.complete(Unit)
            reads.awaitAll()
            assertEquals(16, f.accountPeak)
            assertTrue(f.peaks.values.all { it <= 8 })
            handle.updateConnectionAllowance(6)
            assertEquals(6, handle.connectionLimit.value)
        } finally { f.release.complete(Unit); handle.close(); f.client.close() }
    }

    @Test
    fun `two completed direct reads do not mint while idle`() = runTest {
        val f = Fixture(this)
        val handle = f.handle()
        try {
            repeat(2) { handle.readBytes(0, 32768) }
            advanceUntilIdle()
            assertEquals(0, f.details)
            assertEquals(8, handle.connectionLimit.value)
        } finally { handle.close(); f.client.close() }
    }

    @Test
    fun `withdrawn cache demand during cooldown prevents mint`() = runTest {
        val f = Fixture(this)
        val handle = f.handle()
        try {
            handle.updateConnectionAllowance(16)
            repeat(2) { handle.readBytes(0, 32768) }
            runCurrent()
            handle.updateConnectionAllowance(0)
            advanceUntilIdle()
            assertEquals(0, f.details)
            assertEquals(0, handle.connectionLimit.value)
        } finally { handle.close(); f.client.close() }
    }

    @Test
    fun `duplicate signature and failed detail retain single lane`() = runTest {
        for (failed in listOf(false, true)) {
            val f = Fixture(this, sameIdentity = true, failDetail = failed)
            val handle = f.handle()
            try {
                handle.updateConnectionAllowance(16)
                repeat(2) { handle.readBytes(0, 32768) }
                advanceUntilIdle()
                assertEquals(1, f.details)
                assertEquals(8, handle.connectionLimit.value)
            } finally { handle.close(); f.client.close() }
        }
    }

    @Test
    fun `eight connection allowance never mints extra capacity`() = runTest {
        val f = Fixture(this)
        val handle = f.handle()
        try {
            handle.updateConnectionAllowance(8)
            repeat(3) { handle.readBytes(0, 32768) }
            advanceUntilIdle()
            assertEquals(0, f.details)
            assertEquals(8, handle.connectionLimit.value)
        } finally { handle.close(); f.client.close() }
    }

    @Test
    fun `secondary rejection retires only that lane when refresh repeats its signature`() = runTest {
        val f = Fixture(this)
        val handle = f.handle()
        try {
            handle.updateConnectionAllowance(16)
            repeat(2) { handle.readBytes(0, 32768) }
            advanceUntilIdle()
            f.rejectSecondary = true
            val results = List(2) { async { runCatching { handle.readBytes(0, 32768) } } }.awaitAll()
            assertEquals(2, results.count { it.isSuccess })
            assertEquals(0, results.count { it.isFailure })
            assertEquals(8, handle.connectionLimit.value)
            assertEquals(2, f.details)
        } finally { handle.close(); f.client.close() }
    }

    @Test
    fun `one sustained direct transfer can enable the second lane`() = runTest {
        val f = Fixture(this)
        f.bodyDelay = 2100
        val handle = f.handle()
        try {
            handle.readBytes(0, 32768)
            advanceUntilIdle()
            assertEquals(1, f.details)
            assertEquals(16, handle.connectionLimit.value)
        } finally { handle.close(); f.client.close() }
    }

    @Test
    fun `leased slow reads never mint a second signature`() = runTest {
        val f = Fixture(this)
        val handle = f.handle(leased = true)
        try {
            handle.updateConnectionAllowance(16)
            repeat(3) { handle.readBytes(0, 32768) }
            advanceUntilIdle()
            assertEquals(8, handle.maxConnections)
            assertEquals(8, handle.connectionLimit.value)
            assertEquals(0, f.details)
        } finally { handle.close(); f.client.close() }
    }

    @Test
    fun `retired secondary late refresh success cannot replace its successor`() = runTest {
        retiredRefresh(false)
    }

    @Test
    fun `retired secondary late refresh failure cannot clear its successor`() = runTest {
        retiredRefresh(true)
    }

    private suspend fun TestScope.retiredRefresh(failOldRefresh: Boolean) {
        val f = Fixture(this)
        val handle = f.handle()
        val primaryReply = CompletableDeferred<Unit>()
        val oldReply = CompletableDeferred<Unit>()
        fun detail(signature: String) =
            """{"id":"f1","size":"32768","links":{"application/octet-stream":{"url":"https://cdn/file?sign=$signature"}}}"""
        f.detailReply = { call ->
            when (call) {
                1 -> detail("b")
                2 -> { primaryReply.await(); detail("b") }
                3 -> {
                    oldReply.await()
                    if (failOldRefresh) """{"id":"f1","size":"32768"}""" else detail("c")
                }
                else -> detail("d")
            }
        }
        try {
            handle.updateConnectionAllowance(16)
            repeat(2) { handle.readBytes(0, 32768) }
            advanceUntilIdle()
            val old = assertNotNull(handle.secondaryReaderForTest())
            old.prewarm()
            // The primary owns detailMutex while the old secondary starts refreshing.
            val primary = async { handle.provideUrl(UrlRequest.Expired("https://cdn/file?sign=a")) }
            runCurrent()
            assertEquals(2, f.details)
            f.rejectSecondary = true
            val staleRead = async { runCatching { old.readBytes(0, 32768) } }
            runCurrent()
            primaryReply.complete(Unit)
            primary.await()
            runCurrent()
            assertEquals(8, handle.connectionLimit.value)
            // The refresh entered on the old generation but returns after its retirement.
            f.rejectSecondary = false
            handle.readBytes(0, 32768)
            oldReply.complete(Unit)
            staleRead.await()
            advanceUntilIdle()
            val successor = assertNotNull(handle.secondaryReaderForTest())
            assertTrue(successor !== old)
            assertSame(old.gateForTest(), successor.gateForTest())
            assertEquals("https://cdn/file?sign=d", successor.prewarm())
            assertEquals(16, handle.connectionLimit.value)
            val before = f.details
            // A later retry on the retired reader must not mint or touch the successor.
            f.rejectSecondary = true
            runCatching { old.readBytes(0, 32768) }
            assertEquals(before, f.details)
            assertSame(successor, handle.secondaryReaderForTest())
            assertEquals(16, handle.connectionLimit.value)
        } finally {
            primaryReply.complete(Unit)
            oldReply.complete(Unit)
            handle.close()
            f.client.close()
        }
    }

    @Test
    fun `signature identity ignores sibling host and query ordering`() {
        assertEquals(SignedUrlGates.identityOf("https://a/f?sign=x&expires=1"),
            SignedUrlGates.identityOf("https://b/f?expires=1&sign=x"))
        assertTrue(SignedUrlGates.identityOf("https://a/f?sign=x") != SignedUrlGates.identityOf("https://a/f?sign=y"))
    }
}
