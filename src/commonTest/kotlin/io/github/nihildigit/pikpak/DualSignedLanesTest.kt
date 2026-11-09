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
        // Progress must cross two seconds before the final chunk (which cannot expand a lane).
        var bodyDelay = 2_100L
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
    fun `a slow sustained direct transfer without scaling evidence never mints a second lane`() = runTest {
        val f = Fixture(this)
        f.bodyDelay = 2100
        val handle = f.handle()
        try {
            handle.readBytes(0, 32768)
            advanceUntilIdle()
            assertEquals(0, f.details)
            assertEquals(8, handle.connectionLimit.value)
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
    fun `signature identity ignores sibling host and query ordering`() {
        assertEquals(SignedUrlGates.identityOf("https://a/f?sign=x&expires=1"),
            SignedUrlGates.identityOf("https://b/f?expires=1&sign=x"))
        assertTrue(SignedUrlGates.identityOf("https://a/f?sign=x") != SignedUrlGates.identityOf("https://a/f?sign=y"))
    }
}
