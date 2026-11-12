package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.FileAdmission
import io.github.nihildigit.pikpak.internal.HostHealth
import io.github.nihildigit.pikpak.internal.RecoveryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPolicyTest {

    @Test
    fun purePolicyKeeps18KiBAt300KiBPerSecAgainstHighRttReplacement() {
        val candidate = RecoveryPolicy.CandidateEstimate(
            hostKey = "dl-z01a-0002.mypikpak.com",
            url = "https://dl-z01a-0002.mypikpak.com/download/?fid=1&sign=s",
            ttfbMillis = 1_100L,
            bodyBytesPerSecond = 5L * 1024L * 1024L,
        )
        val first = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 238L * 1024L,
            goalEndOffset = 256L * 1024L, // 18 KiB remaining
            goalRevision = 1L,
            consumedGoalRevision = -1L,
            currentUrl = "https://dl-z01a-0001.mypikpak.com/download/?fid=1&sign=s",
            currentShortWindowBytesPerSecond = 300L * 1024L, // T_keep ~= 60 ms
            hasSinkBackpressure = false,
            candidate = candidate,
            previousConsecutiveQualifying = 1,
        )
        assertFalse(first.shouldSwitch)
        assertEquals(0, first.nextConsecutiveQualifying)
        assertEquals(false, first.comparison?.qualifies)
    }

    @Test
    fun purePolicyReplaces200KiBAt25KiBPerSecOnSecondConsecutiveObservation() {
        val currentUrl = "https://dl-z01a-0001.mypikpak.com/download/?fid=1&sign=s"
        val candidate = RecoveryPolicy.CandidateEstimate(
            hostKey = "dl-z01a-0002.mypikpak.com",
            url = "https://dl-z01a-0002.mypikpak.com/download/?fid=1&sign=s",
            ttfbMillis = 1_100L,
            bodyBytesPerSecond = 400L * 1024L,
        )
        val obs1 = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 56L * 1024L,
            goalEndOffset = 256L * 1024L, // 200 KiB remaining -> T_keep = 8000 ms, T_replace = 1650 ms
            goalRevision = 7L,
            consumedGoalRevision = -1L,
            currentUrl = currentUrl,
            currentShortWindowBytesPerSecond = 25L * 1024L,
            hasSinkBackpressure = false,
            candidate = candidate,
            previousConsecutiveQualifying = 0,
        )
        assertFalse(obs1.shouldSwitch, "first qualifying window only arms hysteresis")
        assertEquals(1, obs1.nextConsecutiveQualifying)
        assertEquals(true, obs1.comparison?.qualifies)

        val obs2 = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 64L * 1024L,
            goalEndOffset = 264L * 1024L, // 200 KiB remaining
            goalRevision = 7L,
            consumedGoalRevision = -1L,
            currentUrl = currentUrl,
            currentShortWindowBytesPerSecond = 25L * 1024L,
            hasSinkBackpressure = false,
            candidate = candidate,
            previousConsecutiveQualifying = obs1.nextConsecutiveQualifying,
        )
        assertTrue(obs2.shouldSwitch, "second consecutive qualifying window triggers switchover")
        assertEquals(2, obs2.nextConsecutiveQualifying)
        assertEquals(candidate, obs2.candidate)
    }

    @Test
    fun hysteresisResetsOnRecoveredWindowBackpressureAndConsumedGoalRevision() {
        val currentUrl = "https://dl-z01a-0001.mypikpak.com/download/?fid=1&sign=s"
        val candidate = RecoveryPolicy.CandidateEstimate(
            hostKey = "dl-z01a-0002.mypikpak.com",
            url = "https://dl-z01a-0002.mypikpak.com/download/?fid=1&sign=s",
            ttfbMillis = 300L,
            bodyBytesPerSecond = 2L * 1024L * 1024L,
        )
        val slow1 = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 0L,
            goalEndOffset = 200L * 1024L,
            goalRevision = 3L,
            consumedGoalRevision = -1L,
            currentUrl = currentUrl,
            currentShortWindowBytesPerSecond = 25L * 1024L,
            hasSinkBackpressure = false,
            candidate = candidate,
            previousConsecutiveQualifying = 0,
        )
        assertEquals(1, slow1.nextConsecutiveQualifying)

        val recovered = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 64L * 1024L,
            goalEndOffset = 200L * 1024L,
            goalRevision = 3L,
            consumedGoalRevision = -1L,
            currentUrl = currentUrl,
            currentShortWindowBytesPerSecond = 512L * 1024L,
            hasSinkBackpressure = false,
            candidate = candidate,
            previousConsecutiveQualifying = slow1.nextConsecutiveQualifying,
        )
        assertFalse(recovered.shouldSwitch)
        assertEquals(0, recovered.nextConsecutiveQualifying)

        val backpressured = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 0L,
            goalEndOffset = 200L * 1024L,
            goalRevision = 3L,
            consumedGoalRevision = -1L,
            currentUrl = currentUrl,
            currentShortWindowBytesPerSecond = 25L * 1024L,
            hasSinkBackpressure = true,
            candidate = candidate,
            previousConsecutiveQualifying = 1,
        )
        assertFalse(backpressured.shouldSwitch)
        assertEquals(0, backpressured.nextConsecutiveQualifying)

        val alreadyConsumed = RecoveryPolicy.evaluateSameOffsetSwitchover(
            forwardedOffset = 0L,
            goalEndOffset = 200L * 1024L,
            goalRevision = 3L,
            consumedGoalRevision = 3L,
            currentUrl = currentUrl,
            currentShortWindowBytesPerSecond = 25L * 1024L,
            hasSinkBackpressure = false,
            candidate = candidate,
            previousConsecutiveQualifying = 1,
        )
        assertFalse(alreadyConsumed.shouldSwitch)
        assertEquals(0, alreadyConsumed.nextConsecutiveQualifying)
    }

    @Test
    fun inFlightContinuationVsRepositionEvaluatesEtaCleanly() {
        val keepRunning = RecoveryPolicy.evaluateInFlightContinuationOrReposition(
            interveningBytes = 128L * 1024L,
            urgentRequiredBytes = 64L * 1024L,
            inFlightBytesPerSecond = 512L * 1024L,
            repositionTtfbMillis = 1_100L,
            repositionBytesPerSecond = 512L * 1024L,
        )
        assertFalse(keepRunning.shouldReposition, "375 ms continuation beats 1275 ms cancel+reposition")

        val reposition = RecoveryPolicy.evaluateInFlightContinuationOrReposition(
            interveningBytes = 768L * 1024L,
            urgentRequiredBytes = 256L * 1024L,
            inFlightBytesPerSecond = 25L * 1024L,
            repositionTtfbMillis = 1_100L,
            repositionBytesPerSecond = 512L * 1024L,
        )
        assertTrue(reposition.shouldReposition, "40 s crawl through 768 KiB gap loses decisively to reposition")
    }

    @Test
    fun rangeReaderBoundaryStopHaltsAtExactSafePointWithZeroPenalty() = runTest {
        val url = "https://dl-z01a-0001.mypikpak.com/download/?fid=boundary&sign=b1"
        val expected = ByteArray(64 * 1024) { ((it * 17 + 3) and 0xFF).toByte() }
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        val body = ByteChannel(autoFlush = true)
                        this@runTest.backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                            try {
                                for (chunkStart in 0 until expected.size step (16 * 1024)) {
                                    body.writeFully(expected, chunkStart, chunkStart + 16 * 1024)
                                    delay(10)
                                }
                                body.close()
                            } catch (_: Throwable) {
                            }
                        }
                        respond(
                            content = body,
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentRange to listOf("bytes 0-${expected.size - 1}/${expected.size}"),
                                HttpHeaders.ContentLength to listOf(expected.size.toString()),
                            ),
                        )
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
            timeSource = testScheduler.timeSource,
        )
        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(
            client = client,
            urlProvider = { url },
            onAttempt = { attempts += it },
            timeSource = testScheduler.timeSource,
        )
        val claimControl = FetchClaimControl(MutableStateFlow(PikPakStreamReader.STREAMING_PRIORITY)).apply {
            allowedProductionEndOffset = 32L * 1024L
        }
        try {
            val received = withContext(claimControl) {
                reader.read(0L, expected.size.toLong(), PikPakStreamReader.STREAMING_PRIORITY) { ch ->
                    ch.toByteArray()
                }
            }
            assertContentEquals(expected.copyOfRange(0, 32 * 1024), received)
            assertEquals(1, attempts.size)
            assertEquals(RangeAttempt.EndReason.BoundaryStop, attempts.single().endReason)
            assertFalse(client.hostHealth.isBad(url), "BoundaryStop must never penalize the host")
        } finally {
            reader.close()
            client.close()
            http.close()
        }
    }

    @Test
    fun rangeReaderEtaSwitchoverTransfersActivePermitInOneShotAndPreservesExactByteContinuity() = runTest {
        val slowUrl = "https://dl-z01a-0001.mypikpak.com/download/?fid=eta&sign=s1"
        val fastUrl = "https://dl-z01a-0002.mypikpak.com/download/?fid=eta&sign=s1"
        val otherUrl = "https://dl-z01a-0009.mypikpak.com/download/?fid=other&sign=other"
        val totalBytes = 224 * 1024
        val expected = ByteArray(totalBytes) { ((it * 31 + 7) and 0xFF).toByte() }
        val requestedRanges = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()
        val slowFirstChunkSent = CompletableDeferred<Unit>()

        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val rangeHeader = request.headers[HttpHeaders.Range]!!
                        requestedRanges += request.url.host to rangeHeader
                        events += "admitted:${request.url.host}"
                        val parts = rangeHeader.removePrefix("bytes=").split('-')
                        val start = parts[0].toInt()
                        val end = parts[1].toInt()
                        val length = end - start + 1
                        if (request.url.host == "dl-z01a-0001.mypikpak.com") {
                            val body = ByteChannel(autoFlush = true)
                            this@runTest.backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                                try {
                                    var cursor = start
                                    var chunkIndex = 0
                                    while (cursor <= end) {
                                        if (chunkIndex > 0) delay(320) // ~25 KiB/s (8 KiB per 320 ms)
                                        val count = minOf(8 * 1024, end - cursor + 1)
                                        body.writeFully(expected, cursor, cursor + count)
                                        if (chunkIndex == 0) slowFirstChunkSent.complete(Unit)
                                        cursor += count
                                        chunkIndex++
                                    }
                                    body.close()
                                } catch (_: Throwable) {
                                }
                            }
                            respond(
                                content = body,
                                status = HttpStatusCode.PartialContent,
                                headers = headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$totalBytes"),
                                    HttpHeaders.ContentLength to listOf(length.toString()),
                                ),
                            )
                        } else {
                            respond(
                                content = ByteReadChannel(expected.copyOfRange(start, end + 1)),
                                status = HttpStatusCode.PartialContent,
                                headers = headersOf(
                                    HttpHeaders.ContentRange to listOf("bytes $start-$end/$totalBytes"),
                                    HttpHeaders.ContentLength to listOf(length.toString()),
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
            accountConnectionBudget = 1,
            steerEdgeHosts = true,
            timeSource = testScheduler.timeSource,
        )
        client.hostHealth.observe(FileDetail(webContentLink = fastUrl))
        repeat(HostHealth.MIN_SAMPLES) {
            client.hostHealth.answered(fastUrl, 150.milliseconds)
            client.hostHealth.recordFirstByteLatency(fastUrl, 200.milliseconds)
            client.hostHealth.delivered(HostHealth.hostOf(fastUrl), 512L * 1024L, 2L * 1024L * 1024L, fastUrl)
        }

        val attempts = mutableListOf<RangeAttempt>()
        val reader = RangeReader(
            client = client,
            urlProvider = { slowUrl },
            onAttempt = { attempts += it },
            timeSource = testScheduler.timeSource,
        )
        val claimControl = FetchClaimControl(MutableStateFlow(PikPakStreamReader.STREAMING_PRIORITY)).apply {
            recoveryGoalEndOffset = totalBytes.toLong()
            recoveryGoalRevision = 1L
        }
        try {
            val readJob = async(claimControl) {
                reader.read(0L, totalBytes.toLong(), PikPakStreamReader.STREAMING_PRIORITY) { ch ->
                    ch.toByteArray()
                }
            }
            slowFirstChunkSent.await()
            val competing = async(FileAdmission(Any(), 1)) {
                client.signedUrlGates.withSlot(otherUrl, PikPakStreamReader.STREAMING_PRIORITY) {
                    events += "competingAdmitted"
                }
            }
            runCurrent()
            assertFalse(competing.isCompleted, "competing same-tier waiter must wait while active slot is held")

            val actual = readJob.await()
            competing.await()

            assertContentEquals(expected, actual)
            assertEquals(
                listOf(
                    "admitted:dl-z01a-0001.mypikpak.com",
                    "admitted:dl-z01a-0002.mypikpak.com",
                    "competingAdmitted",
                ),
                events,
                "1-shot permit handoff must keep the active slot across replacement before admitting queued peer",
            )
            assertEquals(1L, claimControl.consumedRecoveryRevision)
            assertEquals(2, attempts.size)
            assertEquals(RangeAttempt.EndReason.OptimizedReplacement, attempts[0].endReason)
            assertEquals("dl-z01a-0001.mypikpak.com", attempts[0].host)
            assertEquals(24L * 1024L, attempts[0].delivered)
            assertEquals(RangeAttempt.Outcome.Complete, attempts[1].outcome)
            assertEquals("dl-z01a-0002.mypikpak.com", attempts[1].host)
            assertEquals(24L * 1024L, attempts[1].start)
            assertEquals((totalBytes - 24 * 1024).toLong(), attempts[1].delivered)
            assertEquals(
                listOf(
                    "dl-z01a-0001.mypikpak.com" to "bytes=0-${totalBytes - 1}",
                    "dl-z01a-0002.mypikpak.com" to "bytes=${24 * 1024}-${totalBytes - 1}",
                ),
                requestedRanges,
            )
            assertFalse(client.hostHealth.isBad(slowUrl), "OptimizedReplacement must not mark the replaced host bad")
            assertEquals(0, client.signedUrlGates.inUse)
        } finally {
            reader.close()
            client.close()
            http.close()
        }
    }
}
