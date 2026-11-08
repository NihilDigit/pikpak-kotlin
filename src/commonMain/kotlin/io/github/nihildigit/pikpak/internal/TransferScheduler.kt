package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.BandwidthLimiter
import io.github.nihildigit.pikpak.ConcurrencyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A cache can simultaneously serve playback and durable work; warm alone is never hunger. */
internal data class CacheDemand(
    val foreground: Boolean = false,
    val warm: Boolean = false,
    val background: Boolean = false,
    val hungry: Boolean = false,
)

/** Account-wide planning. State is immutable: no callback runs inside a CAS update or cache lock acquisition. */
internal class TransferScheduler(
    private val accountBudget: Int,
    private val connectionBudget: Int,
    private val downloadLimiter: BandwidthLimiter,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    concurrentAcceleration: Boolean = true,
    concurrencyMode: ConcurrencyMode = if (concurrentAcceleration) ConcurrencyMode.Adaptive() else ConcurrencyMode.Serial,
) {
    var concurrentAcceleration: Boolean
        get() = concurrencyMode != ConcurrencyMode.Serial
        set(value) { concurrencyMode = if (value) ConcurrencyMode.Adaptive() else ConcurrencyMode.Serial }
    @Volatile
    var concurrencyMode: ConcurrencyMode = concurrencyMode
        set(value) {
            if (field == value) return
            field = value
            change { snapshot ->
                snapshot.copy(probe = null, samples = emptyList(), caches = snapshot.caches.mapValues { (reg, cache) ->
                    cache.copy(workers = minOf(4, reg.connectionBudget), direction = Direction.Up,
                        holdUntil = Duration.ZERO, positiveScalingGain = false)
                })
            }
            state.value.caches.keys.forEach { it.onScheduleChanged() }
        }

    internal class Registration internal constructor(
        internal val connectionBudget: Int,
        internal val blockSize: Long,
        internal val onScheduleChanged: () -> Unit,
    )

    private enum class Direction { Up, Down }

    private data class CacheState(
        val foreground: Boolean = false,
        val warm: Boolean = false,
        val hungry: Boolean = false,
        val background: Boolean = false,
        val span: Int = 1,
        val bodyRate: Double = 0.0,
        val latency: Duration = Duration.ZERO,
        val warmLatency: Duration? = null,
        val spanSettled: Boolean = false,
        val workers: Int,
        val direction: Direction = Direction.Up,
        val step: Int = 2,
        val holdUntil: Duration = Duration.ZERO,
        val positiveScalingGain: Boolean = false,
        val activeWorkers: Int? = null,
        val availableCapacity: Int = Int.MAX_VALUE,
        val probeCeiling: Int = 0,
        val congestionLimit: Int = Int.MAX_VALUE,
        val congestionUntil: Duration = Duration.ZERO,
    )
    private data class Sample(val owner: Registration, val at: Duration, val bytes: Long)
    private data class Probe(
        val owner: Registration,
        val started: Duration,
        val baseline: Double,
        val previousWorkers: Int,
        val direction: Direction,
        val baselineLatency: Duration?,
        val targetWorkers: Int,
        val measuring: Boolean = true,
        val warmLatency: Duration? = null,
        val deliveredBytes: Long = 0,
        val resolved: Boolean = false,
    )
    private data class State(
        val caches: Map<Registration, CacheState> = emptyMap(),
        val interactiveUntil: Duration = Duration.ZERO,
        val samples: List<Sample> = emptyList(),
        val probe: Probe? = null,
        val allocations: Map<Registration, Int> = emptyMap(),
    )
    private val origin = timeSource.markNow()
    private val state = MutableStateFlow(State())

    private inline fun change(transform: (State) -> State): State {
        while (true) {
            val before = state.value
            val now = origin.elapsedNow()
            // Reconcile elapsed time before consuming progress: an expired experiment
            // must not accept its last sample under an allocation it no longer owns.
            val current = reconcileAllocationChanges(before, before, now)
            val after = reconcileAllocationChanges(current, transform(current), now)
            if (before == after) return before
            if (state.compareAndSet(before, after)) {
                if (before.caches != after.caches || before.interactiveUntil != after.interactiveUntil ||
                    before.allocations != after.allocations) {
                    after.caches.keys.forEach { it.onScheduleChanged() }
                }
                return after
            }
        }
    }

    fun register(
        connectionBudget: Int = this.connectionBudget,
        blockSize: Long = SLOT_BYTES,
        onScheduleChanged: () -> Unit,
    ): Registration {
        val reg = Registration(connectionBudget.coerceAtLeast(1), blockSize.coerceAtLeast(1), onScheduleChanged)
        change { it.copy(caches = it.caches + (reg to CacheState(workers = minOf(4, reg.connectionBudget)))) }
        return reg
    }

    fun unregister(reg: Registration) {
        change { it.copy(caches = it.caches - reg, probe = it.probe?.takeUnless { probe -> probe.owner === reg }) }
    }

    fun update(reg: Registration, hasForegroundDemand: Boolean, isHungry: Boolean, hasBackgroundDemand: Boolean) {
        reportDemand(reg, CacheDemand(foreground = hasForegroundDemand,
            hungry = isHungry, background = hasBackgroundDemand))
    }

    fun reportDemand(reg: Registration, demand: CacheDemand) {
        change { state ->
            val cache = state.caches[reg] ?: return@change state
            state.copy(caches = state.caches + (reg to cache.copy(
                foreground = demand.foreground, warm = demand.warm,
                hungry = demand.foreground && demand.hungry, background = demand.background,
            )))
        }
    }

    /** Start a capacity experiment only once old requests have drained to the target. */
    fun reportActiveWorkers(reg: Registration, active: Int, availableCapacity: Int) {
        change { state ->
            val own = state.caches[reg] ?: return@change state
            val next = own.copy(activeWorkers = active, availableCapacity = availableCapacity.coerceAtLeast(1))
            var probe = state.probe
            if (probe?.owner === reg && !probe.resolved && !probe.measuring &&
                (if (probe.direction == Direction.Down) active <= probe.targetWorkers else active >= probe.targetWorkers)) {
                probe = probe.copy(measuring = true, started = origin.elapsedNow(), deliveredBytes = 0)
            }
            state.copy(caches = state.caches + (reg to next), probe = probe)
        }
    }

    fun onInteractiveActivity() {
        val until = origin.elapsedNow() + 500.milliseconds
        change { it.copy(interactiveUntil = until) }
    }

    fun isAccountHungry(): Boolean = state.value.caches.values.any { it.hungry }

    fun foregroundHungry(): Boolean = state.value.caches.values.any { it.foreground && it.hungry }

    /** Potential fair share, independent of a source's current signed-lane capacity. */
    fun connectionAllowanceFor(reg: Registration): Int = connectionAllowanceFor(change { it }, reg)

    private fun connectionAllowanceFor(state: State, reg: Registration): Int {
        return state.allocations[reg] ?: 0
    }

    /** One disjoint allocation ledger: mixed caches receive a single aggregate allowance. */
    private fun allowances(state: State, now: Duration = origin.elapsedNow()): Map<Registration, Int> {
        val foreground = state.caches.filterValues { it.foreground }.keys.toList()
        val warms = state.caches.filterValues { !it.foreground && it.warm }.keys.toList()
        val background = state.caches.filterValues { !it.foreground && !it.warm && it.background }.keys.toList()
        val hungry = state.caches.values.any { it.foreground && it.hungry }
        val burst = now < state.interactiveUntil
        val result = HashMap<Registration, Int>()
        var remaining = accountBudget
        fun distribute(owners: List<Registration>, budget: Int, cap: (Registration) -> Int): Int {
            var left = minOf(budget, remaining).coerceAtLeast(0)
            val initial = left
            val ordered = owners.sortedBy {
                ImportancePolicy.fairIndex(owners.indexOf(it), owners.size, now.inWholeSeconds)
            }
            while (left > 0) {
                var changed = false
                for (owner in ordered) {
                    val weight = if (owner in foreground && state.caches.getValue(owner).hungry) 2 else 1
                    repeat(weight) {
                        val count = result[owner] ?: 0
                        if (left > 0 && count < minOf(capacityCeiling(owner), cap(owner))) {
                            result[owner] = count + 1
                            left--
                            changed = true
                        }
                    }
                }
                if (!changed) break
            }
            val used = initial - left
            remaining -= used
            return used
        }
        val warmCap = ImportancePolicy.warmCapacity(hungry)
        if (foreground.isNotEmpty()) {
            // Every player gets a foothold before hunger weights spend extra slots.
            // The same rotating order handles budgets smaller than the player count.
            distribute(foreground, remaining) { 1 }
            // Side work gets a bounded reserve, never an equal share per cache.
            val sideBudget = ImportancePolicy.sideReserve(accountBudget, foreground.size)
            val bgBudget = minOf(sideBudget, ImportancePolicy.backgroundReserve(hungry))
            val bgUsed = distribute(background, bgBudget) {
                if (hungry && capacityCeiling(it) == 1) 0 else if (hungry) 2 else Int.MAX_VALUE
            }
            distribute(warms, minOf(2, sideBudget - bgUsed)) { warmCap }
            distribute(foreground, remaining) { Int.MAX_VALUE }
            // Unused playback capacity may be borrowed, within warm/emergency limits.
            distribute(warms, remaining) { warmCap }
            distribute(background, remaining) {
                if (hungry) if (capacityCeiling(it) == 1) 0 else 1 else Int.MAX_VALUE
            }
        } else {
            distribute(warms, minOf(4, if (background.isEmpty()) accountBudget else accountBudget / 2)) { warmCap }
            val budget = if (burst) minOf(remaining, (accountBudget / 2).coerceAtLeast(1)) else remaining
            distribute(background, budget) { if (burst && capacityCeiling(it) == 1) 0 else Int.MAX_VALUE }
            distribute(warms, remaining) { warmCap }
        }
        return result
    }

    private fun reconcileAllocationChanges(before: State, after: State, now: Duration): State {
        val oldShares = before.allocations
        val newShares = allowances(after, now)
        val affected = after.caches.keys.filter {
            val old = before.caches[it]
            val current = after.caches.getValue(it)
            oldShares[it] != newShares[it] || old == null || old.foreground != current.foreground ||
                old.warm != current.warm || old.background != current.background
        }.toSet()
        val activeProbe = after.probe?.takeIf { !it.resolved }
        val otherForegroundHungry = activeProbe != null &&
            after.caches.any { (reg, c) -> reg !== activeProbe.owner && c.foreground && c.hungry }
        val ownHungryDown = activeProbe != null && activeProbe.direction == Direction.Down &&
            after.caches[activeProbe.owner]?.let { it.foreground && it.hungry } == true
        val probe = activeProbe?.takeIf { it.owner in affected || otherForegroundHungry || ownHungryDown }
        if (affected.isEmpty() && probe == null) return after.copy(allocations = newShares)
        return after.copy(
            allocations = newShares,
            caches = after.caches.mapValues { (reg, own) ->
                var next = if (probe?.owner === reg) own.copy(workers = probe.previousWorkers) else own
                if ((newShares[reg] ?: 0) > (oldShares[reg] ?: 0) && now >= own.congestionUntil) {
                    next = next.copy(direction = Direction.Up, holdUntil = Duration.ZERO, positiveScalingGain = false)
                }
                next
            },
            samples = after.samples.filterNot { it.owner in affected },
            probe = if (probe != null) null else after.probe,
        )
    }

    fun workersFor(reg: Registration, isDurableOnly: Boolean): Int = workersFor(change { it }, reg, isDurableOnly)

    private fun workersFor(state: State, reg: Registration, durable: Boolean): Int {
        val own = state.caches[reg] ?: return 0
        val capacity = minOf(CapacityPolicy.workers(concurrencyMode, reg.connectionBudget, own.workers),
            if (origin.elapsedNow() < own.congestionUntil) own.congestionLimit else Int.MAX_VALUE)
        val hungry = state.caches.values.any { it.hungry }
        val share = connectionAllowanceFor(state, reg)
        if (own.foreground && !durable) return minOf(capacity, share)
        if (!own.foreground && own.warm) return minOf(capacity, share, if (hungry) 1 else 2)
        val durableShare = if (own.foreground && durable)
            if (own.hungry) 0 else maxOf(1, share / 2) else share
        return minOf(capacity, share, durableShare, if (hungry) 2 else Int.MAX_VALUE)
    }

    fun spanFor(reg: Registration, workers: Int, durable: Boolean = false): Int {
        val snapshot = change { it }
        val own = snapshot.caches[reg] ?: return 1
        val hungry = snapshot.caches.values.any { it.hungry }
        if (hungry && (!own.foreground || durable)) return 1
        if (!own.spanSettled) return 4
        val ceiling = if (durable) MAX_DURABLE_RANGE_BYTES else MAX_PLAYBACK_RANGE_BYTES
        val minSlots = if (!durable && own.foreground) MIN_PLAYBACK_SPAN_SLOTS else 1
        if (snapshot.probe?.let { it.owner === reg && !it.resolved } == true) {
            // Keep request size independent of the worker-count experiment, even while
            // completion samples continue to refresh the service model.
            return minOf(own.span, ceil(ceiling.toDouble() / reg.blockSize).toInt()).coerceAtLeast(minSlots)
        }
        val limitedRate = downloadLimiter.bytesPerSecond?.takeIf { durable }
            ?.toDouble()?.div(workers.coerceAtLeast(1))
        val rate = limitedRate?.let { minOf(own.bodyRate, it) } ?: own.bodyRate
        // 95% body duty cycle: S / r = 19 L. Range ownership is not a buffer reservation.
        val bytes = rate * own.latency.inWholeNanoseconds / 1e9 * 19.0
        return ceil(bytes.coerceIn(0.0, ceiling.toDouble()) / reg.blockSize)
            .toInt().coerceAtLeast(minSlots)
    }

    fun hasHighLatency(reg: Registration): Boolean {
        val own = state.value.caches[reg] ?: return false
        return own.spanSettled && own.latency >= 400.milliseconds
    }

    /** Explicit CDN pressure overrides hunger and aborts any optimistic capacity probe. */
    fun onThrottled(reg: Registration) {
        val now = origin.elapsedNow()
        change { state ->
            val own = state.caches[reg] ?: return@change state
            val current = minOf(CapacityPolicy.workers(concurrencyMode, reg.connectionBudget, own.workers),
                own.activeWorkers ?: Int.MAX_VALUE,
                if (now < own.congestionUntil) own.congestionLimit else Int.MAX_VALUE)
            val reduced = (current - 1).coerceAtLeast(1)
            state.copy(caches = state.caches + (reg to own.copy(
                workers = minOf(own.workers, reduced), congestionLimit = reduced,
                congestionUntil = now + 4.seconds,
                direction = Direction.Down, holdUntil = now + 4.seconds, positiveScalingGain = false,
            )), probe = state.probe?.takeUnless { it.owner === reg })
        }
    }

    /** Streaming byte deltas, never a request's accumulated bytes at completion. */
    fun recordProgress(reg: Registration, bytes: Long) {
        // Token-shaped wall-clock progress is not network-capacity evidence. Service
        // samples still learn from their explicitly unshaped active network duration.
        if (bytes <= 0 || downloadLimiter.bytesPerSecond != null) return
        val now = origin.elapsedNow()
        change { state ->
            if (reg !in state.caches) return@change state
            val recent = state.samples.filter { now - it.at < 4.seconds }
            val bucket = recent.indexOfLast { it.owner === reg && now - it.at < 100.milliseconds }
            val samples = if (bucket < 0) recent + Sample(reg, now, bytes) else
                recent.mapIndexed { index, sample -> if (index == bucket) sample.copy(bytes = sample.bytes + bytes) else sample }
            var probe = state.probe
            var caches = state.caches
            if (probe != null && !probe.resolved && probe.measuring && probe.owner === reg) {
                probe = probe.copy(deliveredBytes = probe.deliveredBytes + bytes)
                if (now - probe.started >= 2.seconds) {
                    val measured = rate(probe.deliveredBytes, now - probe.started)
                    val owner = caches[reg]!!
                    val accepted = when (probe.direction) {
                        Direction.Down -> !owner.hungry && measured >= probe.baseline * 0.93
                        // Long ranges can outlive the window. No completed warm sample
                        // is not evidence of queue inflation; any observed inflation vetoes gain.
                        Direction.Up -> measured >= probe.baseline * 1.10 &&
                            (probe.warmLatency == null || probe.warmLatency.let { warm ->
                                probe.baselineLatency?.let { warm <= it * 1.25 } == true
                            })
                    }
                    caches = caches + (reg to owner.copy(
                        workers = if (accepted) owner.workers else probe.previousWorkers,
                        direction = if (accepted) probe.direction else reverse(probe.direction),
                        holdUntil = now + if (accepted) 2.seconds else 4.seconds,
                        positiveScalingGain = accepted && probe.direction == Direction.Up,
                    ))
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
            val probing = state.probe?.let { it.owner === reg && !it.resolved } == true
            val next = if (clean && ttfb != null && body != null && body > Duration.ZERO) {
                val measuredRate = requestedBytes.toDouble() / body.inWholeNanoseconds * 1e9
                val bytes = measuredRate * ttfb.inWholeNanoseconds / 1e9 * 19.0
                own.copy(bodyRate = measuredRate, latency = ttfb,
                    warmLatency = if (telemetry.connectionReused == true) ttfb else own.warmLatency,
                    span = if (probing) own.span else
                        ceil(bytes.coerceIn(0.0, MAX_DURABLE_RANGE_BYTES.toDouble()) / reg.blockSize)
                            .toInt().coerceAtLeast(1), spanSettled = true)
            } else own
            val caches = state.caches + (reg to next)
            var probe = state.probe
            val sampleStarted = origin.elapsedNow() - telemetry.elapsed
            if (probe?.owner === reg && !probe.resolved && probe.measuring && clean && ttfb != null &&
                telemetry.connectionReused == true && sampleStarted >= probe.started) {
                probe = probe.copy(warmLatency = maxOf(probe.warmLatency ?: Duration.ZERO, ttfb))
            }
            state.copy(caches = caches, probe = probe)
        }
    }

    /** One account-wide worker experiment. Caller must release in NonCancellable finally and on close. */
    fun tryAcquireProbe(reg: Registration): Boolean {
        if (concurrencyMode !is ConcurrencyMode.Adaptive || downloadLimiter.bytesPerSecond != null) return false
        while (true) {
            val before = change { it }
            val own = before.caches[reg] ?: return false
            val otherHungry = before.caches.any { (other, c) -> other !== reg && c.hungry }
            if (before.probe != null || otherHungry || !own.spanSettled ||
                (!own.foreground && !own.background)) return false
            val now = origin.elapsedNow()
            if (now < own.holdUntil || now < own.congestionUntil) return false
            val samples = before.samples.filter { it.owner === reg && now - it.at < 4.seconds }
            if (samples.size < 2) return false
            val policyCapacity = workersFor(before.copy(caches = before.caches +
                (reg to own.copy(workers = reg.connectionBudget))), reg, !own.foreground)
            val ceiling = minOf(connectionAllowanceFor(before, reg), policyCapacity, own.availableCapacity)
            val effectiveWorkers = minOf(workersFor(before, reg, !own.foreground), own.availableCapacity)
            if (effectiveWorkers <= 0) return false
            if ((own.foreground || own.hungry) && effectiveWorkers >= ceiling) return false
            val minWorkers = if (own.foreground) minOf(4, ceiling.coerceAtLeast(1)) else 1
            val direction = when {
                own.hungry -> Direction.Up
                effectiveWorkers <= minWorkers -> Direction.Up
                effectiveWorkers >= ceiling -> Direction.Down
                ceiling > own.probeCeiling -> Direction.Up
                else -> own.direction
            }
            val target = (effectiveWorkers + if (direction == Direction.Up) minOf(own.step, effectiveWorkers) else -own.step)
                .coerceIn(1, ceiling.coerceAtLeast(1))
            if (target == effectiveWorkers) return false
            val after = before.copy(
                caches = before.caches + (reg to own.copy(workers = target, direction = direction, probeCeiling = ceiling)),
                probe = Probe(reg, now, throughput(samples, minOf(now, 4.seconds)), effectiveWorkers,
                    direction, own.warmLatency, target, measuring = own.activeWorkers == null),
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

    fun isProbeResolved(reg: Registration): Boolean =
        state.value.probe?.let { it.owner === reg && it.resolved } == true

    /** Measured upward gain without observed warm-latency inflation authorizes another signed lane. */
    fun hasPositiveScalingGain(reg: Registration): Boolean {
        val own = change { it }.caches[reg] ?: return false
        return concurrencyMode is ConcurrencyMode.Adaptive && own.positiveScalingGain && own.workers >= 8
    }

    private fun capacityCeiling(reg: Registration): Int = CapacityPolicy.ceiling(concurrencyMode, reg.connectionBudget)

    private fun reverse(direction: Direction): Direction =
        if (direction == Direction.Up) Direction.Down else Direction.Up

    private fun throughput(samples: List<Sample>, elapsed: Duration): Double = rate(samples.sumOf { it.bytes }, elapsed)

    private fun rate(bytes: Long, elapsed: Duration): Double =
        bytes.toDouble() / elapsed.inWholeMilliseconds.coerceAtLeast(1) * 1000

    private companion object {
        const val SLOT_BYTES = 256L * 1024
        const val MIN_PLAYBACK_SPAN_SLOTS = 8
        const val MAX_DURABLE_RANGE_BYTES = 1024L * 1024 * 1024
        const val MAX_PLAYBACK_RANGE_BYTES = 32L * 1024 * 1024
    }
}
