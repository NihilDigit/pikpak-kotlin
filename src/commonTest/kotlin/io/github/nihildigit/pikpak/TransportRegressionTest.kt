package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.HostHealth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class TransportRegressionTest {
    @Test fun rateLimitRetriesWithoutRefresh(): Unit = runBlocking {
        var calls = 0
        var issued = 0
        val http = HttpClient(MockEngine {
            calls++
            if (calls <= 2) respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
            else respond(ByteReadChannel(byteArrayOf(1)), HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange to listOf("bytes 0-0/1"), HttpHeaders.ContentLength to listOf("1")))
        })
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http, sessionStore = InMemorySessionStore())
        val reader = RangeReader(client, { issued++; "https://cdn/file?sign=s" })
        try {
            assertContentEquals(byteArrayOf(1), reader.readBytes(0, 1))
            assertEquals(1, issued)
        } finally { reader.close(); client.close(); http.close() }
    }

    @Test fun reroutedFailureReportsOnceAndNeverPenalizesUnexecutedLinkHost() = runTest {
        val linkUrl = "https://dl-z01a-0026.mypikpak.com/download/?fid=1&sign=s" // congested primary -> steers to sibling
        val fastSeed = "https://dl-z01a-0002.mypikpak.com/download/?fid=1&sign=s"
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        if (request.url.host == "dl-z01a-0002.mypikpak.com") {
                            respond("forbidden", HttpStatusCode.Forbidden)
                        } else {
                            respond(
                                ByteReadChannel(byteArrayOf(9, 8, 7, 6)),
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes 0-3/4"),
                                    HttpHeaders.ContentLength to listOf("4"),
                                ),
                            )
                        }
                    }
                },
            ),
        )
        val client = PikPakClient(
            account = "mock",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            steerEdgeHosts = true,
            timeSource = testScheduler.timeSource,
        )
        client.hostHealth.observe(FileDetail(webContentLink = fastSeed))
        repeat(HostHealth.MIN_SAMPLES) {
            client.hostHealth.delivered(HostHealth.hostOf(fastSeed), HostHealth.MIN_SAMPLE_BYTES, 4_000_000L, fastSeed)
        }
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(client, { linkUrl }, onAttempt = { attempts += it }, timeSource = testScheduler.timeSource)
        try {
            val bytes = reader.readBytes(0L, 4L)
            assertContentEquals(byteArrayOf(9, 8, 7, 6), bytes)
            assertEquals(
                1,
                attempts.count { it.host == "dl-z01a-0002.mypikpak.com" },
                "rerouted 403 on 0002 must report exactly once",
            )
            assertFalse(client.hostHealth.isBad(linkUrl), "unexecuted primary linkUrl host must never be penalized")
        } finally {
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test fun bodySilenceRecordsOnlyBodyStallAndMidBodyTruncationDoesNotMarkSiblingRefused() = runTest {
        val primary = "https://dl-z01a-0001.mypikpak.com/download/?fid=stall&sign=s"
        val sibling = "https://dl-z01a-0002.mypikpak.com/download/?fid=stall&sign=s"
        val expected = ByteArray(16) { (it + 1).toByte() }
        var primaryCalls = 0
        var siblingCalls = 0

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val range = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-')
                        val start = range[0].toInt()
                        val end = range[1].toInt()
                        val len = end - start + 1
                        when {
                            request.url.host == "dl-z01a-0001.mypikpak.com" && primaryCalls++ == 0 -> {
                                val ch = ByteChannel(autoFlush = true)
                                this@runTest.backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                                    try {
                                        ch.writeFully(expected, start, start + 4)
                                        awaitCancellation()
                                    } catch (_: Throwable) {
                                    }
                                }
                                respond(
                                    ch,
                                    HttpStatusCode.PartialContent,
                                    headersOf(
                                        HttpHeaders.ContentRange to listOf("bytes $start-$end/${expected.size}"),
                                        HttpHeaders.ContentLength to listOf(len.toString()),
                                    ),
                                )
                            }
                            request.url.host == "dl-z01a-0002.mypikpak.com" && siblingCalls++ == 0 -> {
                                // Truncated mid-body: announces 12 bytes, sends only 4 bytes then closes EOF.
                                respond(
                                    ByteReadChannel(expected.copyOfRange(start, start + 4)),
                                    HttpStatusCode.PartialContent,
                                    headersOf(
                                        HttpHeaders.ContentRange to listOf("bytes $start-$end/${expected.size}"),
                                        HttpHeaders.ContentLength to listOf(len.toString()),
                                    ),
                                )
                            }
                            else -> respond(
                                ByteReadChannel(expected.copyOfRange(start, end + 1)),
                                HttpStatusCode.PartialContent,
                                headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/${expected.size}"),
                                    HttpHeaders.ContentLength to listOf(len.toString()),
                                ),
                            )
                        }
                    }
                },
            ),
        )
        val client = PikPakClient(
            account = "mock",
            password = "pw",
            httpClient = http,
            cdnHttpClient = http,
            sessionStore = InMemorySessionStore(),
            steerEdgeHosts = true,
            timeSource = testScheduler.timeSource,
        )
        client.hostHealth.observe(FileDetail(webContentLink = sibling))
        repeat(HostHealth.MIN_SAMPLES) {
            client.hostHealth.delivered(HostHealth.hostOf(sibling), HostHealth.MIN_SAMPLE_BYTES, 3_000_000L, sibling)
        }
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(
            client = client,
            urlProvider = { primary },
            onAttempt = { attempts += it },
            timeSource = testScheduler.timeSource,
        )
        try {
            val actual = reader.readBytes(0L, expected.size.toLong(), priority = PikPakStreamReader.BLOCKING_PRIORITY)
            assertContentEquals(expected, actual)
            assertEquals(RangeAttempt.EndReason.BodyStall, attempts[0].endReason)
            assertTrue(client.hostHealth.isBad(primary), "stalled primary host is marked stalled")
            assertEquals(RangeAttempt.EndReason.Truncated, attempts[1].endReason)
            // Mid-body truncation on rerouted sibling must NOT mark sibling refused!
            assertEquals(sibling, client.hostHealth.route(sibling, mayExplore = false))
        } finally {
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test fun hostHealthStalledUsesTrueElapsedDurationAndBestReplacementCandidateRespectsPoolAndColdShardRules() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        val slowDownload = "https://dl-z01a-0001.mypikpak.com/download/?fid=1&t=0"
        val fastDownload = "https://dl-z01a-0002.mypikpak.com/download/?fid=1&t=0"
        val fastOriginal = "https://dl-z01a-0002.mypikpak.com/download/?fid=1&t=0&category=original"
        val coldShard = "https://dl-a10b-0001.mypikpak.com/download/?fid=1&t=0"

        // Record distinct header latency (80 ms) vs true first-byte TTFB (420 ms) on fastDownload.
        repeat(HostHealth.MIN_SAMPLES) {
            health.answered(fastDownload, 80.milliseconds)
            health.recordFirstByteLatency(fastDownload, 420.milliseconds)
            health.delivered(HostHealth.hostOf(fastDownload), HostHealth.MIN_SAMPLE_BYTES, 2_500_000L, fastDownload)
        }
        val cand = assertNotNull(health.bestReplacementCandidate(slowDownload, remainingBytes = 256 * 1024L))
        assertEquals(fastDownload, cand.url)
        assertEquals(420L, cand.ttfbMillis, "must use true first-byte TTFB rather than header latency")
        assertEquals(2_500_000L, cand.bodyBytesPerSecond)

        // Never rewrite dl-a10b sharded cold storage.
        assertNull(health.bestReplacementCandidate(coldShard, remainingBytes = 256 * 1024L))

        // Service pool isolation: download pool samples do not fabricate original-pool replacement candidates.
        val slowOriginal = "https://dl-z01a-0001.mypikpak.com/download/?fid=1&t=0&category=original"
        health.observe(FileDetail(medias = listOf(MediaVariant(isOrigin = true, link = DownloadLink(fastOriginal)))))
        assertNull(health.bestReplacementCandidate(slowOriginal, remainingBytes = 256 * 1024L))

        // True elapsed body+stall duration accounting on proven-good host: 1st stall -> suspect, 2nd -> bad.
        repeat(HostHealth.MIN_SAMPLES) {
            health.delivered(HostHealth.hostOf(fastDownload), HostHealth.MIN_SAMPLE_BYTES, 3_000_000L, fastDownload)
        }
        health.stalled(fastDownload, deliveredBytes = 96 * 1024L, bodyElapsed = 4.seconds)
        assertFalse(health.isBad(fastDownload), "first mid-body stall on proven-good host enters suspect state")
        assertNull(health.bestReplacementCandidate(slowDownload, remainingBytes = 256 * 1024L),
            "suspect host must be excluded from optimistic ETA replacement")
        health.stalled(fastDownload, deliveredBytes = 96 * 1024L, bodyElapsed = 4.seconds)
        assertTrue(health.isBad(fastDownload), "corroborated second stall marks host bad")
    }
}
