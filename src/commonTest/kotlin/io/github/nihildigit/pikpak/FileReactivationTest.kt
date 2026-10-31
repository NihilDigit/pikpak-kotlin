package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.FileReactivationCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Cold storage is a protocol state rather than a malformed range or a transport retry. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileReactivationTest {
    @Test
    fun `cold header precedes status and content range validation`() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.PartialContent)) {
            fixture { cold(status) }.use { fixture ->
                var entered = false
                val error = assertFailsWith<FileReactivatingException> {
                    fixture.client.streamRangeFromUrl(URL, 0, 1) { entered = true }
                }
                assertEquals(status.value, error.httpStatus)
                assertFalse(entered, "cold response must not expose its placeholder body")
                assertEquals(1, fixture.requests.size, "cold state must not spend HTTP retries")
            }
        }
    }

    @Test
    fun `ordinary responses retain the range contract`() = runTest {
        for (marker in listOf(null, "4", "50")) {
            fixture {
                respond("placeholder", HttpStatusCode.OK, Headers.build {
                    marker?.let { append("X-Xos-Err-Desc", it) }
                })
            }.use { fixture ->
                val error = assertFailsWith<PikPakException> {
                    fixture.client.streamRangeFromUrl(URL, 0, 1) { error("body exposed") }
                }
                assertFalse(error is FileReactivatingException)
                assertEquals(200, error.httpStatus)
            }
        }
    }

    @Test
    fun `concurrent waiters share one wake and serial one byte probes`() = runTest {
        val wakeEntered = CompletableDeferred<Unit>()
        val releaseWake = CompletableDeferred<Unit>()
        var probes = 0
        var activeProbes = 0
        var peakProbes = 0
        fixture { request ->
            if (request.headers[HttpHeaders.Range] == null) {
                wakeEntered.complete(Unit)
                releaseWake.await()
                respond("", HttpStatusCode.OK)
            } else {
                activeProbes++
                peakProbes = maxOf(peakProbes, activeProbes)
                delay(3)
                activeProbes--
                probes++
                if (probes < 3) cold() else ready()
            }
        }.use { fixture ->
            val coordinator = fixture.coordinator()
            val states = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.isReactivating.collect { states += it }
            }
            val waiters = List(16) { async { coordinator.awaitReady(0, URL) { error("unexpected refresh") } } }
            wakeEntered.await()
            runCurrent()
            assertTrue(coordinator.isReactivating.value)
            assertEquals(1, fixture.requests.size)
            assertTrue(waiters.none { it.isCompleted })
            releaseWake.complete(Unit)
            runCurrent()
            advanceTimeBy(9)
            runCurrent()
            assertEquals(1, fixture.requests.size, "first probe must wait for the poll interval")
            advanceUntilIdle()
            waiters.awaitAll()
            assertEquals(listOf(null, "bytes=0-0", "bytes=0-0", "bytes=0-0"), fixture.ranges())
            assertTrue(fixture.requests.all { it.method == HttpMethod.Get })
            assertEquals(1, peakProbes)
            assertEquals(1L, coordinator.generation)
            assertEquals(listOf(false, true, false), states)
        }
    }

    @Test
    fun `late cold response from an old generation does not wake again`() = runTest {
        fixture { request -> if (request.headers[HttpHeaders.Range] == null) respond("") else ready() }.use { fixture ->
            val coordinator = fixture.coordinator()
            coordinator.awaitReady(0, URL) { error("unexpected refresh") }
            val completedRequests = fixture.requests.size
            coordinator.awaitReady(0, URL) { error("stale generation refreshed") }
            assertEquals(completedRequests, fixture.requests.size)
            assertFalse(coordinator.isReactivating.value)
            // A genuinely new cold wave can still wake the same file.
            coordinator.awaitReady(1, URL) { error("unexpected refresh") }
            assertEquals(2L, coordinator.generation)
            assertEquals(listOf(null, "bytes=0-0", null, "bytes=0-0"), fixture.ranges())
        }
    }

    @Test
    fun `timeout fails all waiters and clears state for another wave`() = runTest {
        var readable = false
        fixture { request ->
            if (request.headers[HttpHeaders.Range] == null) respond("")
            else if (readable) ready() else cold()
        }.use { fixture ->
            val coordinator = FileReactivationCoordinator(fixture.client, 10.milliseconds, 25.milliseconds)
            val waiters = List(4) { async { runCatching { coordinator.awaitReady(0, URL) { it } } } }
            runCurrent()
            assertTrue(coordinator.isReactivating.value)
            advanceUntilIdle()
            waiters.awaitAll().forEach { assertIs<FileReactivatingException>(it.exceptionOrNull()) }
            assertEquals(25L, testScheduler.currentTime)
            assertFalse(coordinator.isReactivating.value)
            assertEquals(0L, coordinator.generation)
            assertEquals(listOf(null, "bytes=0-0", "bytes=0-0"), fixture.ranges())
            readable = true
            coordinator.awaitReady(0, URL) { it }
            assertEquals(1L, coordinator.generation)
            assertFalse(coordinator.isReactivating.value)
        }
    }

    @Test
    fun `leader cancellation clears state and releases followers`() = runTest {
        fixture { request -> if (request.headers[HttpHeaders.Range] == null) respond("") else ready() }.use { fixture ->
            val coordinator = fixture.coordinator()
            val leader = async { coordinator.awaitReady(0, URL) { it } }
            runCurrent()
            val follower = async { runCatching { coordinator.awaitReady(0, URL) { it } } }
            runCurrent()
            assertTrue(coordinator.isReactivating.value)
            leader.cancelAndJoin()
            assertIs<CancellationException>(follower.await().exceptionOrNull())
            assertFalse(coordinator.isReactivating.value)
            assertEquals(0L, coordinator.generation)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf<String?>(null), fixture.ranges(), "cancelled leader must not leave a detached poll")
            coordinator.awaitReady(0, URL) { it }
            assertEquals(1L, coordinator.generation)
        }
    }

    @Test
    fun `cancelling a follower does not cancel the shared wake`() = runTest {
        fixture { request -> if (request.headers[HttpHeaders.Range] == null) respond("") else ready() }.use { fixture ->
            val coordinator = fixture.coordinator()
            val leader = async { coordinator.awaitReady(0, URL) { it } }
            runCurrent()
            val follower = async { coordinator.awaitReady(0, URL) { it } }
            runCurrent()
            follower.cancelAndJoin()
            assertTrue(coordinator.isReactivating.value)
            advanceUntilIdle()
            leader.await()
            assertFalse(coordinator.isReactivating.value)
            assertEquals(listOf(null, "bytes=0-0"), fixture.ranges())
        }
    }

    @Test
    fun `expired signatures refresh on both wake and probe without a second successful wake`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            fixture { request ->
                when (request.url.parameters["sig"]) {
                    "old" -> respond("", status)
                    "wake" -> if (request.headers[HttpHeaders.Range] == null) respond("") else respond("", status)
                    "ready" -> ready()
                    else -> error("unexpected signed URL")
                }
            }.use { fixture ->
                val refreshed = mutableListOf<String>()
                val coordinator = fixture.coordinator()
                coordinator.awaitReady(0, "$URL?sig=old") { rejected ->
                    refreshed += rejected
                    if (refreshed.size == 1) "$URL?sig=wake" else "$URL?sig=ready"
                }
                assertEquals(listOf("$URL?sig=old", "$URL?sig=wake"), refreshed)
                assertEquals(listOf("old", "wake", "wake", "ready"), fixture.requests.map { it.url.parameters["sig"] })
                assertEquals(listOf(null, null, "bytes=0-0", "bytes=0-0"), fixture.ranges())
                assertEquals(1L, coordinator.generation)
                assertFalse(coordinator.isReactivating.value)
            }
        }
    }

    @Test
    fun `handle exposes cold state and resumes the original read`() = runTest {
        var warmed = false
        val releaseProbe = CompletableDeferred<Unit>()
        val probeEntered = CompletableDeferred<Unit>()
        fixture { request ->
            when (request.headers[HttpHeaders.Range]) {
                null -> { warmed = true; respond("") }
                "bytes=0-0" -> {
                    probeEntered.complete(Unit)
                    releaseProbe.await()
                    ready()
                }
                "bytes=1-2" -> if (!warmed) cold() else respond(
                    ByteReadChannel(byteArrayOf(11, 12)), HttpStatusCode.PartialContent,
                    Headers.build {
                        append(HttpHeaders.ContentRange, "bytes 1-2/3")
                        append(HttpHeaders.ContentLength, "2")
                    },
                )
                else -> error("unexpected range")
            }
        }.use { fixture ->
            val handle = PikPakFileHandle(
                client = fixture.client,
                gcid = "A3C62CB153B61CF265298986AD97EA07A85112DF",
                size = 3,
                name = "fixture.bin",
                initialLink = VariantLink(URL, expiresAt = null),
                reactivationPollInterval = 10.milliseconds,
                reactivationTimeout = 100.milliseconds,
            )
            val cache = handle.openCache(coroutineContext = backgroundScope.coroutineContext)
            try {
                assertFalse(handle.isReactivating.value)
                assertTrue(cache.isReactivating === handle.isReactivating)
                val read = async { handle.readBytes(1, 2) }
                probeEntered.await()
                assertTrue(handle.isReactivating.value)
                assertTrue(cache.isReactivating.value)
                assertFalse(read.isCompleted)
                releaseProbe.complete(Unit)
                assertContentEquals(byteArrayOf(11, 12), read.await())
                assertFalse(handle.isReactivating.value)
                assertFalse(fixture.client.hostHealth.isBad(URL))
                assertEquals(0, handle.readerForTest().stats.value.retries)
                assertEquals(0, handle.readerForTest().stats.value.urlRefreshes)
                assertEquals(listOf("bytes=1-2", null, "bytes=0-0", "bytes=1-2"), fixture.ranges())
            } finally {
                releaseProbe.complete(Unit)
                cache.close()
                handle.close()
            }
        }
    }

    private fun TestScope.fixture(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Fixture {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request
                handler(request)
            }
        })
        val http = HttpClient(engine)
        val client = PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            cdnHttpClient = http,
            retryPolicy = RetryPolicy(maxAttempts = 1),
            steerEdgeHosts = false,
        )
        return Fixture(client, http, requests)
    }

    private class Fixture(
        val client: PikPakClient,
        private val http: HttpClient,
        val requests: MutableList<HttpRequestData>,
    ) : AutoCloseable {
        fun coordinator() = FileReactivationCoordinator(client, 10.milliseconds, 100.milliseconds)
        fun ranges() = requests.map { it.headers[HttpHeaders.Range] }
        override fun close() {
            client.close()
            http.close()
        }
    }

    private fun MockRequestHandleScope.cold(status: HttpStatusCode = HttpStatusCode.PartialContent) = respond(
        ByteReadChannel("archived placeholder"), status,
        Headers.build { append("X-Xos-Err-Desc", " 5 ") },
    )

    private fun MockRequestHandleScope.ready() = respond(
        ByteReadChannel(byteArrayOf(7)), HttpStatusCode.PartialContent,
        Headers.build {
            append(HttpHeaders.ContentRange, "bytes 0-0/3")
            append(HttpHeaders.ContentLength, "1")
        },
    )

    private companion object {
        const val URL = "https://cdn.example.com/file"
    }
}
