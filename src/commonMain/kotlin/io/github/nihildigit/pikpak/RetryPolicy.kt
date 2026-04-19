package io.github.nihildigit.pikpak

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff retry policy applied by the HTTP layer to transient
 * network failures and HTTP 5xx / 429 responses. Captcha refresh and token
 * refresh have their own dedicated single-retry path and are not counted here.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 200.milliseconds,
    val maxDelay: Duration = 5.seconds,
    val multiplier: Double = 2.0,
) {
    fun delayFor(attempt: Int): Duration {
        val factor = multiplier.let { m ->
            var v = 1.0
            repeat(attempt) { v *= m }
            v
        }
        val raw = initialDelay.inWholeMilliseconds * factor
        val capped = raw.coerceAtMost(maxDelay.inWholeMilliseconds.toDouble())
        return capped.toLong().milliseconds
    }

    companion object {
        val Default = RetryPolicy()
        val None = RetryPolicy(maxAttempts = 1)
    }
}
