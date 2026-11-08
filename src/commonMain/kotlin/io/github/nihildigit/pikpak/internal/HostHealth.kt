package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
    private val foregroundHungry: () -> Boolean = { false },
) {
    private data class HealthState(
        val epoch: Long = 0,
        val rates: Map<String, List<RateSample>> = emptyMap(),
        val hostLatencies: Map<String, List<LatencySample>> = emptyMap(),
        val latencies: List<LatencySample> = emptyList(),
        val cleanLatencies: List<LatencySample> = emptyList(),
        val silences: Map<String, TimeMark> = emptyMap(),
        val stalls: Map<String, TimeMark> = emptyMap(),
        val suspects: Map<String, TimeMark> = emptyMap(),
        val refusals: Map<String, TimeMark> = emptyMap(),
        val preferredByCluster: Map<String, RouteLease> = emptyMap(),
    )

    // Route evidence and its epoch are published together. A reader never sees a partial reset.
    private val health = MutableStateFlow(HealthState())
    private val answers = MutableStateFlow<Map<String, TimeMark>>(emptyMap())

    /** Host labels without the root domain, in the order links named them. Tried before [seedLabels]. */
    private val learned = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val seedLabels = seeds.toList()
    private val lastExplore = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val activeDrains = MutableStateFlow(0)
    private val idleSockets = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val everPooled = MutableStateFlow(false)
    private val lastClusterUrl = MutableStateFlow<Map<String, String>>(emptyMap())

    private class RateSample(val bytesPerSecond: Long, val at: TimeMark)
    private class RouteLease(val label: String, val at: TimeMark)

    /**
     * [url]'s host sent response headers [after] the request went out. Null when the wait is not
     * a clean measure, e.g. it spanned a retry inside the HTTP layer. Only a proven
     * [connectionReused] sample trains warm route-epoch detection; unknown is not warm.
     */
    fun answered(url: String, after: Duration? = null, connectionReused: Boolean? = null) {
        val host = keyOf(url) ?: return
        answers.update { it + (host to timeSource.markNow()) }
        val sample = after?.let { LatencySample(it, timeSource.markNow()) }
        health.update { previous ->
            // Only proven pooled connections compare route RTT. Cold handshakes and engines
            // without connection evidence still train deadlines, never the route epoch.
            val current = when {
                sample == null -> previous
                connectionReused == true -> previous.recordCleanLatency(sample)
                else -> previous.copy(latencies = (previous.latencies + sample).takeLast(MAX_LATENCY_SAMPLES))
            }
            current.copy(
                silences = current.silences - host,
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
        if (answers.value[host]?.let { it.elapsedNow() < waited } == true) return
        val othersAnswering = answers.value.any { (other, at) -> other != host && at.elapsedNow() < CORROBORATION }
        if (!othersAnswering) return
        val now = timeSource.markNow()
        health.update { current ->
            current.copy(silences = current.silences.filterValues { it.elapsedNow() < memory } + (host to now))
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
            return maxOf(base * COLD_DEADLINE_RATIO, INITIAL_DEADLINE).coerceAtMost(MAX_DEADLINE)
        }
        return base
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
                usable(sibling.key) && (medianRate(sibling.key) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC
            }
        }
    }

    /**
     * [url]'s host answered headers promptly, then stalled mid-body for [silenceDuration].
     *
     * Such hosts (e.g. an edge throttled to 10–40 KB/s after a 100 KiB initial burst) beat the
     * first-response deadline and fail short of [MIN_SAMPLE_BYTES], escaping both [markSilent]
     * and [delivered]. Because response headers already arrived on this connection, local network
     * connectivity is proven and concurrent header arrivals on the same host must not clear or
     * suppress the mid-body stall penalty; only a healthy completed transfer ([delivered]) clears it.
     */
    fun stalled(url: String, deliveredBytes: Long, bodyBytesPerSecond: Long, silenceDuration: Duration) {
        recordLatency(silenceDuration)
        val host = keyOf(url) ?: return
        val now = timeSource.markNow()
        val effectiveRate = if (deliveredBytes > 0L) bodyBytesPerSecond.coerceAtLeast(1L) else 1L
        val sample = RateSample(effectiveRate, now)
        health.update { current ->
            if (deliveredBytes >= MIN_SAMPLE_BYTES && effectiveRate >= GOOD_RATE_BYTES_PER_SEC) {
                return@update current.withRate(host, sample)
            }
            val hadGoodHistory = current.recentRates(host).any { it >= GOOD_RATE_BYTES_PER_SEC }
            val alreadySuspect = current.isSuspect(host)
            val updated = current.withRate(host, sample)
            if (hadGoodHistory && !alreadySuspect) {
                updated.copy(
                    suspects = current.suspects.filterValues { it.elapsedNow() < SUSPECT_WINDOW } + (host to now),
                )
            } else {
                updated.copy(
                    stalls = current.stalls.filterValues { it.elapsedNow() < memory } + (host to now),
                    suspects = current.suspects - host,
                )
            }
        }
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
        return with(health.value) { isSilent(host) || isStalled(host) }
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
        val sample = RateSample(bytesPerSecond, timeSource.markNow())
        health.update { current ->
            val updated = current.withRate(key, sample)
            if (bytesPerSecond >= GOOD_RATE_BYTES_PER_SEC) {
                updated.copy(
                    silences = updated.silences - key,
                    stalls = updated.stalls - key,
                    suspects = updated.suspects - key,
                    refusals = updated.refusals - key,
                )
            } else {
                updated
            }
        }
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
        foregroundHungry: Boolean = this.foregroundHungry(),
        demandBytesPerSecond: Long? = null,
    ): String = health.value.route(url, mayExplore, requestedBytes, priority, foregroundHungry, demandBytesPerSecond)

    private fun HealthState.route(
        url: String,
        mayExplore: Boolean,
        requestedBytes: Long,
        priority: Int,
        foregroundHungry: Boolean,
        demandBytesPerSecond: Long?,
    ): String {
        if (!steering) return url
        val edge = Edge.parse(url) ?: return url
        learn(edge)

        val clusterKey = edge.cluster
        val siblings = siblingsOf(edge)
        val allCandidates = siblings + edge
        val bytes = requestedBytes.coerceAtLeast(1)
        fun viable(host: String): Boolean = usable(host) &&
            (medianRate(host) ?: 0L) >= (demandBytesPerSecond?.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val lease = preferredByCluster[clusterKey]
        if (lease != null && lease.at.elapsedNow() < ROUTE_LEASE) {
            allCandidates.firstOrNull { it.label == lease.label && usable(it.key) &&
                (medianRate(it.key) ?: 0L) >= (demandBytesPerSecond?.takeIf { it > 0 } ?: GOOD_RATE_BYTES_PER_SEC) }?.let {
                return edge.rewrite(url, it)
            }
        }
        val best = siblings
            .filter { usable(it.key) }
            .mapNotNull { sibling -> completionSeconds(sibling.key, bytes)?.let { sibling to it } }
            .minByOrNull { it.second }
        if (best != null) {
            val own = completionSeconds(edge.key, bytes)
            // Require a gain beyond observed variability and a fresh request's setup cost.
            val ownIsSlow = own != null && own - best.second >
                uncertaintySeconds(edge.key, bytes) + uncertaintySeconds(best.first.key, bytes) +
                latencySeconds(best.first.key)
            if (ownIsSlow || isSilent(edge.key) || isStalled(edge.key)) {
                rememberPreferred(clusterKey, best.first.label)
                return edge.rewrite(url, best.first)
            }
        } else if (isSilent(edge.key) || isStalled(edge.key)) {
            // Even before any sibling has accumulated MIN_SAMPLES full completions, a silent or
            // stalled host yields to a usable observed sibling so retries do not hammer the same
            // degraded edge node.
            siblings.firstOrNull { usable(it.key) }?.let { fallback ->
                return edge.rewrite(url, fallback)
            }
        }

        // After the hard lease expires, retain a proven preference for unmeasured links
        // until another host demonstrates a gain. Rate evidence still ages out with memory.
        val currentPreferredLabel = preferredByCluster[clusterKey]?.label
        val currentPreferred = currentPreferredLabel?.let { label ->
            allCandidates.firstOrNull { it.label == label && usable(it.key) }
                ?.let { candidate -> completionSeconds(candidate.key, bytes)?.let { candidate to it } }
        }
        val preferredEdge = if (currentPreferred != null) {
            val (preferred, prefTime) = currentPreferred
            val beatenByBest = best != null && prefTime - best.second >
                uncertaintySeconds(preferred.key, bytes) + uncertaintySeconds(best.first.key, bytes) +
                latencySeconds(best.first.key)
            if (!beatenByBest) preferred else best.first
        } else {
            if (currentPreferredLabel != null) {
                rememberPreferred(clusterKey, null)
            }
            allCandidates.firstOrNull { cand ->
                usable(cand.key) && medianRate(cand.key) != null
            }
        }
        if (preferredEdge != null) {
            if (preferredEdge.label != edge.label &&
                (preferredEdge.label != currentPreferredLabel || lease == null || lease.at.elapsedNow() >= ROUTE_LEASE)
            ) {
                rememberPreferred(clusterKey, preferredEdge.label)
            }
            val own = medianRate(edge.key)
            if (own == null && recentRates(edge.key).isEmpty()) {
                return if (preferredEdge.label == edge.label) url else edge.rewrite(url, preferredEdge)
            }
        }

        if (!mayExplore || foregroundHungry || viable(edge.key)) return url
        val measured = (siblings + edge).count { medianRate(it.key) != null }
        if (measured >= EXPLORE_TARGET) return url
        // A sibling half measured goes first, or every candidate would stop at one sample and
        // the family would never reach the target that ends exploring
        val candidate = siblings
            .filter {
                usable(it.key) && recentRates(it.key).size < MIN_SAMPLES
            }
            .maxByOrNull { recentRates(it.key).size } ?: return url
        // Exploration pays one estimated request. More uncertain paths justify earlier sampling;
        // a stable measured path waits longer, regardless of its absolute throughput.
        // A long download must not postpone its next observation for the entire
        // declared range lifetime. Exploration needs only one committed sample.
        val sampleBytes = minOf(bytes, MIN_SAMPLE_BYTES)
        val expected = completionSeconds(edge.key, sampleBytes)
        val uncertainty = uncertaintySeconds(edge.key, sampleBytes)
        val cooldown = if (expected == null) EXPLORE_INTERVAL else
            maxOf(EXPLORE_INTERVAL, (expected * (1.0 + expected / uncertainty.coerceAtLeast(expected / 4))).seconds)
        if (!claimExploreSlot(edge.cluster, cooldown)) return url
        return edge.rewrite(url, candidate)
    }

    private fun HealthState.rememberPreferred(cluster: String, label: String?) {
        health.update { current ->
            // A route computed before a reset must not restore the old epoch's preference.
            if (current.epoch != epoch) current else current.copy(
                preferredByCluster = if (label == null) current.preferredByCluster - cluster else
                    current.preferredByCluster + (cluster to RouteLease(label, timeSource.markNow())),
            )
        }
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
        (learned.value[edge.cluster].orEmpty() + if (edge.pool == "download") seedLabels else emptyList())
            .distinct().filter { it != edge.label && Edge.familyOf(it) == edge.family }
            .map { Edge(it, edge.family, edge.root, edge.pool) }

    private fun HealthState.usable(host: String): Boolean = !isSilent(host) && !isStalled(host) && !isRefused(host)

    private fun HealthState.isSilent(host: String): Boolean = silences[host]?.let { it.elapsedNow() < memory } == true

    private fun HealthState.isStalled(host: String): Boolean = stalls[host]?.let { it.elapsedNow() < memory } == true

    private fun HealthState.isSuspect(host: String): Boolean = suspects[host]?.let { it.elapsedNow() < SUSPECT_WINDOW } == true

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

    private fun HealthState.latencySeconds(host: String): Double {
        val waits = hostLatencies[host].orEmpty().filter { it.at.elapsedNow() < memory }
            .map { it.wait.inWholeNanoseconds / 1e9 }.sorted()
        return if (waits.isEmpty()) 0.0 else waits[(waits.size - 1) / 2]
    }

    private fun HealthState.completionSeconds(host: String, bytes: Long): Double? =
        medianRate(host)?.let { latencySeconds(host) + bytes.toDouble() / it }

    private fun HealthState.uncertaintySeconds(host: String, bytes: Long): Double {
        val samples = recentRates(host)
        if (samples.isEmpty()) return 0.0
        val times = samples.map { bytes.toDouble() / it }
        return (times.maxOrNull()!! - times.minOrNull()!!) / kotlin.math.sqrt(samples.size.toDouble())
    }

    private fun claimExploreSlot(cluster: String, cooldown: Duration): Boolean {
        while (true) {
            val current = lastExplore.value
            val last = current[cluster]
            if (last != null && last.elapsedNow() < cooldown) return false
            if (lastExplore.compareAndSet(current, current + (cluster to timeSource.markNow()))) return true
        }
    }

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
        val BODY_SILENCE: Duration = 3.seconds
        val URGENT_BODY_SILENCE: Duration = 2.seconds
        const val MIN_LATENCY_SAMPLES = 8
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
