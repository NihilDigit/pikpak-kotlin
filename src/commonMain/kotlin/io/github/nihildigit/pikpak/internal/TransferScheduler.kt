package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.BandwidthLimiter
import io.github.nihildigit.pikpak.ConcurrencyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** Local producer ceilings. Actual account/file/signature admission and preemption live in [SignedUrlGates]. */
internal class TransferScheduler(
    private val accountBudget: Int,
    private val connectionBudget: Int,
    private val downloadLimiter: BandwidthLimiter,
    internal val timeSource: TimeSource = TimeSource.Monotonic,
    concurrentAcceleration: Boolean = true,
    concurrencyMode: ConcurrencyMode = if (concurrentAcceleration) ConcurrencyMode.Adaptive() else ConcurrencyMode.Serial,
) {
    internal class Registration(
        val connectionBudget: Int,
        val blockSize: Long,
        val onScheduleChanged: () -> Unit,
    )

    private val registrations = MutableStateFlow<Set<Registration>>(emptySet())

    @Volatile
    var concurrencyMode: ConcurrencyMode = concurrencyMode
        set(value) {
            field = value
            wake()
        }

    var concurrentAcceleration: Boolean
        get() = concurrencyMode != ConcurrencyMode.Serial
        set(value) {
            concurrencyMode = if (value) ConcurrencyMode.Adaptive() else ConcurrencyMode.Serial
        }

    private fun wake() {
        registrations.value.forEach { it.onScheduleChanged() }
    }

    fun register(
        connectionBudget: Int = this.connectionBudget,
        blockSize: Long = 262144,
        onScheduleChanged: () -> Unit,
    ): Registration = Registration(connectionBudget, blockSize, onScheduleChanged).also { reg ->
        while (true) {
            val before = registrations.value
            val after = before + reg
            if (registrations.compareAndSet(before, after)) break
        }
    }

    fun unregister(reg: Registration) {
        while (true) {
            val before = registrations.value
            if (reg !in before) break
            if (registrations.compareAndSet(before, before - reg)) break
        }
        reg.onScheduleChanged()
    }

    fun connectionAllowanceFor(reg: Registration): Int =
        minOf(accountBudget, CapacityPolicy.ceiling(concurrencyMode, reg.connectionBudget))

    // Non-durable streaming fetches remain 1 block in Serial mode and allow up to 1 MiB (4 × 256 KiB) otherwise.
    fun spanFor(reg: Registration, workers: Int, durable: Boolean = false): Int {
        if (!durable && concurrencyMode == ConcurrencyMode.Serial) return 1
        val ceilingBytes = if (durable) 1024L * 1024 * 1024 else 1024L * 1024
        return (ceilingBytes / reg.blockSize).toInt().coerceAtLeast(1)
    }
}
