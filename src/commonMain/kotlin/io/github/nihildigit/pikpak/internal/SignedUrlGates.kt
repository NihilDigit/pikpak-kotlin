package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RangeAdmission(private val onGranted: () -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(RangeAdmission) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RangeAdmission>
    val granted = kotlinx.coroutines.CompletableDeferred<Unit>()
    fun grant() {
        onGranted()
        granted.complete(Unit)
    }
}

/** Client-scoped admission for every range call, including probes and sibling routes. */
internal class SignedUrlGates {
    private class Entry {
        val gate = PriorityGate(8)
        var users = 0
    }

    companion object {
        fun identityOf(url: String): String {
            val pathAndQuery = url.substringAfter("://").substringAfter('/', "").substringBefore('#')
            val path = pathAndQuery.substringBefore('?')
            val query = pathAndQuery.substringAfter('?', "").split('&').sorted().joinToString("&")
            return "$path?$query"
        }
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withSlot(url: String, block: suspend () -> T): T {
        // Host steering preserves path and signature. Conservatively sharing identical paths
        // and queries across unrelated hosts is safe; host changes must never create quota.
        val identity = identityOf(url)
        val entry = mutex.withLock {
            entries.getOrPut(identity) { Entry() }.also { it.users++ }
        }
        var acquired = false
        try {
            val order = currentCoroutineContext()[RequestOrder]?.value ?: RequestOrder.Sequence.next()
            entry.gate.acquire(0, order)
            acquired = true
            currentCoroutineContext()[RangeAdmission]?.grant()
            return block()
        } finally {
            withContext(NonCancellable) {
                if (acquired) entry.gate.release()
                mutex.withLock {
                    entry.users--
                    if (entry.users == 0) entries.remove(identity)
                }
            }
        }
    }
}
