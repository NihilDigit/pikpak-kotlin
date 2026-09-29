package io.github.nihildigit.pikpak

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How many bytes of leased objects may exist at once. A leased object takes
 * its full size of storage from the create until its delete lands, so an
 * account with little free space (6 GB on a free account) cannot lease several
 * episodes in parallel: the creates past the free space fail. Leases take
 * their size here before the create and give it back once the object is
 * deleted, so later leases wait for earlier deletes instead of failing.
 *
 * Waiters are served in order, so a large file is not starved by small ones.
 * A file larger than the whole budget still goes through, alone, once
 * everything else has been returned; waiting for room that can never exist
 * would hang it forever.
 */
class LeaseBudget(val capacityBytes: Long) {
    private val mutex = Mutex()
    private var inUse = 0L
    private val waiters = ArrayDeque<Waiter>()

    private class Waiter(val bytes: Long) {
        val granted = CompletableDeferred<Unit>()
    }

    /** Takes room for [bytes], suspending until it is free. Returns what was taken, for [release]. */
    suspend fun acquire(bytes: Long): Long {
        val want = bytes.coerceIn(0, capacityBytes)
        val waiter = mutex.withLock {
            if (waiters.isEmpty() && fits(want)) {
                inUse += want
                return want
            }
            Waiter(want).also(waiters::addLast)
        }
        try {
            waiter.granted.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    // Granted between the release and this cancellation: hand the room on.
                    if (!waiters.remove(waiter)) {
                        inUse -= want
                        wakeLocked()
                    }
                }
            }
            throw e
        }
        return want
    }

    /** Returns room taken by [acquire], and grants the waiters it now fits, in order. */
    suspend fun release(bytes: Long) = mutex.withLock {
        inUse = (inUse - bytes).coerceAtLeast(0)
        wakeLocked()
    }

    private fun wakeLocked() {
        while (waiters.isNotEmpty() && fits(waiters.first().bytes)) {
            val next = waiters.removeFirst()
            inUse += next.bytes
            next.granted.complete(Unit)
        }
    }

    private fun fits(want: Long) = inUse == 0L || inUse + want <= capacityBytes
}
