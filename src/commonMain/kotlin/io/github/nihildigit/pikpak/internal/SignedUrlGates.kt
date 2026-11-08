package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.PikPakException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlin.time.Duration.Companion.milliseconds
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

internal class SignedRequestPriority(val value: Int) :
    kotlin.coroutines.AbstractCoroutineContextElement(SignedRequestPriority) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SignedRequestPriority>
}

internal class SignedUrlCongestion(val onThrottled: () -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(SignedUrlCongestion) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SignedUrlCongestion>
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

    suspend fun <T> withSlot(
        url: String,
        priority: Int = 0,
        order: Long = 0L,
        block: suspend () -> T,
    ): T {
        // Host steering preserves path and signature. Conservatively sharing identical paths
        // and queries across unrelated hosts is safe; host changes must never create quota.
        val identity = identityOf(url)
        val entry = mutex.withLock {
            entries.getOrPut(identity) { Entry() }.also { it.users++ }
        }
        var acquired = false
        var retiring = false
        try {
            val requestOrder = order.takeIf { it != 0L }
                ?: currentCoroutineContext()[RequestOrder]?.value ?: RequestOrder.Sequence.next()
            entry.gate.acquire(priority, requestOrder)
            acquired = true
            currentCoroutineContext()[RangeAdmission]?.grant()
            return block()
        } catch (t: Throwable) {
            retiring = t is CancellationException || (t is PikPakException && t.isThrottlePressure)
            if (t is PikPakException && t.isThrottlePressure) {
                currentCoroutineContext()[SignedUrlCongestion]?.onThrottled?.invoke()
            }
            throw t
        } finally {
            suspend fun release() {
                if (acquired) entry.gate.release()
                mutex.withLock {
                    entry.users--
                    if (entry.users == 0) entries.remove(identity)
                }
            }
            if (acquired && retiring) {
                // The local exchange ended before the CDN necessarily saw FIN. Retire its
                // signed permit briefly without delaying release of file/account permits.
                CoroutineScope(currentCoroutineContext() + NonCancellable).launch {
                    delay(100.milliseconds)
                    release()
                }
            } else withContext(NonCancellable) { release() }
        }
    }
}
