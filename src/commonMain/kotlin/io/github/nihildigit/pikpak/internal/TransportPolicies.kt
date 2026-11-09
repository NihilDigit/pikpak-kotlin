package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.ConcurrencyMode

/** Request-count decisions only. No demand, priority, routing or bandwidth shaping. */
internal object CapacityPolicy {
    fun ceiling(mode: ConcurrencyMode, registrationLimit: Int): Int = minOf(registrationLimit, when (mode) {
        ConcurrencyMode.Serial -> 1
        is ConcurrencyMode.Fixed -> mode.connections
        is ConcurrencyMode.Adaptive -> mode.ceiling
    })

}
