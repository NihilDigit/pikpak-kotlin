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
    seeds: Collection<String> = SEED_HOSTS,
) {
    private val silences = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val answers = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val refusals = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val rates = MutableStateFlow<Map<String, List<RateSample>>>(emptyMap())

    /** Host labels without the root domain, in the order links named them. Tried before [seedLabels]. */
    private val learned = MutableStateFlow<List<String>>(emptyList())
    private val seedLabels = seeds.toList()
    private val lastExplore = MutableStateFlow<TimeMark?>(null)

    private class RateSample(val bytesPerSecond: Long, val at: TimeMark)

    /** [url]'s host sent response headers. */
    fun answered(url: String) {
        val host = hostOf(url) ?: return
        answers.update { it + (host to timeSource.markNow()) }
    }

    /** [url]'s host sent nothing within the first-response deadline. */
    fun markSilent(url: String) {
        val host = hostOf(url) ?: return
        val othersAnswering = answers.value.any { (other, at) -> other != host && at.elapsedNow() < CORROBORATION }
        if (!othersAnswering) return
        val now = timeSource.markNow()
        silences.update { current -> current.filterValues { it.elapsedNow() < memory } + (host to now) }
    }

    fun isBad(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return isSilent(host)
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

        val siblings = siblingsOf(edge)
        val best = siblings
            .filter { usable(it.host) }
            .mapNotNull { sibling -> medianRate(sibling.host)?.let { sibling to it } }
            .maxByOrNull { it.second }
        if (best != null) {
            val own = medianRate(edge.host)
            val ownIsSlow = own != null && own * SLOW_RATIO < best.second
            if (ownIsSlow || isSilent(edge.host)) return edge.rewrite(url, best.first)
        }

        if (!mayExplore) return url
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

    /** A `dl-<family>-<number>.<root>` host, the only shape a link is rerouted between. */
    private class Edge(val label: String, val family: String, val root: String) {
        val host: String get() = "$label.$root"

        fun rewrite(url: String, to: Edge): String =
            url.substringBefore("://") + "://" + to.host + url.substringAfter("://").removePrefix(host)

        companion object {
            private val SHAPE = Regex("""^(dl-[a-z0-9]+)-\d+$""")

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

        /**
         * The fast hosts measured within about 2x of each other and the slow ones 20–30x below
         * them; four leaves the healthy spread alone and still catches the slow ones.
         */
        const val SLOW_RATIO = 4

        const val MIN_SAMPLES = 2
        const val MAX_SAMPLES = 6
        const val MIN_SAMPLE_BYTES = 256L * 1024

        /** Hosts per family with figures before exploring stops: enough that one slow host stands out. */
        const val EXPLORE_TARGET = 3

        /**
         * A sibling needs [MIN_SAMPLES] explorations before it can be compared, so this sets how
         * long a read stays on a slow host. Measured 2026-09-28, eight background reads pinned to
         * a slow host fetched 42 of their first 96 MiB there before leaving at 10 s, 24 at 5 s;
         * the 96 MiB took 47 s and 29 s, against 60 s for 24–56 MiB with steering off.
         */
        val EXPLORE_INTERVAL: Duration = 5.seconds

        /**
         * Hosts seen in PikPak links as of 2026-09-28, candidates only. Of the 26 `dl-z01a`
         * ones, 20 served a swapped link and 6 dropped the TLS handshake; a hard-coded list goes
         * stale, which is why a candidate is used only after it has delivered.
         */
        val SEED_HOSTS: List<String> = buildList {
            (listOf(14, 15, 16, 26, 27) + (41..55) + (59..64)).forEach { add("dl-z01a-" + it.toString().padStart(4, '0')) }
            listOf(
                621, 622, 624, 625, 822, 835, 858, 859, 860, 861, 862, 863, 868, 869, 1193, 1194, 1195, 1196,
                1531, 1532, 1533, 1542, 1543, 1551, 1552, 1553, 1554, 1555, 1556, 1557, 1558,
            ).forEach { add("dl-a10b-" + it.toString().padStart(4, '0')) }
        }

        fun hostOf(url: String): String? =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').ifEmpty { null }
    }
}
