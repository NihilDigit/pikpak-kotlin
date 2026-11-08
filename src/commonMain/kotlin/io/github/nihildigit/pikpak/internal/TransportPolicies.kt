package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.ConcurrencyMode

/** Request-count decisions only. No demand, priority, routing or bandwidth shaping. */
internal object CapacityPolicy {
    fun ceiling(mode: ConcurrencyMode, registrationLimit: Int): Int = minOf(registrationLimit, when (mode) {
        ConcurrencyMode.Serial -> 1
        is ConcurrencyMode.Fixed -> mode.connections
        is ConcurrencyMode.Adaptive -> mode.ceiling
    })

    fun workers(mode: ConcurrencyMode, registrationLimit: Int, learned: Int): Int =
        minOf(ceiling(mode, registrationLimit), if (mode is ConcurrencyMode.Adaptive) learned else Int.MAX_VALUE)
}

/** Demand ranking and fair allocation are identical for every request-capacity strategy. */
internal object ImportancePolicy {
    fun sideReserve(accountBudget: Int, players: Int): Int =
        minOf(accountBudget / 2, (accountBudget - players).coerceAtLeast(0))

    fun backgroundReserve(hungry: Boolean): Int = if (hungry) 2 else 4

    fun warmCapacity(hungry: Boolean): Int = if (hungry) 1 else 2

    fun fairIndex(index: Int, owners: Int, seconds: Long): Int {
        if (owners == 0) return 0
        return (index.coerceAtLeast(0) - (seconds % owners).toInt() + owners) % owners
    }
}
