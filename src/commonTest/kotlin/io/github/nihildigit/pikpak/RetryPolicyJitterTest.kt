package io.github.nihildigit.pikpak

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyJitterTest {

    @Test
    fun `jitter zero matches deterministic delay`() {
        val p = RetryPolicy(
            initialDelay = 200.milliseconds,
            multiplier = 2.0,
            maxDelay = 5.seconds,
            jitterRatio = 0.0,
        )
        // Same as non-jittered RetryPolicy.Default.
        repeat(10) {
            assertEquals(200.milliseconds, p.delayFor(0), "jitter=0 must be deterministic")
        }
    }

    @Test
    fun `jitter half keeps delay within half-to-full window`() {
        val p = RetryPolicy(
            initialDelay = 200.milliseconds,
            multiplier = 2.0,
            maxDelay = 5.seconds,
            jitterRatio = 0.5,
        )
        val attempts = 200
        val base = 200.milliseconds
        val minAllowed = base.inWholeMilliseconds / 2 - 1 // -1 for rounding tolerance
        val maxAllowed = base.inWholeMilliseconds
        val samples = List(attempts) { p.delayFor(0).inWholeMilliseconds }
        for (v in samples) {
            assertTrue(
                v in minAllowed..maxAllowed,
                "delay $v out of [$minAllowed, $maxAllowed]",
            )
        }
        // With 200 samples at ratio 0.5, we should see spread: min < max.
        assertTrue(samples.min() < samples.max(), "expected non-deterministic spread, saw single value")
    }

    @Test
    fun `jitter one can produce zero-ish delays`() {
        val p = RetryPolicy(
            initialDelay = 200.milliseconds,
            multiplier = 2.0,
            maxDelay = 5.seconds,
            jitterRatio = 1.0,
        )
        val samples = List(200) { p.delayFor(0).inWholeMilliseconds }
        assertTrue(samples.min() <= 20, "with jitter=1 at least one sample should land near zero, min=${samples.min()}")
        assertTrue(samples.max() <= 200, "never exceeds the base delay")
    }

    @Test
    fun `jitter respects maxDelay cap`() {
        val p = RetryPolicy(
            initialDelay = 200.milliseconds,
            multiplier = 2.0,
            maxDelay = 1.seconds,
            jitterRatio = 0.5,
        )
        repeat(50) {
            val d = p.delayFor(10).inWholeMilliseconds
            assertTrue(d <= 1000, "jittered delay must never exceed maxDelay, got ${d}ms")
        }
    }

    @Test
    fun `invalid jitter ratio rejected at construction`() {
        try {
            RetryPolicy(jitterRatio = -0.1)
            throw AssertionError("expected IllegalArgumentException for negative ratio")
        } catch (_: IllegalArgumentException) {
        }
        try {
            RetryPolicy(jitterRatio = 1.5)
            throw AssertionError("expected IllegalArgumentException for ratio > 1")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun assertEquals(expected: Duration, actual: Duration, message: String) {
        if (expected != actual) throw AssertionError("$message: expected=$expected actual=$actual")
    }
}
