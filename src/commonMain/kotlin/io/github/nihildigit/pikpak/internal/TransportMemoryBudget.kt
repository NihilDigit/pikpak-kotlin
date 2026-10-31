package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared ownership credits for cache arrays, queued writes and the known RangeReader pump page.
 * Ktor/channel pools, engine/socket buffers, caller destinations and custom RangeSource
 * allocations are outside this allocator's ownership and are not included in its diagnostics.
 * Lowering the ceiling takes effect synchronously for admissions; existing owners drain normally.
 */
internal class TransportMemoryBudget(bytes: Long) {
    private data class State(val budget: Long, val used: Long = 0, val peak: Long = 0)
    private val state = MutableStateFlow(State(bytes.also { require(it > 0) }))
    internal val revision = MutableStateFlow(0L)
    private val reclaimers = MutableStateFlow<List<() -> Unit>>(emptyList())

    val budgetBytes: Long get() = state.value.budget
    val usedBytes: Long get() = state.value.used
    val peakBytes: Long get() = state.value.peak

    fun setBudgetBytes(bytes: Long) {
        require(bytes > 0)
        state.update { it.copy(budget = bytes) }
        revision.update { it + 1 }
        requestReclaim()
    }

    internal fun tryAcquire(bytes: Long): Boolean {
        require(bytes >= 0)
        while (true) {
            val old = state.value
            if (bytes > old.budget - old.used) return false
            val used = old.used + bytes
            if (state.compareAndSet(old, old.copy(used = used, peak = maxOf(old.peak, used)))) return true
        }
    }

    internal fun release(bytes: Long) {
        require(bytes >= 0)
        state.update {
            check(it.used >= bytes) { "transport memory released twice" }
            it.copy(used = it.used - bytes)
        }
        revision.update { it + 1 }
    }

    internal fun register(reclaim: () -> Unit) { reclaimers.update { it + reclaim } }
    internal fun unregister(reclaim: () -> Unit) { reclaimers.update { it - reclaim } }
    internal fun requestReclaim() { reclaimers.value.forEach { it() } }
}
