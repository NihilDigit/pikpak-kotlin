package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.BandwidthLimiter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Account-wide planning. State is immutable: no callback runs inside a CAS update or cache lock acquisition. */
internal class TransferScheduler(
    private val accountBudget: Int,
    private val connectionBudget: Int,
    private val downloadLimiter: BandwidthLimiter,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    internal class Registration internal constructor(
        internal val connectionBudget: Int,
        internal val blockSize: Long,
        internal val onScheduleChanged: () -> Unit,
    )

    private data class CacheState(
        val foreground: Boolean = false,
        val hungry: Boolean = false,
        val background: Boolean = false,
        val span: Int = 1,
        val fastSamples: Int = 0,
        val spanSettled: Boolean = false,
        val workers: Int,
    )
    private data class Sample(val at: Duration, val bytes: Long)
    private data class Probe(
        val owner: Registration,
        val started: Duration,
        val baseline: Double,
        val previousWorkers: Int,
        val deliveredBytes: Long = 0,
        val resolved: Boolean = false,
    )
    private data class State(
        val caches: Map<Registration, CacheState> = emptyMap(),
        val learnedSpan: Int = 1,
        val interactiveUntil: Duration = Duration.ZERO,
        val samples: List<Sample> = emptyList(),
        val probe: Probe? = null,
    )
    private val origin = clock.markNow()
    private val state = MutableStateFlow(State())

    private inline fun change(transform: (State) -> State) {
        while (true) {
            val before = state.value
            val after = transform(before)
            if (before == after) return
            if (state.compareAndSet(before, after)) {
                if (before.caches != after.caches || before.interactiveUntil != after.interactiveUntil) {
                    after.caches.keys.forEach { it.onScheduleChanged() }
                }
                return
            }
        }
    }

    fun register(
        connectionBudget: Int = this.connectionBudget,
        blockSize: Long = SLOT_BYTES,
        onScheduleChanged: () -> Unit,
    ): Registration {
        val reg = Registration(connectionBudget.coerceAtLeast(1), blockSize.coerceAtLeast(1), onScheduleChanged)
        change { it.copy(caches = it.caches + (reg to CacheState(span = it.learnedSpan.coerceIn(1, 4), workers = reg.connectionBudget))) }
        return reg
    }

    fun unregister(reg: Registration) {
        change { it.copy(caches = it.caches - reg, probe = it.probe?.takeUnless { probe -> probe.owner === reg }) }
    }

    fun update(reg: Registration, hasForegroundDemand: Boolean, isHungry: Boolean, hasBackgroundDemand: Boolean) {
        change { state ->
            val cache = state.caches[reg] ?: return@change state
            state.copy(caches = state.caches + (reg to cache.copy(
                foreground = hasForegroundDemand, hungry = isHungry, background = hasBackgroundDemand,
            )))
        }
    }

    fun onInteractiveActivity() {
        val until = origin.elapsedNow() + 500.milliseconds
        change { it.copy(interactiveUntil = until) }
    }

    fun isAccountHungry(): Boolean = state.value.caches.values.any { it.hungry }

    fun workersFor(reg: Registration, isDurableOnly: Boolean): Int = workersFor(state.value, reg, isDurableOnly)

    private fun workersFor(state: State, reg: Registration, durable: Boolean): Int {
        val own = state.caches[reg] ?: return 0
        if (own.foreground && !durable) return reg.connectionBudget
        val backgrounds = state.caches.filterValues { it.background }
        val count = backgrounds.size.coerceAtLeast(1)
        val hungry = state.caches.values.any { it.hungry }
        val foreground = state.caches.values.any { it.foreground }
        val burst = origin.elapsedNow() < state.interactiveUntil
        val budget = when {
            burst -> (accountBudget / 2).coerceAtLeast(1)
            foreground -> maxOf(2, accountBudget / 2)
            else -> accountBudget
        }
        if (!foreground && !burst && !hungry && count >= 2) {
            return (accountBudget / count).coerceIn(1, reg.connectionBudget)
        }
        // In capped pools distribute the remainder without exceeding the account ceiling.
        val index = backgrounds.keys.indexOf(reg).coerceAtLeast(0)
        val share = budget / count + if (index < budget % count) 1 else 0
        return minOf(reg.connectionBudget, own.workers, if (hungry) 2 else Int.MAX_VALUE,
            share.coerceAtLeast(if (foreground || burst) 0 else 1))
    }

    fun spanFor(reg: Registration, workers: Int, durable: Boolean = false): Int {
        val snapshot = state.value
        val own = snapshot.caches[reg] ?: return 1
        val hungry = snapshot.caches.values.any { it.hungry }
        if (hungry && (!own.foreground || durable)) return 1
        val workerCap = when {
            workers <= 1 -> 16
            workers <= 2 -> 8
            else -> 4
        }
        val desired = own.span.coerceAtMost(workerCap)
        val rate = downloadLimiter.bytesPerSecond ?: return desired
        val slots = rate / workers.coerceAtLeast(1) / reg.blockSize
        return SPANS.lastOrNull { it <= desired && it <= slots } ?: 1
    }

    /** Only complete clean network samples train spans; throughput includes every delivered byte. */
    fun recordSample(reg: Registration, telemetry: ReadTelemetry, requestedBytes: Long, truncated: Boolean) {
        val now = origin.elapsedNow()
        change { state ->
            val own = state.caches[reg] ?: return@change state
            val samples = state.samples.filter { now - it.at <= 4.seconds } +
                if (telemetry.deliveredBytes > 0) listOf(Sample(now, telemetry.deliveredBytes)) else emptyList()
            var next = own
            val clean = telemetry.isClean && telemetry.deliveredBytes == requestedBytes && !truncated
            if (clean) {
                val ttfb = telemetry.timeToFirstByte ?: Duration.ZERO
                val bodyDuration = (telemetry.networkDuration - ttfb).coerceAtLeast(Duration.ZERO)
                val growCeiling = maxOf(600.milliseconds, ttfb * 2)
                val shrinkFloor = maxOf(1600.milliseconds, ttfb * 4)
                val highRttFloor = if (ttfb >= 80.milliseconds && bodyDuration <= 3200.milliseconds) 4 else 1
                next = when {
                    bodyDuration > shrinkFloor -> own.copy(span = maxOf(highRttFloor, own.span / 2), fastSamples = 0)
                    own.span < highRttFloor -> own.copy(span = highRttFloor, fastSamples = 0)
                    bodyDuration < growCeiling && requestedBytes >= own.span * reg.blockSize -> if (own.fastSamples >= 1)
                        own.copy(span = minOf(16, own.span * 2), fastSamples = 0)
                    else own.copy(fastSamples = own.fastSamples + 1)
                    else -> own.copy(fastSamples = 0)
                }
                next = next.copy(spanSettled = bodyDuration in growCeiling..shrinkFloor ||
                    (next.span == 16 && bodyDuration < growCeiling))
            }
            var caches = state.caches + (reg to next)
            var probe = state.probe
            if (probe?.owner === reg && !probe.resolved && next.span != own.span) {
                // TuneSpan has precedence: a changing request size invalidates the worker experiment.
                caches = caches + (reg to next.copy(workers = probe.previousWorkers))
                probe = probe.copy(resolved = true)
            }
            if (probe != null && !probe.resolved) {
                probe = probe.copy(deliveredBytes = probe.deliveredBytes + telemetry.deliveredBytes)
                if (now - probe.started >= 2.seconds) {
                    val measured = rate(probe.deliveredBytes, now - probe.started)
                    val owner = caches[probe.owner]
                    if (owner != null) {
                        // A downward probe wins if it preserves throughput; an upward one must improve it.
                        val accepted = if (owner.workers < probe.previousWorkers)
                            measured >= probe.baseline * 0.95 else measured >= probe.baseline * 1.05
                        if (!accepted) caches = caches + (probe.owner to owner.copy(workers = probe.previousWorkers))
                    }
                    // Retain ownership until finally releases it: an old owner cannot release a new probe.
                    probe = probe.copy(resolved = true)
                }
            }
            state.copy(caches = caches, learnedSpan = if (clean) next.span.coerceIn(1, 4) else state.learnedSpan, samples = samples, probe = probe)
        }
    }

    /** One account-wide worker experiment. Caller must release in NonCancellable finally and on close. */
    fun tryAcquireProbe(reg: Registration): Boolean {
        while (true) {
            val before = state.value
            val own = before.caches[reg] ?: return false
            if (before.probe != null || before.caches.values.any { it.hungry } || !own.spanSettled || own.fastSamples != 0) return false
            val now = origin.elapsedNow()
            val samples = before.samples.filter { now - it.at <= 4.seconds }
            if (samples.size < 2) return false
            val target = if (own.workers < reg.connectionBudget) own.workers + 1 else maxOf(1, own.workers - 1)
            if (target == own.workers) return false
            val after = before.copy(
                caches = before.caches + (reg to own.copy(workers = target)),
                probe = Probe(reg, now, throughput(samples, minOf(now, 4.seconds)), own.workers),
            )
            if (state.compareAndSet(before, after)) {
                after.caches.keys.forEach { it.onScheduleChanged() }
                return true
            }
        }
    }

    fun releaseProbe(reg: Registration) {
        change { state ->
            val probe = state.probe?.takeIf { it.owner === reg } ?: return@change state
            val own = state.caches[reg]
            state.copy(probe = null, caches = if (own == null || probe.resolved) state.caches else
                state.caches + (reg to own.copy(workers = probe.previousWorkers)))
        }
    }

    private fun throughput(samples: List<Sample>, elapsed: Duration): Double = rate(samples.sumOf { it.bytes }, elapsed)

    private fun rate(bytes: Long, elapsed: Duration): Double =
        bytes.toDouble() / elapsed.inWholeMilliseconds.coerceAtLeast(1) * 1000

    private companion object {
        const val SLOT_BYTES = 256L * 1024
        val SPANS = listOf(1, 2, 4, 8, 16)
    }
}
