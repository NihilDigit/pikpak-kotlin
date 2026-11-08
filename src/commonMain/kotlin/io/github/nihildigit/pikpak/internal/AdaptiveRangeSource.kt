package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.StateFlow

/** Potential concurrency and the currently usable signed-lane capacity. */
internal interface AdaptiveRangeSource {
    val maxConnections: Int
    val connectionLimit: StateFlow<Int>
    /** Evidence from the capacity controller, never inferred from low single-request rate. */
    fun updateCapacityEvidence(positiveScalingGain: Boolean) {}
    fun updateConnectionAllowance(allowance: Int)
}
