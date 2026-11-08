package io.github.nihildigit.pikpak

/** Per-file request capacity. Importance, rate shaping and CDN routing are independent. */
sealed interface ConcurrencyMode {
    data object Serial : ConcurrencyMode

    data class Fixed(val connections: Int) : ConcurrencyMode {
        init { require(connections >= 1) }
    }

    data class Adaptive(val ceiling: Int = 16) : ConcurrencyMode {
        init { require(ceiling >= 1) }
    }
}
