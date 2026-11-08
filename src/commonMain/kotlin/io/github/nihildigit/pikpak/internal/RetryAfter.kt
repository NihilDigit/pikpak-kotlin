package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.PikPakException
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val PikPakException.isThrottlePressure: Boolean
    get() = httpStatus == 429 || httpStatus == 503

internal fun PikPakException.retryAfter(): Duration? =
    parseRetryAfter(headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value?.firstOrNull())

/** Share the HTTP layer's thirty-second bound across range and API retry owners. */
internal fun parseRetryAfter(raw: String?, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): Duration? {
    val value = raw?.trim() ?: return null
    value.toLongOrNull()?.let { return if (it < 0) null else it.coerceAtMost(30).seconds }
    return try {
        (value.fromHttpToGmtDate().timestamp - nowMillis).coerceIn(0L, 30_000L).milliseconds
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
