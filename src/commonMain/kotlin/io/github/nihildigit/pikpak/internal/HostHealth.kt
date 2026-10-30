package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the account has learned about CDN edge hosts, shared by every file on it: which went
 * silent, how fast each one delivers, and which other host a link may be sent to instead.
 *
 * PikPak signs each link for a host picked anew, and a reader that finds its host silent
 * moves to a fresh link on its own. That lesson used to stay with the one reader: the next
 * file minted a link to the same host and paid the same deadline to learn it again. Measured
 * 2026-09-26, a host that stops answering does so for every request, so one silence is
 * reason enough to steer the whole account away for a while.
 *
 * One silence counts only when another host answered recently. When the client's own network
 * drops, every request goes quiet at once and no host answers; recording those would blacklist
 * the whole pool for [memory] and make every mint afterwards pay extra lookups to dodge hosts
 * that were never at fault.
 *
 * Speed is the other half. Measured 2026-09-28 through a Singapore route, 18 of 20 `dl-z01a`
 * hosts delivered 3.2–7.2 MB/s on one connection and the other two 0.17 MB/s, in both passes;
 * eight connections got 8.8 MB/s from a fast host and 1.3 MB/s from a slow one. A slow host
 * answers promptly, so the silence check never fires, and the reader would keep it for the
 * life of the link, which is a day.
 *
 * Re-minting does not reliably escape either: twelve mints of one file named the same four
 * hosts in two runs. What does is that a link is not bound to its host. The same signed path
 * and query sent to any other working host of its family (`dl-z01a-*`, same root domain)
 * returned the same bytes on all 20 hosts that completed a handshake, while a tampered
 * signature got 403 and a host of the other family (`dl-a10b-*`) got 404. So [route] may send
 * an attempt to a sibling host, and does so in two cases only:
 *
 *  - the link's host has measured at least [SLOW_RATIO] times slower than a sibling that has
 *    itself delivered bytes on this account, or has gone silent; only a sibling with figures
 *    of its own is a destination;
 *  - a background or read-ahead attempt, at most one per [EXPLORE_INTERVAL] account-wide, may
 *    try a sibling not measured yet, until [EXPLORE_TARGET] hosts of the family have figures.
 *    Without this a single file never compares its host with anything. Playback's blocking
 *    reads never explore, since a sibling may turn out to be one of the slow ones.
 *
 * Nothing here trusts a host it has not seen work. Candidates are the hosts this account's own
 * links named, then [SEED_HOSTS], and a sibling that fails a rerouted attempt in any way — a
 * handshake refused, 403, 404, silence — is [refused] and left alone for [memory]; the reader
 * falls back to the link's own host without counting the failure. Speed is only ever compared
 * between hosts on this account, so a slow line or a slow consumer slows every host alike and
 * moves nothing.
 */
