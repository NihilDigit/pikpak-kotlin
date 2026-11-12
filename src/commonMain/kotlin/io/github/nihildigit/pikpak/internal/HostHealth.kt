package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Account-local CDN routing evidence. Candidates are learned from issued links and
 * partitioned by family, root domain and signed service class (download/original/transcoded).
 * A compatible signature does not imply equivalent service: cross-pool rewriting can
 * redirect a request or apply a much slower transport policy.
 *
 * Rates, failures and route leases are isolated by pool even when hostnames coincide.
 * Unknown playback demand never becomes "satisfied" merely by exceeding a fixed low
 * throughput floor. Exploration remains bounded by sample count, cooldown and hunger.
 */
internal class HostHealth(
    private val memory: Duration = DEFAULT_MEMORY,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** False keeps every attempt on its link's own host; only silence is still remembered. */
    @Volatile internal var steering: Boolean = true,
    seeds: Collection<String> = emptyList(),
) {
    private data class HealthState(
        val epoch: Long = 0,
        val rates: Map<String, List<RateSample>> = emptyMap(),
        val hostLatencies: Map<String, List<LatencySample>> = emptyMap(),
        val hostFirstByteLatencies: Map<String, List<LatencySample>> = emptyMap(),
        val hostColdFirstByteLatencies: Map<String, List<LatencySample>> = emptyMap(),
        val latencies: List<LatencySample> = emptyList(),
        val cleanLatencies: List<LatencySample> = emptyList(),
        val coldLatencies: List<LatencySample> = emptyList(),
        val silences: Map<String, TimeMark> = emptyMap(),
        val suspects: Map<String, TimeMark> = emptyMap(),
        val stalls: Map<String, TimeMark> = emptyMap(),
        val refusals: Map<String, TimeMark> = emptyMap(),
    )

    // Route evidence and its epoch are published together. A reader never sees a partial reset.
    private val health = MutableStateFlow(HealthState())
    private val answers = MutableStateFlow<Map<String, TimeMark>>(emptyMap())

    /** Host labels without the root domain, in the order links named them. Tried before [seedLabels]. */
    private val learned = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val seedLabels = seeds.toList()
    private val activeDrains = MutableStateFlow(0)
    private val idleSockets = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val everPooled = MutableStateFlow(false)
    private val lastClusterUrl = MutableStateFlow<Map<String, String>>(emptyMap())

    private class RateSample(val bytesPerSecond: Long, val at: TimeMark)

    /**
     * [url]'s host sent response headers [after] the request went out. Null when the wait is not
     * a clean measure, e.g. it spanned a retry inside the HTTP layer. Only a proven
     * [connectionReused] sample trains warm route-epoch detection; unknown is not warm.
     */
    fun answered(url: String, after: Duration? = null, connectionReused: Boolean? = null) {
        val host = keyOf(url) ?: return
        val bareHost = hostOf(url)
        val now = timeSource.markNow()
        answers.update { cur ->
            if (bareHost != null && bareHost != host) cur + (host to now) + (bareHost to now)
            else cur + (host to now)
        }
        val sample = after?.let { LatencySample(it, now) }
        health.update { previous ->
            // Only proven pooled connections compare route RTT. Cold handshakes and engines
            // without connection evidence still train deadlines, never the route epoch.
            val current = when {
                sample == null -> previous
                connectionReused == true -> previous.recordCleanLatency(sample)
                connectionReused == false -> previous.copy(
                    latencies = (previous.latencies + sample).takeLast(MAX_LATENCY_SAMPLES),
                    coldLatencies = (previous.coldLatencies.filter { it.at.elapsedNow() < LATENCY_MEMORY } + sample)
                        .takeLast(MAX_LATENCY_SAMPLES),
                )
                else -> previous.copy(latencies = (previous.latencies + sample).takeLast(MAX_LATENCY_SAMPLES))
            }
            val clearedSilences = if (bareHost != null) current.silences - host - bareHost else current.silences - host
            current.copy(
                silences = clearedSilences,
                hostLatencies = if (sample == null) current.hostLatencies else
                    current.hostLatencies + (host to (current.hostLatencies[host].orEmpty() + sample).takeLast(MAX_SAMPLES)),
            )
        }
        Edge.parse(url)?.let { edge ->
            lastClusterUrl.update { it + (edge.cluster to url) }
        }
    }

    fun onRequestStarted(url: String): Boolean {
        val host = keyOf(url) ?: return false
        var hadWarm = false
        idleSockets.update { cur ->
            val count = cur[host] ?: 0
            hadWarm = count > 0 && answers.value[host]?.let { it.elapsedNow() < WARM_TTL } == true
            cur + (host to (count - 1).coerceAtLeast(0))
        }
        return hadWarm
    }

    fun onSocketReturned(url: String) {
        val host = keyOf(url) ?: return
        everPooled.value = true
        answers.update { it + (host to timeSource.markNow()) }
        idleSockets.update { cur -> cur + (host to ((cur[host] ?: 0) + 1)) }
    }

    /**
     * [url]'s host sent nothing within [waited], the deadline the request was given.
     *
     * The wait counts as a latency sample, a lower bound of the real one. Without it the samples
     * would only ever be the requests that beat the deadline, and on a line where most do not,
     * the median would stay below the deadline and never lift it.
     */
    fun markSilent(url: String, waited: Duration) {
        recordLatency(waited)
        val host = keyOf(url) ?: return
        val bareHost = hostOf(url)
        if (answers.value[host]?.let { it.elapsedNow() < waited } == true) return
        if (bareHost != null && answers.value[bareHost]?.let { it.elapsedNow() < waited } == true) return
        val othersAnswering = answers.value.any { (other, at) ->
            other != host && other != bareHost &&
                (bareHost == null || !other.startsWith("$bareHost|")) &&
                at.elapsedNow() < CORROBORATION
        }
        if (!othersAnswering) return
        val now = timeSource.markNow()
        health.update { current ->
            val active = current.silences.filterValues { it.elapsedNow() < memory }
            val updated = if (bareHost != null && bareHost != host) {
                active + (host to now) + (bareHost to now)
            } else {
                active + (host to now)
            }
            current.copy(silences = updated)
        }
    }

    /**
     * How long a request may wait for its response headers before its host is given up on.
     *
     * [LATENCY_RATIO] times the 90th percentile of the waits on this account, never below
     * [MIN_DEADLINE] nor above [MAX_DEADLINE]. A fixed deadline was tuned on a line where a
     * healthy host answers in 300–500 ms. On a direct line from mainland China, a 2026-10-02 log
     * had the fixed 2 s kill 252 of 587 attempts, 175 of them on hosts that served another
     * request within 5 s, and mark those hosts silent besides. Measured 2026-10-05 on that line:
     * a pooled connection answers in 0.4 s, one round trip, while a new one takes 1.4–5.8 s to
     * its first byte, nearly all of it the TLS handshake.
     *
     * Not the median, because those are two populations: while pooled connections are most of
     * the samples the median is theirs, and the deadline it gives kills every new connection,
     * which is exactly what a host change opens. A tail that more than a tenth of the requests
     * cross is lifted by their cut-off samples, so the deadline doubles until it covers them.
     */
    fun firstResponseDeadline(url: String? = null, claimedWarmSocket: Boolean? = null): Duration {
        val recent = health.value.latencies.filter { it.at.elapsedNow() < LATENCY_MEMORY }
        val base = if (recent.size < MIN_LATENCY_SAMPLES) {
            INITIAL_DEADLINE
        } else {
            val tail = recent.map { it.wait }.sorted()[(recent.size * 9) / 10]
            (tail * LATENCY_RATIO).coerceIn(MIN_DEADLINE, MAX_DEADLINE)
        }
        // Warm pooled sockets answer in one RTT and pull P90 down to MIN_DEADLINE, while opening
        // fresh TLS connections to a cold host through a proxy can take 2–3x longer. Give cold
        // connections handshake headroom so an 8-way cold open does not cascade across the cluster.
        val warm = claimedWarmSocket ?: (url != null && isWarm(url))
        if (url != null && (everPooled.value || hasAnyWarmHost()) && !warm) {
            val recentCold = health.value.coldLatencies.filter { it.at.elapsedNow() < LATENCY_MEMORY }
            if (recentCold.size >= MIN_COLD_LATENCY_SAMPLES) {
                val coldTail = recentCold.map { it.wait }.sorted()[(recentCold.size * 9) / 10]
                return maxOf(base, coldTail * COLD_DEADLINE_RATIO).coerceIn(MIN_DEADLINE, MAX_DEADLINE)
            }
            return maxOf(base * COLD_DEADLINE_RATIO, INITIAL_DEADLINE).coerceAtMost(MAX_DEADLINE)
        }
        return base
    }

    /**
     * Header response deadline for active playback / blocking foreground reads, tightened below
     * [MIN_DEADLINE] / [INITIAL_DEADLINE] only when recent route evidence proves the socket is
     * far past both pooled RTT (when [connectionReused] is `true`) and cold TLS handshake P90
     * (when [connectionReused] is `false` or unknown).
     */
    fun urgentFirstResponseDeadline(
        url: String? = null,
        claimedWarmSocket: Boolean? = null,
        connectionReused: Boolean? = null,
        priority: Int = 0,
    ): Duration {
        val standard = firstResponseDeadline(url, claimedWarmSocket)
        if (priority < io.github.nihildigit.pikpak.PikPakStreamReader.STREAMING_PRIORITY) return standard
        val state = health.value
        if (connectionReused == true) {
            val recentClean = state.cleanLatencies.filter { it.at.elapsedNow() < LATENCY_MEMORY }
            if (recentClean.size >= ROUTE_EPOCH_SAMPLES) {
                val cleanTail = recentClean.map { it.wait }.sorted()[(recentClean.size * 9) / 10]
                return (cleanTail * URGENT_DEADLINE_RATIO).coerceIn(URGENT_MIN_DEADLINE, standard)
            }
            val recentAll = state.latencies.filter { it.at.elapsedNow() < LATENCY_MEMORY }
            if (recentAll.size >= MIN_LATENCY_SAMPLES) {
                val tail = recentAll.map { it.wait }.sorted()[(recentAll.size * 9) / 10]
                return (tail * LATENCY_RATIO).coerceIn(MIN_DEADLINE, standard)
            }
            return standard
        }
        if (priority >= io.github.nihildigit.pikpak.PikPakStreamReader.BLOCKING_PRIORITY) {
            val recentCold = state.coldLatencies.filter { it.at.elapsedNow() < LATENCY_MEMORY }
            if (recentCold.size >= MIN_COLD_LATENCY_SAMPLES) {
                val coldTail = recentCold.map { it.wait }.sorted()[(recentCold.size * 9) / 10]
                return (coldTail * URGENT_DEADLINE_RATIO).coerceIn(URGENT_COLD_MIN_DEADLINE, standard)
            }
        }
        return standard
    }

    /**
     * How long an already-answered response body may stop delivering bytes before the attempt is
     * cut off and resumed elsewhere. Independent of cold TLS handshake waits: once headers have
     * arrived the handshake is over, so a stalled body on an urgent playback/seek read should
     * fail over in 2–3 s rather than waiting 8–9 s behind cold-open latency samples.
     */
    fun bodySilenceDeadline(priority: Int = 0): Duration {
        val urgentFloor = if (priority >= io.github.nihildigit.pikpak.PikPakStreamReader.BLOCKING_PRIORITY) {
            URGENT_BODY_SILENCE
        } else {
            BODY_SILENCE
        }
        if (priority >= io.github.nihildigit.pikpak.PikPakStreamReader.READ_AHEAD_PRIORITY) return urgentFloor
        val recent = health.value.cleanLatencies.filter { it.at.elapsedNow() < LATENCY_MEMORY }
        val median = if (recent.size < MIN_LATENCY_SAMPLES) null else {
            recent.map { it.wait }.sorted()[(recent.size - 1) / 2]
        }
        if (median == null || median < 1.seconds) return urgentFloor
        return (median * 3).coerceIn(urgentFloor, MAX_DEADLINE)
    }

    fun hasGoodHistory(url: String): Boolean {
        val host = keyOf(url) ?: return false
        return health.value.recentRates(host).any { it >= GOOD_RATE_BYTES_PER_SEC }
    }

    fun hasHealthySibling(url: String): Boolean {
        val edge = Edge.parse(url) ?: return false
        return with(health.value) {
            siblingsOf(edge).any { sibling ->
                usable(sibling) && (medianRate(sibling.key) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC
            }
        }
    }

    /**
     * Records clean time-to-first-body-byte ([ttfb]) on [url]'s service-isolated host key, distinct
     * from response-header latency so [RecoveryPolicy] models actual first-byte arrival.
     */
    fun recordFirstByteLatency(url: String, ttfb: Duration, connectionReused: Boolean? = null) {
        if (!ttfb.isFinite() || ttfb < Duration.ZERO) return
        val host = keyOf(url) ?: return
        val now = timeSource.markNow()
        val sample = LatencySample(ttfb, now)
        health.update { current ->
            val kept = current.hostFirstByteLatencies[host].orEmpty()
                .filter { it.at.elapsedNow() < memory }
                .takeLast(MAX_SAMPLES - 1)
            val updatedCold = if (connectionReused == false) {
                val keptCold = current.hostColdFirstByteLatencies[host].orEmpty()
                    .filter { it.at.elapsedNow() < memory }
                    .takeLast(MAX_SAMPLES - 1)
                current.hostColdFirstByteLatencies + (host to (keptCold + sample))
            } else {
                current.hostColdFirstByteLatencies
            }
            current.copy(
                hostFirstByteLatencies = current.hostFirstByteLatencies + (host to (kept + sample)),
                hostColdFirstByteLatencies = updatedCold,
            )
        }
    }

    /**
     * [url]'s host answered headers promptly, then stalled mid-body over total active+silence
     * [bodyElapsed] duration after delivering [deliveredBytes].
     *
     * Such hosts (e.g. an edge throttled to 10–40 KB/s after a 100 KiB initial burst) beat the
     * first-response deadline and fail short of [MIN_SAMPLE_BYTES], escaping both [markSilent]
     * and [delivered]. Because response headers already arrived on this connection, local network
     * connectivity is proven and concurrent header arrivals on the same host must not clear or
     * suppress the mid-body stall evidence. It expires with the evidence window or a route epoch change.
     */
    fun stalled(url: String, deliveredBytes: Long, bodyElapsed: Duration) {
        val host = keyOf(url) ?: return
        val bareHost = hostOf(url)
        val now = timeSource.markNow()
        val totalSeconds = bodyElapsed.coerceAtLeast(Duration.ZERO).inWholeNanoseconds / 1e9
        val effectiveRate = if (deliveredBytes > 0L && totalSeconds > 0.0) {
            (deliveredBytes / totalSeconds).toLong().coerceAtLeast(1L)
        } else 1L
        val sample = RateSample(effectiveRate, now)
        health.update { current ->
            val provenGood = (current.medianRate(host) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC ||
                (bareHost != null && (current.medianRate(bareHost) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC)
            val alreadySuspect = current.isSuspect(host) ||
                (bareHost != null && current.isSuspect(bareHost))
            val updated = current.withRate(host, sample)
            if (provenGood && !alreadySuspect) {
                val activeSuspects = updated.suspects.filterValues { it.elapsedNow() < SUSPECT_WINDOW }
                val newSuspects = if (bareHost != null && bareHost != host) {
                    activeSuspects + (host to now) + (bareHost to now)
                } else {
                    activeSuspects + (host to now)
                }
                updated.copy(suspects = newSuspects)
            } else {
                val activeStalls = updated.stalls.filterValues { it.elapsedNow() < memory }
                val newStalls = if (bareHost != null && bareHost != host) {
                    activeStalls + (host to now) + (bareHost to now)
                } else {
                    activeStalls + (host to now)
                }
                val clearedSuspects = if (bareHost != null) {
                    updated.suspects - host - bareHost
                } else {
                    updated.suspects - host
                }
                updated.copy(stalls = newStalls, suspects = clearedSuspects)
            }
        }
    }

    fun stalled(url: String, deliveredBytes: Long, bodyBytesPerSecond: Long, silenceDuration: Duration) {
        val activeNanos = if (deliveredBytes > 0L && bodyBytesPerSecond > 0L) {
            ((deliveredBytes.toDouble() / bodyBytesPerSecond.toDouble()) * 1e9).toLong().coerceAtLeast(0L)
        } else 0L
        stalled(url, deliveredBytes, activeNanos.nanoseconds + silenceDuration.coerceAtLeast(Duration.ZERO))
    }

    private class LatencySample(val wait: Duration, val at: TimeMark)

    // Timeout lower bounds must not masquerade as a new route's clean header latency.
    // Pure transition: update may retry it against newer evidence after a failed CAS.
    private fun HealthState.recordCleanLatency(sample: LatencySample): HealthState {
        val samples = (cleanLatencies.filter { it.at.elapsedNow() < LATENCY_MEMORY } + sample)
            .takeLast(MAX_LATENCY_SAMPLES)
        val latest = samples.takeLast(ROUTE_EPOCH_SAMPLES)
        val history = samples.dropLast(ROUTE_EPOCH_SAMPLES)
        val changed = if (history.size >= ROUTE_EPOCH_SAMPLES) {
            val baseline = history.map { it.wait }.sorted()[(history.size - 1) / 2]
            val median = latest.map { it.wait }.sorted()[ROUTE_EPOCH_SAMPLES / 2]
            (median > baseline * 2.5 || median < baseline * 0.4) &&
                (median - baseline).absoluteValue >= ROUTE_EPOCH_MIN_CHANGE
        } else false
        return if (changed) {
            HealthState(epoch = epoch + 1, latencies = latest, cleanLatencies = latest)
        } else {
            copy(cleanLatencies = samples, latencies = (latencies + sample).takeLast(MAX_LATENCY_SAMPLES))
        }
    }

    private fun recordLatency(wait: Duration) {
        val sample = LatencySample(wait, timeSource.markNow())
        health.update { it.copy(latencies = (it.latencies + sample).takeLast(MAX_LATENCY_SAMPLES)) }
    }

    fun isBad(url: String): Boolean {
        val host = keyOf(url) ?: return false
        val bareHost = hostOf(url)
        return with(health.value) {
            isSilent(host) || isStalled(host) ||
                (bareHost != null && (isSilent(bareHost) || isStalled(bareHost)))
        }
    }

    fun isAnswering(url: String): Boolean {
        val host = keyOf(url) ?: return false
        return answers.value[host]?.let { it.elapsedNow() < CORROBORATION } == true
    }

    fun isWarm(url: String): Boolean {
        val host = keyOf(url) ?: return false
        return (idleSockets.value[host] ?: 0) > 0 && answers.value[host]?.let { it.elapsedNow() < WARM_TTL } == true
    }

    fun hasAnyWarmHost(): Boolean =
        idleSockets.value.entries.any { (host, count) ->
            count > 0 && answers.value[host]?.let { it.elapsedNow() < WARM_TTL } == true
        }

    fun coldClusterUrls(): List<String> =
        lastClusterUrl.value.values.map { route(it, mayExplore = false) }.distinct().filter { !isWarm(it) }

    fun tryAcquireDrain(): Boolean {
        while (true) {
            val cur = activeDrains.value
            if (cur >= MAX_CONCURRENT_DRAINS) return false
            if (activeDrains.compareAndSet(cur, cur + 1)) return true
        }
    }

    fun releaseDrain() {
        activeDrains.update { (it - 1).coerceAtLeast(0) }
    }

    /**
     * One finished attempt on [host]: [bytes] delivered at [bytesPerSecond] from the first byte on.
     *
     * Attempts under [MIN_SAMPLE_BYTES] are dropped. At the route's round trip most of a small
     * one is TCP slow start, so its rate says more about the request size than about the host.
     */
    fun delivered(host: String?, bytes: Long, bytesPerSecond: Long, url: String? = null) {
        if (host == null || bytes < MIN_SAMPLE_BYTES || bytesPerSecond <= 0) return
        val key = if (url == null) host else keyOf(url) ?: return
        val bareHost = if (url == null) host else hostOf(url)
        val sample = RateSample(bytesPerSecond, timeSource.markNow())
        health.update { current ->
            val updated = current.withRate(key, sample)
            if (bytesPerSecond >= GOOD_RATE_BYTES_PER_SEC) {
                val cleared = if (bareHost != null) updated.silences - key - bareHost else updated.silences - key
                updated.copy(
                    silences = cleared,
                )
            } else {
                updated
            }
        }
    }

    /**
     * Records controlled post-first-byte body throughput for clean non-fault stops (such as
     * `BoundaryStop` or `OptimizedReplacement`) without applying any failure penalty.
     */
    fun recordBodyRate(url: String, bytes: Long, bodyDuration: Duration, bytesPerSecond: Long) {
        if (bytes <= 0L || bytesPerSecond <= 0L) return
        if (bytes < MIN_SAMPLE_BYTES && bodyDuration < MIN_CONTROLLED_RATE_DURATION) return
        val key = keyOf(url) ?: return
        val bareHost = hostOf(url)
        val sample = RateSample(bytesPerSecond, timeSource.markNow())
        health.update { current ->
            val updated = current.withRate(key, sample)
            if (bytes >= MIN_SAMPLE_BYTES && bytesPerSecond >= GOOD_RATE_BYTES_PER_SEC) {
                val cleared = if (bareHost != null) updated.silences - key - bareHost else updated.silences - key
                updated.copy(silences = cleared)
            } else {
                updated
            }
        }
    }

    /**
     * Returns the best verified replacement candidate in the same edge cluster, service pool and
     * route epoch as [currentUrl], using conservative `(ttfbMillis, bodyBytesPerSecond)` estimates.
     * Returns `null` when steering is disabled, [currentUrl] is non-rewritable (e.g. `dl-a10b`
     * sharded cold storage), or no sibling has at least [MIN_SAMPLES] clean rate observations.
     */
    fun bestReplacementCandidate(
        currentUrl: String,
        candidateUrls: Collection<String> = emptyList(),
        excludedHosts: Set<String> = emptySet(),
        remainingBytes: Long = MIN_SAMPLE_BYTES,
    ): RecoveryPolicy.CandidateEstimate? =
        health.value.bestReplacementCandidate(currentUrl, candidateUrls, excludedHosts, remainingBytes)

    private fun HealthState.bestReplacementCandidate(
        currentUrl: String,
        candidateUrls: Collection<String>,
        excludedHosts: Set<String>,
        remainingBytes: Long,
    ): RecoveryPolicy.CandidateEstimate? {
        if (!steering) return null
        val currentEdge = Edge.parse(currentUrl) ?: return null
        learn(currentEdge)
        val extraEdges = candidateUrls.mapNotNull { candUrl ->
            Edge.parse(candUrl)?.takeIf { it.cluster == currentEdge.cluster && it.label != currentEdge.label }
                ?.also(::learn)
        }
        val candidates = (siblingsOf(currentEdge) + extraEdges)
            .distinctBy { it.label }
            .filter { it.host !in excludedHosts && usable(it) && !isSuspect(it) }
        if (candidates.isEmpty()) return null

        val targetBytes = remainingBytes.coerceAtLeast(1L).toDouble()
        val poolTracked = everPooled.value || hasAnyWarmHost()
        return candidates.mapNotNull { cand ->
            val rate = medianRate(cand.key)?.takeIf { it > 0L } ?: return@mapNotNull null
            val candUrl = currentEdge.rewrite(currentUrl, cand)
            val candWarm = if (poolTracked) isWarm(candUrl) else null
            val ttfbMs = conservativeTtfbMillis(cand.key, candWarm)
            RecoveryPolicy.CandidateEstimate(
                hostKey = cand.key,
                url = candUrl,
                ttfbMillis = ttfbMs,
                bodyBytesPerSecond = rate,
            )
        }.minByOrNull { est ->
            est.ttfbMillis.toDouble() + (targetBytes * 1000.0) / est.bodyBytesPerSecond.toDouble()
        }
    }

    private fun HealthState.conservativeTtfbMillis(hostKey: String, warmSocketAvailable: Boolean? = null): Long {
        val baseEstimate = run {
            val recentTtfb = hostFirstByteLatencies[hostKey].orEmpty()
                .filter { it.at.elapsedNow() < memory }
                .map { it.wait.inWholeMilliseconds.coerceAtLeast(0L) }
                .sorted()
            if (recentTtfb.isNotEmpty()) {
                return@run recentTtfb[((recentTtfb.size * 3) / 4).coerceIn(0, recentTtfb.lastIndex)]
            }
            val recentHeaders = hostLatencies[hostKey].orEmpty()
                .filter { it.at.elapsedNow() < memory }
                .map { it.wait.inWholeMilliseconds.coerceAtLeast(0L) }
                .sorted()
            if (recentHeaders.isNotEmpty()) {
                return@run recentHeaders[((recentHeaders.size * 3) / 4).coerceIn(0, recentHeaders.lastIndex)]
            }
            val accountRecent = latencies
                .filter { it.at.elapsedNow() < LATENCY_MEMORY }
                .map { it.wait.inWholeMilliseconds.coerceAtLeast(0L) }
                .sorted()
            if (accountRecent.isNotEmpty()) {
                return@run accountRecent[((accountRecent.size * 3) / 4).coerceIn(0, accountRecent.lastIndex)]
                    .coerceAtLeast(DEFAULT_CONSERVATIVE_TTFB_MS)
            }
            DEFAULT_CONSERVATIVE_TTFB_MS
        }
        if (warmSocketAvailable != false) return baseEstimate

        // No idle pooled socket is available for this candidate host right now: switching to it
        // must pay a cold TCP+TLS handshake rather than its warm pooled TTFB.
        val hostColdTtfb = hostColdFirstByteLatencies[hostKey].orEmpty()
            .filter { it.at.elapsedNow() < memory }
            .map { it.wait.inWholeMilliseconds.coerceAtLeast(0L) }
            .sorted()
        if (hostColdTtfb.isNotEmpty()) {
            val coldP75 = hostColdTtfb[((hostColdTtfb.size * 3) / 4).coerceIn(0, hostColdTtfb.lastIndex)]
            return maxOf(baseEstimate, coldP75)
        }
        val accountColdHeaders = coldLatencies
            .filter { it.at.elapsedNow() < LATENCY_MEMORY }
            .map { it.wait.inWholeMilliseconds.coerceAtLeast(0L) }
            .sorted()
        if (accountColdHeaders.isNotEmpty()) {
            val coldP75 = accountColdHeaders[((accountColdHeaders.size * 3) / 4).coerceIn(0, accountColdHeaders.lastIndex)]
            return maxOf(baseEstimate, coldP75)
        }
        return maxOf(baseEstimate * COLD_DEADLINE_RATIO, DEFAULT_CONSERVATIVE_TTFB_MS)
    }

    private fun HealthState.withRate(host: String, sample: RateSample): HealthState {
        val kept = rates[host].orEmpty().filter { it.at.elapsedNow() < memory }.takeLast(MAX_SAMPLES - 1)
        return copy(rates = rates + (host to kept + sample))
    }

    /** A rerouted attempt failed on [url]'s host; it is not offered again for [memory]. */
    fun refused(url: String) {
        val host = keyOf(url) ?: return
        val now = timeSource.markNow()
        health.update { current ->
            current.copy(refusals = current.refusals.filterValues { it.elapsedNow() < memory } + (host to now))
        }
    }

    /**
     * The URL an attempt on [url] should go to: [url] itself, or the same path and query on a
     * sibling host. [mayExplore] allows a sibling with no figures yet; see the class comment.
     * Foreground [priority] avoids cold exploration on a high-latency route when this host's
     * measured throughput is already viable. Known improvements and failover remain available.
     */
    fun route(
        url: String,
        mayExplore: Boolean,
        requestedBytes: Long = MIN_SAMPLE_BYTES,
        priority: Int = 0,
        demandBytesPerSecond: Long? = null,
    ): String = health.value.route(url, mayExplore, requestedBytes, priority, demandBytesPerSecond)

    private fun HealthState.route(
        url: String, mayExplore: Boolean, requestedBytes: Long, priority: Int,
        demandBytesPerSecond: Long?,
    ): String {
        if (!steering) return url
        val edge = Edge.parse(url) ?: return url
        learn(edge)
        val edgeProvenGood = (medianRate(edge.key) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC ||
            (medianRate(edge.host) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC
        val avoidCongestedPrimary = isCongestedLabel(edge.label) && !edgeProvenGood
        if (usable(edge) && !avoidCongestedPrimary) return url
        val usableSiblings = siblingsOf(edge).filter { usable(it) }
        val cleanSiblings = usableSiblings.filter { !isSuspect(it) }
        if (!usable(edge)) {
            val pool = cleanSiblings.ifEmpty { usableSiblings }
            val measured = pool.filter { recentRates(it.key).isNotEmpty() }
                .maxByOrNull { medianRate(it.key) ?: recentRates(it.key).minOrNull() ?: 0L }
            val candidate = measured
                ?: pool.firstOrNull { !isCongestedLabel(it.label) }
                ?: pool.firstOrNull()
            return candidate?.let { edge.rewrite(url, it) } ?: url
        }
        val nonCongestedClean = cleanSiblings.filter { !isCongestedLabel(it.label) }
        val measuredFast = nonCongestedClean
            .filter { (medianRate(it.key) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC }
            .maxByOrNull { medianRate(it.key) ?: 0L }
        val candidate = measuredFast ?: nonCongestedClean.firstOrNull()
        return candidate?.let { edge.rewrite(url, it) } ?: url
    }

    private fun learn(edge: Edge) {
        learned.update { current ->
            val labels = current[edge.cluster].orEmpty()
            if (edge.label in labels) current else current + (edge.cluster to (labels + edge.label))
        }
    }

    /** Only URLs issued for the same service class authorize sibling routing. */
    fun observe(detail: io.github.nihildigit.pikpak.FileDetail) {
        Edge.parse(detail.webContentLink)?.let(::learn)
        for (link in detail.links.values) Edge.parse(link.url)?.let(::learn)
        for (media in detail.medias) Edge.parse(media.link.url)?.let(::learn)
    }

    private fun siblingsOf(edge: Edge): List<Edge> =
        (learned.value[edge.cluster].orEmpty() +
            if (edge.pool == "download" || edge.pool == "transcoded") seedLabels else emptyList())
            .distinct().filter { it != edge.label && Edge.familyOf(it) == edge.family }
            .map { Edge(it, edge.family, edge.root, edge.pool) }

    private fun HealthState.usable(edge: Edge): Boolean =
        usable(edge.key) && !isSilent(edge.host) && !isStalled(edge.host)

    private fun HealthState.usable(host: String): Boolean = !isSilent(host) && !isStalled(host) && !isRefused(host)

    private fun HealthState.isSuspect(edge: Edge): Boolean = isSuspect(edge.key) || isSuspect(edge.host)

    private fun HealthState.isSuspect(host: String): Boolean = suspects[host]?.let { it.elapsedNow() < SUSPECT_WINDOW } == true

    private fun isCongestedLabel(label: String): Boolean {
        val nodeNumber = label.substringAfterLast('-', "").toIntOrNull() ?: return false
        return nodeNumber in 26..27 || nodeNumber in 51..55 || nodeNumber in 63..64
    }

    private fun HealthState.isSilent(host: String): Boolean = silences[host]?.let { it.elapsedNow() < memory } == true

    private fun HealthState.isStalled(host: String): Boolean = stalls[host]?.let { it.elapsedNow() < memory } == true

    private fun HealthState.isRefused(host: String): Boolean = refusals[host]?.let { it.elapsedNow() < memory } == true

    /**
     * Null until the host has [MIN_SAMPLES] recent samples; one fast or slow attempt is not a
     * host's speed. The lower middle of an even count: on a direct line one host gave 1.38 and
     * then 0.06 MB/s, and a single lucky burst should not make it the destination for every read.
     */
    private fun HealthState.medianRate(host: String): Long? {
        val recent = recentRates(host)
        if (recent.size < MIN_SAMPLES) return null
        return recent.sorted()[(recent.size - 1) / 2]
    }

    private fun HealthState.recentRates(host: String): List<Long> =
        rates[host].orEmpty().filter { it.at.elapsedNow() < memory }.map { it.bytesPerSecond }

    /** A `<cluster>-<number>.<root>` edge host, rerouted only within the same `<cluster>` and `<root>`. */
    private class Edge(val label: String, val family: String, val root: String, val pool: String) {
        val cluster: String get() = "$family.$root|$pool"
        val key: String get() = if (pool == "download") host else "$host|$pool"
        val host: String get() = "$label.$root"

        fun rewrite(url: String, to: Edge): String =
            url.substringBefore("://") + "://" + to.host + url.substringAfter("://").removePrefix(host)

        companion object {
            private val SHAPE = Regex("""^([a-z0-9]+(?:-[a-z0-9]+)+)-\d+$""")

            fun familyOf(label: String): String? = SHAPE.matchEntire(label)?.groupValues?.get(1)

            fun parse(url: String): Edge? {
                val host = hostOf(url) ?: return null
                // A port or credentials in the authority would not survive the rewrite; no link has carried either
                val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                if (authority != host) return null
                val label = host.substringBefore('.')
                val root = host.substringAfter('.', "").ifEmpty { return null }
                val family = familyOf(label) ?: return null
                // dl-a10b-* is a sharded cold/archive storage cluster (1500+ nodes), not a stateless
                // front-end CDN edge pool; rewriting onto sibling nodes returns 0-byte 206 bodies.
                if (family.equals("dl-a10b", ignoreCase = true)) return null
                return Edge(label, family, root, poolOf(url) ?: return null)
            }
        }
    }

    internal companion object {
        /**
         * Long, because forgetting is the expensive mistake: avoiding a host that has recovered
         * costs one extra link request, around 180 ms, while returning to one that has not
         * costs the first-response deadline again on every read that lands there.
         */
        val DEFAULT_MEMORY: Duration = 10.minutes
        val SUSPECT_WINDOW: Duration = 2.minutes
        val ROUTE_LEASE: Duration = 30.seconds
        val DEFAULT_DL_Z01A_SEEDS: List<String> = (41..50).map { "dl-z01a-${it.toString().padStart(4, '0')}" }

        /** How recently another host must have answered for a silence to be the host's own. */
        val CORROBORATION: Duration = 10.seconds
        val WARM_TTL: Duration = 12.seconds

        /** The floor once the line is measured: four times the 500 ms a healthy host takes on a good line. */
        val MIN_DEADLINE: Duration = 2.seconds

        /**
         * Before the line is measured. Playback opens a file with eight new connections at once,
         * and with the floor here, measured 2026-10-05 on a direct line, seven of the eight were
         * cut off mid-handshake and opened again. A good line measures itself within a second
         * and drops to the floor; what this costs is a host dead on the very first request.
         */
        val INITIAL_DEADLINE: Duration = 5.seconds

        /**
         * A dead host does not answer within a minute either, so the cap only bounds what one
         * costs: on a line slow enough to reach it, a 256 KiB block takes longer than that.
         */
        val MAX_DEADLINE: Duration = 15.seconds

        const val LATENCY_RATIO = 2
        const val COLD_DEADLINE_RATIO = 2
        const val URGENT_DEADLINE_RATIO = 3
        val URGENT_MIN_DEADLINE: Duration = 1_000.milliseconds
        val URGENT_COLD_MIN_DEADLINE: Duration = 1_200.milliseconds
        val BODY_SILENCE: Duration = 3.seconds
        val URGENT_BODY_SILENCE: Duration = 2.seconds
        const val MIN_LATENCY_SAMPLES = 8
        const val MIN_COLD_LATENCY_SAMPLES = 4
        const val MAX_LATENCY_SAMPLES = 32

        /** Short against [DEFAULT_MEMORY]: a proxy switched on or off changes the line within seconds. */
        val LATENCY_MEMORY: Duration = 2.minutes

        private const val ROUTE_EPOCH_SAMPLES = 3
        private val ROUTE_EPOCH_MIN_CHANGE = 250.milliseconds

        const val GOOD_RATE_BYTES_PER_SEC = 250L * 1024
        const val MAX_CONCURRENT_DRAINS = 2
        const val MAX_UNMEASURED_CLUSTER_FALLBACKS = 2

        const val MIN_SAMPLES = 2
        const val MAX_SAMPLES = 6
        const val MIN_SAMPLE_BYTES = 256L * 1024
        val MIN_CONTROLLED_RATE_DURATION: Duration = 500.milliseconds
        const val DEFAULT_CONSERVATIVE_TTFB_MS = 1_100L

        /** Hosts per family with figures before exploring stops: enough that one slow host stands out. */
        const val EXPLORE_TARGET = 3

        /**
         * A sibling needs [MIN_SAMPLES] explorations before it can be compared, so this sets how
         * long a read stays on a slow host.
         */
        val EXPLORE_INTERVAL: Duration = 5.seconds

        /** Null means the link may be used as issued, but cannot authorize sibling routing. */
        fun poolOf(url: String): String? {
            val params = signedUrlParameters(url) ?: return null
            if (params.getAll("category").orEmpty().size > 1 || params.getAll("t").orEmpty().size > 1) return null
            val category = params["category"]
            val type = params["t"]
            if (category != null && category !in listOf("original", "transcoded")) return null
            if (type != null && type !in listOf("0", "1")) return null
            if ((category == "original" && type == "1") || (category == "transcoded" && type == "0")) return null
            return when {
                category == "original" -> "original"
                category == "transcoded" || type == "1" -> "transcoded"
                else -> "download"
            }
        }

        private fun keyOf(url: String): String? = hostOf(url)?.let { host ->
            val pool = poolOf(url) ?: return null
            if (pool == "download") host else "$host|$pool"
        }

        fun hostOf(url: String): String? =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').ifEmpty { null }
    }
}
