package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.SignedUrlGates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedUrlGatesTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelled signed connections retire before replacement admission`() = kotlinx.coroutines.test.runTest {
        val gates = SignedUrlGates()
        val holders = List(8) { async { gates.withSlot("https://cdn/f?s=retire") { awaitCancellation() } } }
        testScheduler.runCurrent()
        holders.forEach { it.cancel() }
        holders.forEach { it.join() }
        var entered = false
        val replacement = async { gates.withSlot("https://cdn/f?s=retire") { entered = true } }
        testScheduler.runCurrent()
        assertFalse(entered)
        testScheduler.advanceTimeBy(99)
        testScheduler.runCurrent()
        assertFalse(entered)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertTrue(entered)
        replacement.await()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `429 and 503 notify congestion immediately and retire only the rejected permit`() = kotlinx.coroutines.test.runTest {
        for (status in listOf(429, 503)) {
            val gates = SignedUrlGates()
            val reject = CompletableDeferred<Unit>()
            val holders = List(7) { async { gates.withSlot("https://cdn/f?s=throttle") { awaitCancellation() } } }
            var congestions = 0
            val rejected = async(io.github.nihildigit.pikpak.internal.SignedUrlCongestion { congestions++ }) {
                kotlin.test.assertFailsWith<PikPakException> {
                    gates.withSlot("https://cdn/f?s=throttle") {
                        reject.await()
                        throw PikPakException(-1, "busy", httpStatus = status)
                    }
                }
            }
            testScheduler.runCurrent()
            reject.complete(Unit)
            testScheduler.runCurrent()
            rejected.await()
            assertTrue(congestions == 1)
            var entered = false
            val replacement = async { gates.withSlot("https://cdn/f?s=throttle") { entered = true } }
            testScheduler.runCurrent()
            assertFalse(entered)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertTrue(entered)
            replacement.await()
            holders.forEach { it.cancel() }
            holders.forEach { it.join() }
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `foreground priority wins the contended signed gate`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val gates = SignedUrlGates()
            val release = List(8) { CompletableDeferred<Unit>() }
            val entered = List(8) { CompletableDeferred<Unit>() }
            val holders = List(8) { index ->
                async {
                    gates.withSlot("https://cdn/file?sign=priority") {
                        entered[index].complete(Unit)
                        release[index].await()
                    }
                }
            }
            entered.forEach { it.await() }
            val order = mutableListOf<String>()
            val background = async {
                gates.withSlot("https://sibling/file?sign=priority", priority = -10, order = 1L) {
                    order += "background"
                }
            }
            repeat(16) { yield() }
            val foreground = async {
                gates.withSlot("https://cdn/file?sign=priority", priority = 100, order = 2L) {
                    order += "foreground"
                }
            }
            repeat(16) { yield() }
            release[0].complete(Unit)
            foreground.await()
            background.await()
            kotlin.test.assertEquals(listOf("foreground", "background"), order)
            release.forEach { it.complete(Unit) }
            holders.awaitAll()
          }
    }

    @Test
    fun `sibling routes share eight slots and cancelled waiters do not leak`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val gates = SignedUrlGates()
            val started = List(8) { CompletableDeferred<Unit>() }
            val holders = List(8) { index ->
                async {
                    gates.withSlot("https://dl-z01a-$index.mypikpak.com/f?sign=s&expires=1") {
                        started[index].complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            started.forEach { it.await() }
            var entered = false
            val waiter = async {
                gates.withSlot("https://dl-z01a-9999.mypikpak.com/f?expires=1&sign=s") { entered = true }
            }
            repeat(16) { yield() }
            assertFalse(entered)
            waiter.cancel()
            waiter.join()
            holders.forEach { it.cancel() }
            holders.forEach { it.join() }
            List(8) { async { gates.withSlot("https://cdn/f?sign=s&expires=1") { true } } }
                .awaitAll().forEach { assertTrue(it) }
          }
    }
}
