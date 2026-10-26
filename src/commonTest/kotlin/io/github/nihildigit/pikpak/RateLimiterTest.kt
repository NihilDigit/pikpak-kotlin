package io.github.nihildigit.pikpak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class RateLimiterTest {

    @Test
    fun `queued requests use descending priority and FIFO ties`() = runBlocking<Unit> {
        val limiter = RateLimiter(capacity = 1, refillPerSecond = 10.0)
        limiter.acquire()
        val order = mutableListOf<String>()
        val jobs = listOf("bulk1" to -10, "bulk2" to -10, "normal" to 0, "high1" to 5, "high2" to 5).map { (name, priority) ->
            async(start = CoroutineStart.UNDISPATCHED) { limiter.acquire(priority); order += name }
        }
        withTimeout(3_000) { jobs.awaitAll() }
        assertEquals(listOf("high1", "high2", "normal", "bulk1", "bulk2"), order)
    }

    @Test
    fun `cancelled reservations do not leave debt or stranded waiters`() = runBlocking<Unit> {
        val limiter = RateLimiter(capacity = 1, refillPerSecond = 10.0)
        limiter.acquire()
        val abandoned = List(50) { async(start = CoroutineStart.UNDISPATCHED) { limiter.acquire(10) } }
        val survivor = async(start = CoroutineStart.UNDISPATCHED) { limiter.acquire(-10) }
        abandoned.forEach { it.cancelAndJoin() }
        withTimeout(1_000) { survivor.await() }
        // Cancelling reservations must not mint tokens: the survivor spent the
        // next token, so this call still has to wait for a refill.
        val start = TimeSource.Monotonic.markNow()
        limiter.acquire()
        assertTrue(start.elapsedNow().inWholeMilliseconds >= 40)
    }

    @Test
    fun `HTTP requests inherit priority and restore the enclosing context`() = runBlocking<Unit> {
        val limiter = RateLimiter(capacity = 1, refillPerSecond = 10.0)
        val order = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            order += request.url.encodedPath
            respond("{}")
        })
        val c = PikPakClient("mock@x", "pw", sessionStore = InMemorySessionStore(), rateLimiter = limiter, httpClient = http)
        try {
            limiter.acquire()
            val bulk = async(start = CoroutineStart.UNDISPATCHED) {
                withApiPriority(-10) { c.http.requestRaw(HttpMethod.Get, "https://api.test/bulk") }
            }
            val normal = async(start = CoroutineStart.UNDISPATCHED) {
                c.http.requestRaw(HttpMethod.Get, "https://api.test/normal")
            }
            val high = async(start = CoroutineStart.UNDISPATCHED) {
                withApiPriority(10) {
                    assertEquals(10, currentCoroutineContext()[ApiRequestPriority]?.priority)
                    c.http.requestRaw(HttpMethod.Get, "https://api.test/high")
                }
                assertEquals(null, currentCoroutineContext()[ApiRequestPriority])
            }
            withTimeout(3_000) { bulk.await(); normal.await(); high.await() }
            assertEquals(listOf("/high", "/normal", "/bulk"), order)
        } finally { c.close(); http.close() }
    }

    @Test
    fun `default factory has twenty four initial tokens`() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()
        val limiter = RateLimiter.default()
        repeat(24) { limiter.acquire() }
        assertTrue(start.elapsedNow().inWholeMilliseconds < 100)
    }

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
        val limiter = RateLimiter.unlimited()
        val start = TimeSource.Monotonic.markNow()
        repeat(1000) { limiter.acquire() }
        val elapsed = start.elapsedNow().inWholeMilliseconds
        assertTrue(elapsed < 200, "Unlimited should complete 1000 acquires under 200ms, got ${elapsed}ms")
    }

    @Test
    fun `default factory creates independent buckets per call`() {
        // Each construction is a fresh bucket — verifies default() isn't a singleton
        // (important for per-client rate budgets).
        val a = RateLimiter.default()
        val b = RateLimiter.default()
        runBlocking {
            repeat(5) { a.acquire() } // drains a
            val start = TimeSource.Monotonic.markNow()
            repeat(5) { b.acquire() } // b still has 5 preloaded
            val elapsed = start.elapsedNow().inWholeMilliseconds
            assertTrue(elapsed < 50, "b should not have been drained by a (elapsed=${elapsed}ms)")
        }
    }

    @Test
    fun `concurrent waiters are staggered not thundering`() = runBlocking {
        // 3 tokens preloaded, 5/s refill → each additional token ~200ms apart.
        // 6 coroutines compete for 3+3 acquires. The 3 waiters must trail each
        // other by roughly the slot interval; a zero-gap batch would indicate
        // a thundering herd. Keep the gap threshold well below the ideal slot
        // (~200ms) so slow CI runners don't flake.
        val limiter = RateLimiter(capacity = 3, refillPerSecond = 5.0)
        val start = TimeSource.Monotonic.markNow()
        val completionTimes = coroutineScope {
            val deferreds = (1..6).map {
                async {
                    limiter.acquire()
                    start.elapsedNow().inWholeMilliseconds
                }
            }
            deferreds.awaitAll()
        }
        val waiterTimes = completionTimes.sorted().drop(3) // the 3 that had to wait
        val gaps = waiterTimes.zipWithNext { a, b -> b - a }
        for (gap in gaps) {
            assertTrue(gap >= 80, "waiters should be staggered, saw gap=${gap}ms in $waiterTimes")
        }
    }

    /**
     * A reservation spends its token immediately. Without that, refill kept
     * crediting tokens that queued waiters had already been promised, so a
     * caller arriving mid-queue found a "free" token and jumped ahead of them
     * — and the bucket issued up to twice the configured rate.
     */
    @Test
    fun `a latecomer cannot take a token already promised to a waiter`() = runBlocking<Unit> {
        val limiter = RateLimiter(capacity = 1, refillPerSecond = 5.0)
        limiter.acquire() // drains the bucket
        coroutineScope {
            // Five waiters now hold reservations at +200ms through +1000ms.
            val waiters = List(5) { async { limiter.acquire() } }
            delay(250)
            val start = TimeSource.Monotonic.markNow()
            limiter.acquire()
            val elapsed = start.elapsedNow().inWholeMilliseconds
            assertTrue(
                elapsed >= 100,
                "latecomer must queue behind the five reservations but waited only ${elapsed}ms",
            )
            waiters.awaitAll()
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
