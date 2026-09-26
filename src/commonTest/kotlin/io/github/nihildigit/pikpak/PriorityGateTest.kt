package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.PriorityGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class PriorityGateTest {
    // Files warmed together each queue one block at a time, so served by arrival they take turns
    // block by block and all finish late; served by demand, the first file asked for finishes first.
    @Test
    fun `equal priorities go to the older demand rather than the earlier arrival`() = runBlocking<Unit> {
        val gate = PriorityGate(1)
        gate.acquire(priority = 5, order = 1)
        val granted = mutableListOf<String>()
        val bothQueued = CompletableDeferred<Unit>()

        val newer = launch {
            gate.acquire(priority = 5, order = 30)
            granted += "newer"
            gate.release()
        }
        yield()
        val older = launch {
            bothQueued.complete(Unit)
            gate.acquire(priority = 5, order = 20)
            granted += "older"
            gate.release()
        }
        bothQueued.await()
        yield()
        gate.release()
        newer.join()
        older.join()

        assertEquals(listOf("older", "newer"), granted)
    }
}
