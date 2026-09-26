package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How many [io.github.nihildigit.pikpak.StreamRole.FOREGROUND] readers the
 * account has open. Background readers throttle themselves while it is above
 * zero; see [io.github.nihildigit.pikpak.PikPakStreamReader].
 *
 * A count rather than a flag because foreground readers come and go
 * independently — a player switching files opens the next before closing the
 * last — and a flag would be cleared by whichever of the two left first.
 */
internal class ForegroundStreams {
    private val count = MutableStateFlow(0)

    val active: StateFlow<Int> = count.asStateFlow()

    fun enter() = count.update { it + 1 }

    fun leave() = count.update { (it - 1).coerceAtLeast(0) }
}
