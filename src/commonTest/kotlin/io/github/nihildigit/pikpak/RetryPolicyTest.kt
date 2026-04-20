package io.github.nihildigit.pikpak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {

    @Test
    fun `default exponential schedule`() {
        val p = RetryPolicy.Default
        assertEquals(200.milliseconds, p.delayFor(0))
        assertEquals(400.milliseconds, p.delayFor(1))
        assertEquals(800.milliseconds, p.delayFor(2))
        assertEquals(1_600.milliseconds, p.delayFor(3))
        assertEquals(3_200.milliseconds, p.delayFor(4))
    }

    @Test
    fun `delay is capped at maxDelay`() {
        val p = RetryPolicy.Default
        assertEquals(5.seconds, p.delayFor(5))
        assertEquals(5.seconds, p.delayFor(10))
        assertEquals(5.seconds, p.delayFor(20))
    }

    @Test
    fun `custom initial and multiplier honoured`() {
        val p = RetryPolicy(initialDelay = 100.milliseconds, multiplier = 3.0, maxDelay = 10.seconds)
        assertEquals(100.milliseconds, p.delayFor(0))
        assertEquals(300.milliseconds, p.delayFor(1))
        assertEquals(900.milliseconds, p.delayFor(2))
        assertEquals(2_700.milliseconds, p.delayFor(3))
    }

    @Test
    fun `None policy disables retry`() {
        assertEquals(1, RetryPolicy.None.maxAttempts)
    }

    @Test
    fun `attempt zero equals initial delay even with high multiplier`() {
        val p = RetryPolicy(initialDelay = 500.milliseconds, multiplier = 100.0)
        assertEquals(500.milliseconds, p.delayFor(0))
    }

    @Test
    fun `maxDelay floor respected when cap is lower than first delay`() {
        val p = RetryPolicy(initialDelay = 10.seconds, maxDelay = 1.seconds)
        assertTrue(p.delayFor(0) <= 1.seconds)
    }
}
