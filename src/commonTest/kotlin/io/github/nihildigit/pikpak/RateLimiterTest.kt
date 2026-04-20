package io.github.nihildigit.pikpak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class RateLimiterTest {

    @Test
    fun `capacity tokens acquire immediately without suspension`() = runBlocking {
        val limiter = RateLimiter(capacity = 3, refillPerSecond = 0.001)
        val start = TimeSource.Monotonic.markNow()
        repeat(3) { limiter.acquire() }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        // Three tokens were preloaded at construction. None of these should have blocked.
        assertTrue(elapsed < 50, "3 initial tokens should not block (elapsed=${elapsed}ms)")
    }

    @Test
    fun `high refill rate keeps acquires cheap`() = runBlocking {
        val limiter = RateLimiter(capacity = 2, refillPerSecond = 1000.0)
        val start = TimeSource.Monotonic.markNow()
        repeat(10) { limiter.acquire() }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        // Worst case: 2 preloaded + 8 at 1000/s == ~8ms. Give generous headroom
        // for slow runners but still tight enough to fail on an obvious regression.
        assertTrue(elapsed < 500, "high refill should yield fast throughput, elapsed=${elapsed}ms")
    }

    @Test
    fun `slow refill forces suspension beyond initial capacity`() = runBlocking {
        // 1 token preloaded, 10 tokens/sec → each extra token ~100ms.
        val limiter = RateLimiter(capacity = 1, refillPerSecond = 10.0)
        limiter.acquire() // consume the preloaded token
        val start = TimeSource.Monotonic.markNow()
        limiter.acquire()
        val elapsed = start.elapsedNow().inWholeMilliseconds
        assertTrue(elapsed in 50..500, "second token should wait ~100ms, elapsed=${elapsed}ms")
    }

    @Test
    fun `unlimited limiter never blocks`() = runBlocking {
        val limiter = RateLimiter.Unlimited
        val start = TimeSource.Monotonic.markNow()
        repeat(1000) { limiter.acquire() }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        assertTrue(elapsed < 200, "Unlimited should complete 1000 acquires under 200ms, got ${elapsed}ms")
    }

    @Test
    fun `Default limiter is independent per access`() {
        // Each construction is a fresh bucket — verifies Default isn't a singleton
        // (important for per-client rate budgets).
        val a = RateLimiter.Default
        val b = RateLimiter.Default
        // Different instances: we can't compare directly without reflection, but
        // the key behavioural invariant is that consuming one doesn't drain the other.
        runBlocking {
            repeat(5) { a.acquire() } // drains a
            val start = TimeSource.Monotonic.markNow()
            repeat(5) { b.acquire() } // b still has 5 preloaded
            val elapsed = start.elapsedNow().inWholeMilliseconds
            assertTrue(elapsed < 50, "b should not have been drained by a (elapsed=${elapsed}ms)")
        }
    }

    @Test
    fun `concurrent acquires are serialized and eventually complete`() = runBlocking {
        val limiter = RateLimiter(capacity = 2, refillPerSecond = 50.0)
        coroutineScope {
            val jobs = List(10) { async { limiter.acquire(); 1 } }
            val results = jobs.awaitAll()
            assertEquals(10, results.sum())
        }
    }
}
