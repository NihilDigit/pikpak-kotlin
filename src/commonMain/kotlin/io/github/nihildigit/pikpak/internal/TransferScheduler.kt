package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.BandwidthLimiter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Account-wide planning. State is immutable: no callback runs inside a CAS update or cache lock acquisition. */
internal class TransferScheduler(
    private val accountBudget: Int,
    private val connectionBudget: Int,
    private val downloadLimiter: BandwidthLimiter,
    private val timeSource: TimeSource = TimeSource.Monotonic,
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
        val bodyRate: Double = 0.0,
        val latency: Duration = Duration.ZERO,
        val spanSettled: Boolean = false,
        val workers: Int,
    )
    private data class Sample(val owner: Registration, val at: Duration, val bytes: Long)
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
        val interactiveUntil: Duration = Duration.ZERO,
        val samples: List<Sample> = emptyList(),
        val probe: Probe? = null,
    )
    private val origin = timeSource.markNow()
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
        change { it.copy(caches = it.caches + (reg to CacheState(workers = reg.connectionBudget))) }
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

    /** Potential fair share, independent of a source's current signed-lane capacity. */
    fun connectionAllowanceFor(reg: Registration): Int = connectionAllowanceFor(state.value, reg)

    private fun connectionAllowanceFor(state: State, reg: Registration): Int {
        if (reg !in state.caches) return 0
        val active = state.caches.filterValues { it.foreground || it.background }
        val owners = if (reg in active) active.keys.toList() else active.keys.toList() + reg
        val index = owners.indexOf(reg)
        val share = accountBudget / owners.size + if (index < accountBudget % owners.size) 1 else 0
        return minOf(reg.connectionBudget, share)
    }

    fun workersFor(reg: Registration, isDurableOnly: Boolean): Int = workersFor(state.value, reg, isDurableOnly)

    private fun workersFor(state: State, reg: Registration, durable: Boolean): Int {
        val own = state.caches[reg] ?: return 0
        if (own.foreground && !durable) return minOf(own.workers, connectionAllowanceFor(state, reg))
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
            return minOf(own.workers, connectionAllowanceFor(state, reg))
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
        val ceiling = if (durable) MAX_DURABLE_RANGE_BYTES else MAX_PLAYBACK_RANGE_BYTES
        val limitedRate = downloadLimiter.bytesPerSecond?.takeIf { durable }
            ?.toDouble()?.div(workers.coerceAtLeast(1))
        val rate = limitedRate?.let { minOf(own.bodyRate, it) } ?: own.bodyRate
        // 95% body duty cycle: S / r = 19 L. Range ownership is not a buffer reservation.
        val bytes = rate * own.latency.inWholeNanoseconds / 1e9 * 19.0
        return ceil(bytes.coerceIn(0.0, ceiling.toDouble()) / reg.blockSize)
            .toInt().coerceAtLeast(1)
    }

    /** Streaming byte deltas, never a request's accumulated bytes at completion. */
    fun recordProgress(reg: Registration, bytes: Long) {
        if (bytes <= 0) return
        val now = origin.elapsedNow()
        change { state ->
            if (reg !in state.caches) return@change state
            val recent = state.samples.filter { now - it.at <= 4.seconds }
            val bucket = recent.indexOfLast { it.owner === reg && now - it.at < 100.milliseconds }
            val samples = if (bucket < 0) recent + Sample(reg, now, bytes) else
                recent.mapIndexed { index, sample -> if (index == bucket) sample.copy(bytes = sample.bytes + bytes) else sample }
            var probe = state.probe
            var caches = state.caches
            if (probe != null && !probe.resolved && probe.owner === reg) {
                probe = probe.copy(deliveredBytes = probe.deliveredBytes + bytes)
                if (now - probe.started >= 2.seconds) {
                    val measured = rate(probe.deliveredBytes, now - probe.started)
                    val owner = caches[reg]!!
                    val accepted = if (owner.workers < probe.previousWorkers)
                        measured >= probe.baseline * 0.95 else measured >= probe.baseline * 1.05
                    if (!accepted) caches = caches + (reg to owner.copy(workers = probe.previousWorkers))
                    probe = probe.copy(resolved = true)
                }
            }
            state.copy(samples = samples, caches = caches, probe = probe)
        }
    }

    /** Completed uncensored reads train the service model, including successful steering. */
    fun recordSample(reg: Registration, telemetry: ReadTelemetry, requestedBytes: Long, truncated: Boolean) {
        change { state ->
            val own = state.caches[reg] ?: return@change state
            val ttfb = telemetry.timeToFirstByte
            val clean = telemetry.isClean && telemetry.deliveredBytes == requestedBytes && !truncated
            val body = ttfb?.let { telemetry.networkDuration - it }
            val next = if (clean && ttfb != null && body != null && body > Duration.ZERO) {
                val measuredRate = requestedBytes.toDouble() / body.inWholeNanoseconds * 1e9
                val bytes = measuredRate * ttfb.inWholeNanoseconds / 1e9 * 19.0
                own.copy(bodyRate = measuredRate, latency = ttfb,
                    span = ceil(bytes.coerceIn(0.0, MAX_DURABLE_RANGE_BYTES.toDouble()) / reg.blockSize)
                        .toInt().coerceAtLeast(1), spanSettled = true)
            } else own
            var caches = state.caches + (reg to next)
            var probe = state.probe
            if (probe?.owner === reg && !probe.resolved && next.span != own.span) {
                // TuneSpan has precedence: a changing request size invalidates the worker experiment.
                caches = caches + (reg to next.copy(workers = probe.previousWorkers))
                probe = probe.copy(resolved = true)
            }
            state.copy(caches = caches, probe = probe)
        }
    }

    /** One account-wide worker experiment. Caller must release in NonCancellable finally and on close. */
    fun tryAcquireProbe(reg: Registration): Boolean {
        while (true) {
            val before = state.value
            val own = before.caches[reg] ?: return false
            if (before.probe != null || before.caches.values.any { it.hungry } || !own.spanSettled) return false
            val now = origin.elapsedNow()
            val samples = before.samples.filter { it.owner === reg && now - it.at <= 4.seconds }
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
        const val MAX_DURABLE_RANGE_BYTES = 1024L * 1024 * 1024
        const val MAX_PLAYBACK_RANGE_BYTES = 32L * 1024 * 1024
    }
}
