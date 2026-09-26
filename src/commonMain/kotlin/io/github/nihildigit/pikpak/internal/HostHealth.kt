package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * CDN edge hosts that recently said nothing to a request, shared by every file on the account.
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
 */
internal class HostHealth(
    private val memory: Duration = DEFAULT_MEMORY,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val silences = MutableStateFlow<Map<String, TimeMark>>(emptyMap())
    private val answers = MutableStateFlow<Map<String, TimeMark>>(emptyMap())

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
        val silentAt = silences.value[host] ?: return false
        return silentAt.elapsedNow() < memory
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

        fun hostOf(url: String): String? =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').ifEmpty { null }
    }
}