internal class HostHealth(
    private val memory: Duration = DEFAULT_MEMORY,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** False keeps every attempt on its link's own host; only silence is still remembered. */
    private val steering: Boolean = true,
    seeds: Collection<String> = emptyList(),
) {
    private val silences = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val answers = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val refusals = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val rates = MutableStateFlow<Map<String, List<RateSample>>>(emptyMap())

    /** Host labels without the root domain, in the order links named them. Tried before [seedLabels]. */
    private val learned = MutableStateFlow<List<String>>(emptyList())
    private val seedLabels = seeds.toList()
    private val lastExplore = MutableStateFlow<TimeMark?>(null)
    private val activeDrains = MutableStateFlow(0)
    private val preferredByCluster = MutableStateFlow<Map<String, String>>(emptyMap())
    private val idleSockets = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val lastClusterUrl = MutableStateFlow<Map<String, String>>(emptyMap())

    private class RateSample(val bytesPerSecond: Long, val at: TimeMark)

    /**
     * [url]'s host sent response headers [after] the request went out. Null when the wait is not
     * a clean measure, e.g. it spanned a retry inside the HTTP layer.
     */
    fun answered(url: String, after: Duration? = null) {
        val host = hostOf(url) ?: return
        answers.update { it + (host to timeSource.markNow()) }
        if (host in silences.value) silences.update { it - host }
        if (after != null) recordLatency(after)
        Edge.parse(url)?.let { edge ->
            lastClusterUrl.update { it + ("${edge.family}.${edge.root}" to url) }
        }
    }

    fun onRequestStarted(url: String) {
        val host = hostOf(url) ?: return
        idleSockets.update { cur -> cur + (host to ((cur[host] ?: 0) - 1).coerceAtLeast(0)) }
    }

    fun onSocketReturned(url: String) {
        val host = hostOf(url) ?: return
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
        val host = hostOf(url) ?: return
        if (answers.value[host]?.let { it.elapsedNow() < waited } == true) return
        val othersAnswering = answers.value.any { (other, at) -> other != host && at.elapsedNow() < CORROBORATION }
        if (!othersAnswering) return
        val now = timeSource.markNow()
        silences.update { current -> current.filterValues { it.elapsedNow() < memory } + (host to now) }
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
    fun firstResponseDeadline(url: String? = null): Duration {
        val recent = latencies.value.filter { it.at.elapsedNow() < LATENCY_MEMORY }
        val base = if (recent.size < MIN_LATENCY_SAMPLES) {
            INITIAL_DEADLINE
        } else {
            val tail = recent.map { it.wait }.sorted()[(recent.size * 9) / 10]
            (tail * LATENCY_RATIO).coerceIn(MIN_DEADLINE, MAX_DEADLINE)
        }
        // Warm pooled sockets answer in one RTT and pull P90 down to MIN_DEADLINE, while opening
        // fresh TLS connections to a cold host through a proxy can take 2–3x longer. Give cold
        // hosts handshake headroom so an 8-way cold open does not cascade across the cluster.
        if (url != null && hasAnyWarmHost() && !isWarm(url)) {
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
        val recent = latencies.value.filter { it.at.elapsedNow() < LATENCY_MEMORY }
        val median = if (recent.size < MIN_LATENCY_SAMPLES) null else {
            recent.map { it.wait }.sorted()[(recent.size - 1) / 2]
        }
        val urgentFloor = if (priority >= io.github.nihildigit.pikpak.PikPakStreamReader.BLOCKING_PRIORITY) {
            URGENT_BODY_SILENCE
        } else {
            BODY_SILENCE
        }
        if (median == null || median < 1.seconds) return urgentFloor
        return (median * 3).coerceIn(urgentFloor, MAX_DEADLINE)
    }

    fun hasHealthySibling(url: String): Boolean {
        val edge = Edge.parse(url) ?: return false
        return siblingsOf(edge).any { sibling ->
            usable(sibling.host) && (medianRate(sibling.host) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC
        }
    }

    /**
     * [url]'s host answered headers promptly, then stalled mid-body for [silenceDuration].
     *
     * Such hosts (e.g. an edge throttled to 10–40 KB/s after a 100 KiB initial burst) beat the
     * first-response deadline and fail short of [MIN_SAMPLE_BYTES], escaping both [markSilent]
     * and [delivered]. Record their effective rate regardless of sample size and mark them silent
     * (subject to [markSilent]'s corroboration checks) so subsequent reads steer to a healthy sibling.
     */
    fun stalled(url: String, deliveredBytes: Long, bodyBytesPerSecond: Long, silenceDuration: Duration) {
        val host = hostOf(url)
        if (host != null && deliveredBytes > 0L) {
            val effectiveRate = bodyBytesPerSecond.coerceAtLeast(1L)
            val sample = RateSample(effectiveRate, timeSource.markNow())
            rates.update { current ->
                val kept = current[host].orEmpty().filter { it.at.elapsedNow() < memory }.takeLast(MAX_SAMPLES - 1)
                current + (host to kept + sample)
            }
        }
        markSilent(url, silenceDuration)
    }

    private class LatencySample(val wait: Duration, val at: TimeMark)

    private val latencies = MutableStateFlow<List<LatencySample>>(emptyList())

    private fun recordLatency(wait: Duration) {
        val sample = LatencySample(wait, timeSource.markNow())
        latencies.update { (it + sample).takeLast(MAX_LATENCY_SAMPLES) }
    }

    fun isBad(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return isSilent(host)
    }

    fun isAnswering(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return answers.value[host]?.let { it.elapsedNow() < CORROBORATION } == true
    }

    fun isWarm(url: String): Boolean {
        val host = hostOf(url) ?: return false
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
    fun delivered(host: String?, bytes: Long, bytesPerSecond: Long) {
        if (host == null || bytes < MIN_SAMPLE_BYTES || bytesPerSecond <= 0) return
        val sample = RateSample(bytesPerSecond, timeSource.markNow())
        rates.update { current ->
            val kept = current[host].orEmpty().filter { it.at.elapsedNow() < memory }.takeLast(MAX_SAMPLES - 1)
            current + (host to kept + sample)
        }
    }

    /** A rerouted attempt failed on [url]'s host; it is not offered again for [memory]. */
    fun refused(url: String) {
        val host = hostOf(url) ?: return
        val now = timeSource.markNow()
        refusals.update { current -> current.filterValues { it.elapsedNow() < memory } + (host to now) }
    }

    /**
     * The URL an attempt on [url] should go to: [url] itself, or the same path and query on a
     * sibling host. [mayExplore] allows a sibling with no figures yet; see the class comment.
     */
    fun route(url: String, mayExplore: Boolean): String {
        if (!steering) return url
        val edge = Edge.parse(url) ?: return url
        learn(edge.label)

        val clusterKey = "${edge.family}.${edge.root}"
        val siblings = siblingsOf(edge)
        val allCandidates = siblings + edge
        val best = siblings
            .filter { usable(it.host) }
            .mapNotNull { sibling -> medianRate(sibling.host)?.let { sibling to it } }
            .maxByOrNull { it.second }
        if (best != null) {
            val own = medianRate(edge.host)
            val ownIsSlow = own != null && own * SLOW_RATIO < best.second
            if (ownIsSlow || isSilent(edge.host)) {
                preferredByCluster.update { it + (clusterKey to best.first.label) }
                return edge.rewrite(url, best.first)
            }
        } else if (isSilent(edge.host)) {
            // Even before any sibling has accumulated MIN_SAMPLES full completions, a known-silent
            // host must immediately yield to any usable observed sibling in the same cluster.
            siblings.firstOrNull { usable(it.host) }?.let { fallback ->
                return edge.rewrite(url, fallback)
            }
        }

        // Sticky cluster preferred host: once a host in this cluster has proven healthy,
        // keep routing unmeasured sibling links in the same cluster to it so all files
        // share the warm connection pool and never flip-flop mid-file. Ages out with [memory].
        val currentPreferredLabel = preferredByCluster.value[clusterKey]
        val currentPreferred = currentPreferredLabel?.let { label ->
            allCandidates.firstOrNull { it.label == label && usable(it.host) && medianRate(it.host) != null }
        }
        val preferredEdge = if (currentPreferred != null) {
            val prefRate = medianRate(currentPreferred.host)!!
            val beatenByBest = best != null && prefRate * SLOW_RATIO < best.second
            if (!beatenByBest) currentPreferred else best.first
        } else {
            if (currentPreferredLabel != null) {
                preferredByCluster.update { it - clusterKey }
            }
            allCandidates.firstOrNull { cand ->
                usable(cand.host) && (medianRate(cand.host) ?: 0L) >= GOOD_RATE_BYTES_PER_SEC
            }
        }
        if (preferredEdge != null) {
            if (preferredEdge.label != currentPreferredLabel) {
                preferredByCluster.update { it + (clusterKey to preferredEdge.label) }
            }
            val own = medianRate(edge.host)
            if (own == null && recentRates(edge.host).isEmpty()) {
                return if (preferredEdge.label == edge.label) url else edge.rewrite(url, preferredEdge)
            }
        }

        if (!mayExplore) return url
        val ownRate = medianRate(edge.host) ?: recentRates(edge.host).maxOrNull()
        if (ownRate != null && ownRate >= GOOD_RATE_BYTES_PER_SEC) return url
        val measured = (siblings + edge).count { medianRate(it.host) != null }
        if (measured >= EXPLORE_TARGET) return url
        // A sibling half measured goes first, or every candidate would stop at one sample and
        // the family would never reach the target that ends exploring
        val candidate = siblings
            .filter { usable(it.host) && recentRates(it.host).size < MIN_SAMPLES }
            .maxByOrNull { recentRates(it.host).size } ?: return url
        if (!claimExploreSlot()) return url
        return edge.rewrite(url, candidate)
    }

    private fun learn(label: String) {
        if (label in learned.value) return
        learned.update { if (label in it) it else it + label }
    }

    /** Records every CDN edge host present in [detail] as a live candidate for its cluster. */
    fun observe(detail: io.github.nihildigit.pikpak.FileDetail) {
        Edge.parse(detail.webContentLink)?.let { learn(it.label) }
        for (link in detail.links.values) Edge.parse(link.url)?.let { learn(it.label) }
        for (media in detail.medias) Edge.parse(media.link.url)?.let { learn(it.label) }
    }

    /** Same family and root, learned labels before seeds, never the host itself. */
    private fun siblingsOf(edge: Edge): List<Edge> =
        (learned.value + seedLabels).distinct()
            .filter { it != edge.label && Edge.familyOf(it) == edge.family }
            .map { Edge(it, edge.family, edge.root) }

    private fun usable(host: String): Boolean = !isSilent(host) && !isRefused(host)

    private fun isSilent(host: String): Boolean = silences.value[host]?.let { it.elapsedNow() < memory } == true

    private fun isRefused(host: String): Boolean = refusals.value[host]?.let { it.elapsedNow() < memory } == true

    /**
     * Null until the host has [MIN_SAMPLES] recent samples; one fast or slow attempt is not a
     * host's speed. The lower middle of an even count: on a direct line one host gave 1.38 and
     * then 0.06 MB/s, and a single lucky burst should not make it the destination for every read.
     */
    private fun medianRate(host: String): Long? {
        val recent = recentRates(host)
        if (recent.size < MIN_SAMPLES) return null
        return recent.sorted()[(recent.size - 1) / 2]
    }

    private fun recentRates(host: String): List<Long> =
        rates.value[host].orEmpty().filter { it.at.elapsedNow() < memory }.map { it.bytesPerSecond }

    private fun claimExploreSlot(): Boolean {
        while (true) {
            val last = lastExplore.value
            if (last != null && last.elapsedNow() < EXPLORE_INTERVAL) return false
            if (lastExplore.compareAndSet(last, timeSource.markNow())) return true
        }
    }

    /** A `<cluster>-<number>.<root>` edge host, rerouted only within the same `<cluster>` and `<root>`. */
    private class Edge(val label: String, val family: String, val root: String) {
        val host: String get() = "$label.$root"

        fun rewrite(url: String, to: Edge): String =
            url.substringBefore("://") + "://" + to.host + url.substringAfter("://").removePrefix(host)

        companion object {
            private val SHAPE = Regex("""^([a-z0-9]+(?:-[a-z0-9]+)+)-\d+$""")

            fun familyOf(label: String): String? = SHAPE.matchEntire(label)?.groupValues?.get(1)

            fun parse(url: String): Edge? {
                val host = hostOf(url) ?: return null
                // A port or credentials in the authority would not survive the rewrite; no link has carried either
                val authority = url.substringAfter("://").substringBefore('/').substringBefore('?')
                if (authority != host) return null
                val label = host.substringBefore('.')
                val root = host.substringAfter('.', "").ifEmpty { return null }
                val family = familyOf(label) ?: return null
                return Edge(label, family, root)
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
        val MAX_DEADLINE: Duration = 10.seconds

        const val LATENCY_RATIO = 2
        const val COLD_DEADLINE_RATIO = 2
        val BODY_SILENCE: Duration = 3.seconds
        val URGENT_BODY_SILENCE: Duration = 2.seconds
        const val MIN_LATENCY_SAMPLES = 8
        const val MAX_LATENCY_SAMPLES = 32

        /** Short against [DEFAULT_MEMORY]: a proxy switched on or off changes the line within seconds. */
        val LATENCY_MEMORY: Duration = 2.minutes

        /**
         * The fast hosts measured within about 2x of each other and the slow ones 20–30x below
         * them; four leaves the healthy spread alone and still catches the slow ones.
         */
        const val SLOW_RATIO = 4
        const val GOOD_RATE_BYTES_PER_SEC = 250L * 1024
        const val MAX_CONCURRENT_DRAINS = 2

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

        fun hostOf(url: String): String? =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').ifEmpty { null }
    }
}
