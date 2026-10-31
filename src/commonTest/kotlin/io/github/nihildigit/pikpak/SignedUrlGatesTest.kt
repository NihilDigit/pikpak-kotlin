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
