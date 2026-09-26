package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A counting semaphore that hands the next free slot to the highest-priority
 * waiter instead of the longest-waiting one.
 *
 * `kotlinx.coroutines.sync.Semaphore` is strictly FIFO, which is the wrong
 * order for playback: a read-ahead request queued a second ago must not stand
 * in front of the block the decoder is stalled on. Waiters at equal priority
 * are served by [RequestOrder], the moment their demand was made, and only
 * then by arrival.
 *
 * Slots are handed over directly rather than released and re-acquired — a
 * released slot would otherwise be taken by whichever coroutine happened to
 * call acquire at that instant, priority notwithstanding.
 */
internal class PriorityGate(private val capacity: Int) {
    private val mutex = Mutex()
    private val waiters = mutableListOf<Waiter>()
    private var sequence = 0L

    /** Slots currently held. Read without the lock; for reporting only. */
    var inUse: Int = 0
        private set

    /** Waiters currently queued. Read without the lock; for reporting only. */
    val queued: Int get() = waiters.size

    private class Waiter(
        val priority: Int,
        val order: Long,
        val sequence: Long,
        val granted: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    /** @param order see [RequestOrder]; lower goes first among equal priorities. */
    suspend fun acquire(priority: Int, order: Long = Long.MAX_VALUE) {
        val waiter = mutex.withLock {
            // Jumping a non-empty queue would starve waiters that are only
            // there because the gate was full a moment ago.
            if (inUse < capacity && waiters.isEmpty()) {
                inUse++
                return
            }
            Waiter(priority, order, sequence++).also { insert(it) }
        }
        try {
            waiter.granted.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val stillQueued = mutex.withLock { waiters.remove(waiter) }
                // Not in the queue means release() already handed us the slot
                // and nobody will ever use it; give it back.
                if (!stillQueued) release()
            }
            throw e
        }
    }

    /**
     * Safe to call from a cancelled coroutine. Taking the mutex is a suspension
     * point, and a cancelled caller that had to wait for it would throw before
     * the slot was returned; a slot lost that way is never recovered, and a
     * gate that has lost all of them hangs every later acquire.
     */
    suspend fun release() {
        withContext(NonCancellable) {
            mutex.withLock {
                val next = waiters.removeFirstOrNull()
                if (next == null) inUse-- else next.granted.complete(Unit)
            }
        }
    }

    private fun insert(waiter: Waiter) {
        val at = waiters.indexOfFirst { comesAfter(it, waiter) }
        if (at < 0) waiters.add(waiter) else waiters.add(at, waiter)
    }

    private fun comesAfter(queued: Waiter, arriving: Waiter): Boolean = when {
        queued.priority != arriving.priority -> queued.priority < arriving.priority
        queued.order != arriving.order -> queued.order > arriving.order
        else -> queued.sequence > arriving.sequence
    }
}
