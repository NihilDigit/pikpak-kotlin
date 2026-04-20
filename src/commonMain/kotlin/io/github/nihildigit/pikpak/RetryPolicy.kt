package io.github.nihildigit.pikpak

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff retry policy applied by the HTTP layer to transient
 * network failures and HTTP 5xx / 429 responses. Captcha refresh and token
 * refresh have their own dedicated single-retry path and are not counted here.
 *
 * [jitterRatio] (0.0..1.0) randomises each delay downward by a fraction of
 * the computed value — useful when many coroutines share one client and a
 * synchronized 5xx would otherwise produce a thundering-herd retry. At 0.0
 * (default) delays are deterministic; at 1.0 they uniformly fill [0, d].
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 200.milliseconds,
    val maxDelay: Duration = 5.seconds,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.0,
) {
    init {
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in [0.0, 1.0], got $jitterRatio" }
    }

    fun delayFor(attempt: Int): Duration {
        val factor = multiplier.let { m ->
            var v = 1.0
            repeat(attempt) { v *= m }
            v
        }
        val raw = initialDelay.inWholeMilliseconds * factor
        val capped = raw.coerceAtMost(maxDelay.inWholeMilliseconds.toDouble())
        val jittered = if (jitterRatio > 0.0) {
            capped * (1.0 - jitterRatio * Random.nextDouble())
        } else capped
        return jittered.toLong().coerceAtLeast(0).milliseconds
    }

    companion object {
        val Default = RetryPolicy()
        val None = RetryPolicy(maxAttempts = 1)
    }
}
