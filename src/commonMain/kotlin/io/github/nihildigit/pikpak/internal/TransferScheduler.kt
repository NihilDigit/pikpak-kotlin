package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.BandwidthLimiter
import io.github.nihildigit.pikpak.ConcurrencyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

internal data class CacheDemand(val foreground: Boolean = false, val warm: Boolean = false,
    val background: Boolean = false, val hungry: Boolean = false, val urgent: Boolean = false)

/** Local producer ceilings. Actual account/file/signature admission is atomic in SignedUrlGates. */
internal class TransferScheduler(
    private val accountBudget: Int,
    private val connectionBudget: Int,
    private val downloadLimiter: BandwidthLimiter,
    internal val timeSource: TimeSource = TimeSource.Monotonic,
    concurrentAcceleration: Boolean = true,
    concurrencyMode: ConcurrencyMode = if (concurrentAcceleration) ConcurrencyMode.Adaptive() else ConcurrencyMode.Serial,
) {
    internal class Registration(val connectionBudget: Int, val blockSize: Long, val onScheduleChanged: () -> Unit)
    private val demands = MutableStateFlow<Map<Registration, CacheDemand>>(emptyMap())
    private val legacyUrgent = MutableStateFlow(false)
    @Volatile var concurrencyMode: ConcurrencyMode = concurrencyMode
        set(value) { field = value; wake() }
    var concurrentAcceleration: Boolean
        get() = concurrencyMode != ConcurrencyMode.Serial
        set(value) { concurrencyMode = if (value) ConcurrencyMode.Adaptive() else ConcurrencyMode.Serial }
    private fun wake() { demands.value.keys.forEach { it.onScheduleChanged() } }
    private fun change(transform: (Map<Registration, CacheDemand>) -> Map<Registration, CacheDemand>) {
        while (true) {
            val before = demands.value
            val after = transform(before)
            if (before == after) return
            if (demands.compareAndSet(before, after)) { wake(); return }
        }
    }
    fun register(connectionBudget: Int = this.connectionBudget, blockSize: Long = 262144, onScheduleChanged: () -> Unit): Registration =
        Registration(connectionBudget, blockSize, onScheduleChanged).also { reg -> change { it + (reg to CacheDemand()) } }
    fun unregister(reg: Registration) { change { it - reg }; reg.onScheduleChanged() }
    fun update(reg: Registration, hasForegroundDemand: Boolean, isHungry: Boolean, hasBackgroundDemand: Boolean) =
        reportDemand(reg, CacheDemand(foreground = hasForegroundDemand, hungry = isHungry, background = hasBackgroundDemand))
    fun reportDemand(reg: Registration, demand: CacheDemand) { change { if (reg in it) it + (reg to demand) else it } }
    fun setExternalUrgentForeground(urgent: Boolean) { if (legacyUrgent.value != urgent) { legacyUrgent.value = urgent; wake() } }
    fun onInteractiveActivity() = Unit
    fun isAccountHungry(): Boolean = legacyUrgent.value || demands.value.values.any { it.foreground && (it.hungry || it.urgent) }
    fun isUrgentHungry(): Boolean = legacyUrgent.value || demands.value.values.any { it.foreground && it.urgent }
    fun foregroundHungry(): Boolean = isAccountHungry()
    fun connectionAllowanceFor(reg: Registration): Int = minOf(accountBudget, CapacityPolicy.ceiling(concurrencyMode, reg.connectionBudget))
    fun workersFor(reg: Registration, isDurableOnly: Boolean): Int {
        val demand = demands.value[reg] ?: return 0
        if (!demand.foreground && !demand.warm && !demand.background) return 0
        if (isAccountHungry() && (isDurableOnly || !demand.foreground)) return 0
        return connectionAllowanceFor(reg)
    }
    // Ranges amortize HTTP setup; their unstarted tails are revocable, not exclusive reservations.
    fun spanFor(reg: Registration, workers: Int, durable: Boolean = false): Int =
        ((if (durable) 1024L * 1024 * 1024 else 2L * 1024 * 1024) / reg.blockSize).toInt().coerceAtLeast(1)
}
