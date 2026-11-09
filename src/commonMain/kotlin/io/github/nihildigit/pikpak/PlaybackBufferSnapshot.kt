package io.github.nihildigit.pikpak

import kotlin.time.Duration
import kotlin.time.TimeMark

/** Player evidence, not a byte-cursor estimate. Keep the original sample time when forwarding it. */
data class PlaybackBufferSnapshot(
    val generation: Long,
    val bufferedDuration: Duration?,
    val sampledAt: TimeMark,
    val mediaBytesPerSecond: Long? = null,
) {
    init {
        require(bufferedDuration == null || (bufferedDuration.isFinite() && bufferedDuration >= Duration.ZERO))
        require(mediaBytesPerSecond == null || mediaBytesPerSecond > 0)
    }
}
