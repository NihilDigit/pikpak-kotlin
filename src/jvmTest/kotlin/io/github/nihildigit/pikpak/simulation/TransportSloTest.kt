package io.github.nihildigit.pikpak.simulation

import io.github.nihildigit.pikpak.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

/** Exercises the production controller, not an oracle or a duplicate scheduling policy. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportSimulationTest {
    @Test fun lowRttGigabit() = runTest(timeout = 5.minutes) {
        download(NetworkProfile(), minimumUtilization = 0.92)
    }
    @Test fun highRttGigabit() = runTest(timeout = 5.minutes) {
        download(NetworkProfile(rttMs = 200, ttfbMs = 250), minimumUtilization = 0.90)
    }
    @Test fun highTtfbGigabit() = runTest(timeout = 5.minutes) {
        download(NetworkProfile(rttMs = 200, ttfbMs = 1350), minimumUtilization = 0.90)
    }
    @Test fun resumableResetAndLossPreserveEveryByte() = runTest(timeout = 5.minutes) {
        download(NetworkProfile(failRequest = 2, lossEveryPackets = 5000), size = 64 * MIB)
    }
    @Test fun degradedEdgeFindsSibling() = runTest(timeout = 5.minutes) {
        val result = download(NetworkProfile(ttfbMs = 1350, degradedHost = HOST), size = 64 * MIB, steering = true)
        assertTrue(SIBLING in result.hosts, "slow but responding edge never explored the observed same-family sibling")
        assertTrue(result.hosts.all { it == HOST || it == SIBLING }, "steering crossed the observed host family: ${result.hosts}")
        assertTrue(result.elapsedMs < 60_000, "400 KiB/s origin was not rescued: $result")
    }

    @Test fun degradedWithoutSiblingAmortizesFirstByteCost() = runTest(timeout = 5.minutes) {
        val profile = NetworkProfile(ttfbMs = 1350, degradedHost = HOST)
        val result = download(profile, size = 256 * MIB)
        // An optimistic old-policy upper bound: eight warm sockets with no loss,
        // no slow start, no disk overhead, but each 1 MiB request pays its TTFB.
        val oldRangeBytes = MIB.toDouble()
        val oldCeiling = 8 * oldRangeBytes / (profile.ttfbMs / 1000.0 + oldRangeBytes / profile.degradedRate)
        assertEquals(setOf(HOST), result.hosts)
        assertTrue(result.steadyBps >= oldCeiling * 1.20,
            "long-range goodput ${result.steadyBps} did not beat old 1 MiB ideal ceiling $oldCeiling")
        println("SIM degradedNoSibling old1MiBIdealBps=$oldCeiling " +
            "measuredSteadyBps=${result.steadyBps} gain=${result.steadyBps / oldCeiling}")
    }

    private data class Result(val elapsedMs: Long, val hosts: Set<String>, val steadyBps: Double)

    private suspend fun TestScope.download(
        profile: NetworkProfile,
        minimumUtilization: Double? = null,
        size: Long = 2 * 1024 * MIB,
        steering: Boolean = false,
    ): Result {
        val network = VirtualNetwork(this, size, profile)
        val http = HttpClient(network.engine)
        val client = PikPakClient("simulation", { "unused" }, sessionStore = InMemorySessionStore(),
            retryPolicy = RetryPolicy.None, httpClient = http, steerEdgeHosts = steering,
            transportMemoryBudgetBytes = 16 * MIB, timeSource = testScheduler.timeSource)
        if (steering) client.hostHealth.observe(FileDetail(
            webContentLink = "https://$HOST/file?signature=same",
            medias = listOf(
                MediaVariant(link = DownloadLink(url = "https://$SIBLING/file?signature=variant")),
                // An observed host in a different family must not become a routing candidate.
                MediaVariant(link = DownloadLink(url = "https://dl-z01a-0003.mypikpak.com/file?signature=other")),
            ),
        ))
        val attempts = mutableListOf<RangeAttempt>()
        val source = RangeReader(client, { "https://$HOST/file?signature=same" }, onAttempt = { attempts += it })
        val disk = VirtualDisk()
        val cache = BlockCache(source, size, 8, backgroundScope.coroutineContext, client.foregroundStreams, disk,
            blockSize = 256 * 1024, readAheadBytes = 4 * MIB, memoryCapBytes = 16 * MIB,
            scheduler = client.scheduler, memoryBudget = client.transportMemoryBudget, timeSource = testScheduler.timeSource)
        val started = testScheduler.currentTime
        var at20: DurableSample? = null
        var at80: DurableSample? = null
        val sample = backgroundScope.launch {
            while (isActive) {
                if (disk.uniqueBytes >= size / 5 && at20 == null) at20 = DurableSample(testScheduler.currentTime, disk.uniqueBytes, network.wireBytes)
                if (disk.uniqueBytes >= size * 4 / 5 && at80 == null) at80 = DurableSample(testScheduler.currentTime, disk.uniqueBytes, network.wireBytes)
                assertTrue(client.transportMemoryUsedBytes <= 16 * MIB)
                delay(1)
            }
        }
        var failure: Throwable? = null
        try {
            withTimeout(15 * 60 * 1000L) {
                cache.warm(listOf(0L until size), StreamRole.BACKGROUND, null, durable = true).await()
            }
            val elapsed = testScheduler.currentTime - started
            assertEquals(size, disk.uniqueBytes)
            assertTrue(disk.missing("", listOf(0L until size)).isEmpty())
            assertEquals(0, disk.duplicateWrites, "a durable block was committed more than once")
            assertTrue(client.transportMemoryPeakBytes <= 16 * MIB)
            assertTrue(disk.peakWriteBytes <= 16 * MIB)
            network.assertConservation()
            assertEquals(0, network.rejected, "production issued a ninth signed request")
            if (profile.failRequest != null) {
                val reset = network.resets.single()
                val request = network.requests.single { it.id == reset.requestId }
                assertEquals(profile.failAfter, reset.nextOffset - request.start)
                val failed = attempts.single { it.start == request.start && it.outcome == RangeAttempt.Outcome.Failed }
                assertEquals(profile.failAfter, failed.delivered, "reset must follow delivery of the exact cutoff")
                assertTrue(network.requests.any {
                    it.atMs >= reset.atMs && it.start == failed.start + failed.delivered && it.end == request.end
                }, "no exact delivered-offset resume after recorded reset")
                println("SIM reset=$reset failed=$failed resumeOffset=${failed.start + failed.delivered}")
            }
            if (profile.lossEveryPackets > 0) assertTrue(network.losses > 0)
            val first = assertNotNull(at20)
            val last = assertNotNull(at80)
            val windowMs = last.atMs - first.atMs
            val windowBytes = last.durableBytes - first.durableBytes
            assertTrue(windowMs > 0 && windowBytes > 0, "empty steady measurement window")
            val steady = windowBytes * 1000.0 / windowMs
            // Bytes already on the wire at the first sample can commit inside this window.
            // Measure that backlog rather than assuming only one block is between wire and disk.
            // With loss this also includes retransmitted bytes, so the per-tick wire assertion
            // remains the strict physical capacity check.
            val initialBacklog = first.wireBytes - first.durableBytes
            val capacityBytes = profile.capacity / 1000 * windowMs
            assertTrue(windowBytes <= capacityBytes + initialBacklog,
                "unphysical durable window: $windowBytes bytes in ${windowMs}ms with $initialBacklog bytes already on wire")
            println("SIM profile=$profile size=$size elapsedMs=$elapsed endToEndBps=${size * 1000.0 / elapsed} " +
                "steady20to80Bps=$steady utilization=${steady / profile.capacity} initialBacklog=$initialBacklog wire=${network.wireBytes} " +
                "requests=${network.requests.size} reused=${network.requests.count { it.reused }} " +
                "peakMemory=${client.transportMemoryPeakBytes} peakDisk=${disk.peakWriteBytes} losses=${network.losses}")
            minimumUtilization?.let { assertTrue(steady / profile.capacity >= it,
                "steady unique durable goodput ${steady / profile.capacity} below $it; end-to-end ${size * 1000.0 / elapsed}") }
            return Result(elapsed, network.requests.map { it.host }.toSet(), steady)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            sample.cancel()
            try {
                cleanup(failure, cache, source, client, http, network)
            } finally {
                if (profile.degradedHost != null || failure != null) network.printRequestDiagnostics()
            }
        }
    }

    @Test fun seekRescuesAnOwnedStragglerWithoutCorruption() = runTest(timeout = 5.minutes) {
        val early = rescueStraggler(250)
        val late = rescueStraggler(1000)
        assertTrue(late - early >= 700, "rescue timing did not follow playback feedback: $early vs $late")
    }

    @Test fun urgentSeekRescuesImmediatelyDespitePositivePlaybackBuffer() = runTest(timeout = 5.minutes) {
        rescueStraggler(1000, urgent = true)
    }

    private suspend fun TestScope.rescueStraggler(bufferMs: Long, urgent: Boolean = false): Long {
        val network = VirtualNetwork(this, 128 * MIB, NetworkProfile(stallRequest = 1))
        val http = HttpClient(network.engine)
        val client = PikPakClient("simulation", { "unused" }, sessionStore = InMemorySessionStore(),
            retryPolicy = RetryPolicy.None, httpClient = http, steerEdgeHosts = false,
            transportMemoryBudgetBytes = 16 * MIB, timeSource = testScheduler.timeSource)
        val attempts = mutableListOf<RangeAttempt>()
        val source = RangeReader(client, { "https://$HOST/file?signature=same" }, onAttempt = { attempts += it })
        val cache = BlockCache(source, 128 * MIB, 8, backgroundScope.coroutineContext, client.foregroundStreams,
            VirtualDisk(), blockSize = 256 * 1024, readAheadBytes = 4 * MIB, memoryCapBytes = 16 * MIB,
            scheduler = client.scheduler, memoryBudget = client.transportMemoryBudget, timeSource = testScheduler.timeSource)
        var failure: Throwable? = null
        try {
            cache.warm(listOf(0L until 128 * MIB), StreamRole.BACKGROUND, null, durable = true)
            withTimeout(2000) { while (network.requests.isEmpty()) delay(1) }
            val target = network.requests.first().start
            advanceTimeBy(200); runCurrent()
            val cursor = cache.openCursor(StreamRole.FOREGROUND)
            cache.setUrgent(cursor, urgent)
            cache.seek(cursor, target)
            cache.reportPlaybackBuffer(cursor, bufferMs.milliseconds, MIB)
            val started = testScheduler.currentTime
            val bytes = ByteArray(64 * 1024)
            var count = 0
            withTimeout(5000) {
                while (count < bytes.size) {
                    val n = cache.read(cursor, bytes, count, bytes.size - count)
                    assertTrue(n > 0, "premature EOF during seek rescue")
                    count += n
                }
            }
            verifyContent(target, bytes)
            assertTrue(testScheduler.currentTime - started < 5000, "seek waited on original 30 second straggler")
            assertTrue(network.requests.count { it.start <= target && it.end >= target } >= 2, "head ownership was never rescued")
            network.assertConservation()
            assertEquals(0, network.rejected)
            val replacement = network.requests.first { it.id != 1 && it.start <= target && it.end >= target }
            val rescueDelay = replacement.atMs - started
            val expectedDelay = if (urgent) 0L else bufferMs
            assertTrue(rescueDelay in expectedDelay..(expectedDelay + 100),
                "replacement ignored deadline: $rescueDelay for buffer=$bufferMs urgent=$urgent")
            val cancelled = attempts.first { it.start == target }
            assertEquals(RangeAttempt.Outcome.Cancelled, cancelled.outcome)
            assertTrue(cancelled.rightCensored)
            assertTrue(attempts.none { it.outcome == RangeAttempt.Outcome.Failed }, "deadline rescue was classified as network failure")
            println("SIM playbackBufferMs=$bufferMs urgent=$urgent rescueDelayMs=$rescueDelay " +
                "ready64KiBMs=${testScheduler.currentTime - started} outcome=${cancelled.outcome}")
            cache.closeCursor(cursor)
            return rescueDelay
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            cleanup(failure, cache, source, client, http, network)
        }
    }

    @Test fun deterministicSeekStormPreservesOffsetContent() = runTest(timeout = 5.minutes) {
        val size = 128 * MIB
        val network = VirtualNetwork(this, size, NetworkProfile(rttMs = 200, ttfbMs = 250))
        val http = HttpClient(network.engine)
        val client = PikPakClient("simulation", { "unused" }, sessionStore = InMemorySessionStore(),
            retryPolicy = RetryPolicy.None, httpClient = http, steerEdgeHosts = false,
            transportMemoryBudgetBytes = 16 * MIB, timeSource = testScheduler.timeSource)
        val attempts = mutableListOf<RangeAttempt>()
        val source = RangeReader(client, { "https://$HOST/file?signature=same" }, onAttempt = { attempts += it })
        val cache = BlockCache(source, size, 8, backgroundScope.coroutineContext, client.foregroundStreams,
            VirtualDisk(), blockSize = 256 * 1024, readAheadBytes = 4 * MIB, memoryCapBytes = 16 * MIB,
            scheduler = client.scheduler, memoryBudget = client.transportMemoryBudget, timeSource = testScheduler.timeSource)
        val cursor = cache.openCursor(StreamRole.FOREGROUND)
        val started = testScheduler.currentTime
        var failure: Throwable? = null
        try {
            val offsets = listOf(17L, 63 * MIB + 31, 2 * MIB - 19, 100 * MIB + 7, 7 * MIB + 65521, 17L)
            for (target in offsets) {
                // Cancel a read while it is in flight, then seek to a distinct unaligned position.
                cache.seek(cursor, (target + 16 * MIB) % (size - MIB))
                val abandoned = launch { cache.read(cursor, ByteArray(64 * 1024), 0, 64 * 1024) }
                advanceTimeBy(25); runCurrent()
                abandoned.cancelAndJoin()
                cache.seek(cursor, target)
                cache.reportPlaybackBuffer(cursor, 100.milliseconds, MIB)
                val bytes = ByteArray(96 * 1024)
                withTimeout(5000) {
                    var count = 0
                    while (count < bytes.size) {
                        val n = cache.read(cursor, bytes, count, bytes.size - count)
                        assertTrue(n > 0)
                        count += n
                    }
                }
                verifyContent(target, bytes)
                assertTrue(client.transportMemoryPeakBytes <= 16 * MIB)
            }
            assertTrue(attempts.any { it.outcome == RangeAttempt.Outcome.Cancelled })
            assertTrue(attempts.none { it.outcome == RangeAttempt.Outcome.Failed })
            network.assertConservation()
            assertEquals(0, network.rejected)
            println("SIM seekStorm seeks=${offsets.size} elapsedMs=${testScheduler.currentTime - started} " +
                "requests=${network.requests.size} peakMemory=${client.transportMemoryPeakBytes} rejected=${network.rejected}")
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            cleanup(failure, cache, source, client, http, network)
        }
    }

    @Test fun simulatorRejectsNinthConcurrentRequest() = runTest {
        val network = VirtualNetwork(this, MIB, NetworkProfile(stallRequest = 1))
        val http = HttpClient(network.engine)
        try {
            // Keep the first eight bodies unread, to validate the simulator's server limit.
            val opened = kotlinx.coroutines.channels.Channel<io.ktor.utils.io.ByteReadChannel>(8)
            val release = CompletableDeferred<Unit>()
            val responses = (1..8).map { launch {
                http.prepareGet("https://$HOST/file") { header("Range", "bytes=0-1048575") }.execute { response ->
                    val body = response.bodyAsChannel()
                    opened.send(body)
                    release.await()
                    body.cancel(null)
                }
            } }
            val bodies = List(8) { opened.receive() }
            var replacement: Job? = null
            try {
                val ninth = http.get("https://$HOST/file") { header("Range", "bytes=0-1048575") }
                assertEquals(HttpStatusCode.ServiceUnavailable, ninth.status)
                assertEquals(1, network.rejected)
                // A closed body must stop counting before the next pump tick, while
                // the other seven unread, open bodies must retain their slots.
                val cancelledAt = testScheduler.currentTime
                bodies.first().cancel(null)
                // Cancelling a consumer channel alone need not close its HTTP call.
                // Admission is reclaimed only after the owning execute scope returns.
                responses.first().cancelAndJoin()
                replacement = launch {
                    http.prepareGet("https://$HOST/file") { header("Range", "bytes=0-1048575") }.execute { response ->
                        assertEquals(HttpStatusCode.PartialContent, response.status)
                        release.await()
                        response.bodyAsChannel().cancel(null)
                    }
                }
                runCurrent()
                assertEquals(9, network.requests.size, "closed flow blocked same-timestamp replacement")
                assertEquals(cancelledAt, network.requests.last().atMs)
                assertEquals(8, network.active, "still-open flows lost ownership")
                assertEquals(1, network.rejected)
                network.assertConservation()
            } finally { release.complete(Unit); responses.joinAll(); replacement?.join() }
        } finally { http.close(); network.close() }
    }

    private data class DurableSample(val atMs: Long, val durableBytes: Long, val wireBytes: Long)

    private suspend fun cleanup(
        failure: Throwable?,
        cache: BlockCache,
        source: RangeReader,
        client: PikPakClient,
        http: HttpClient,
        network: VirtualNetwork,
    ) {
        // Keep the test assertion primary even if shutdown also finds a leak.
        withContext(NonCancellable) {
            try {
                cache.close(); source.close(); client.close()
                network.awaitIdle()
                assertEquals(0L, client.transportMemoryUsedBytes, "memory ownership leaked after close")
                assertEquals(0, network.active, "transport leaked after close")
            } catch (cleanupFailure: Throwable) {
                if (failure == null) throw cleanupFailure
                failure.addSuppressed(cleanupFailure)
            } finally {
                http.close(); network.close()
            }
        }
    }

    private companion object {
        const val HOST = "dl-a10b-0014.mypikpak.com"
        const val SIBLING = "dl-a10b-0003.mypikpak.com"
    }
}
