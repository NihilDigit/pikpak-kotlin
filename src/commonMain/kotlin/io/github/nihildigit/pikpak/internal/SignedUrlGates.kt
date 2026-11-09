package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.PikPakException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import io.github.nihildigit.pikpak.TransferDemand
import kotlin.concurrent.Volatile

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

/** Scheduling withdrawal preserves accepted bytes and carries no route penalty. */
internal class TransferPreempted : CancellationException("higher priority byte demand")

internal class FileAdmission(val identity: Any, val limit: Int, val onStats: (Int, Int) -> Unit = { _, _ -> }) :
    kotlin.coroutines.AbstractCoroutineContextElement(FileAdmission) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<FileAdmission>
}

/** One atomic account/file/signature ledger, including direct probes and sibling routes. */
internal class SignedUrlGates(private val accountLimit: Int = Int.MAX_VALUE) {
    @Volatile var inUse: Int = 0
        private set
    private class Request(val signature: String, val file: FileAdmission?, var priority: Int,
        val order: Long, val job: Job) {
        val granted = CompletableDeferred<Unit>()
        var withdrawing = false
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
    private val pending = mutableListOf<Request>()
    private val active = mutableListOf<Request>()
    private val signatureLimits = mutableMapOf<String, Int>()
    private val foregroundTurns = LinkedHashMap<Any, Long>()
    private var turn = 0L

    private fun ownerOf(r: Request): Any = r.file?.identity ?: r.signature

    private fun fits(r: Request): Boolean = active.size < accountLimit &&
        active.count { it.signature == r.signature } < (signatureLimits[r.signature] ?: 8) &&
        (r.file == null || active.count { it.file?.identity === r.file.identity } < r.file.limit)

    private fun dispatch() {
        for (r in pending.sortedWith(compareByDescending<Request> { it.priority }
            .thenBy { if (it.priority >= 50) foregroundTurns[ownerOf(it)] ?: 0L else 0L }
            .thenBy { it.order })) {
            if (fits(r)) {
                pending.remove(r)
                active.add(r)
                if (r.priority >= 50) {
                    foregroundTurns[ownerOf(r)] = ++turn
                    if (foregroundTurns.size > 1024) {
                        foregroundTurns.entries.minByOrNull { it.value }?.key?.let { foregroundTurns.remove(it) }
                    }
                }
                r.granted.complete(Unit)
            } else if (r.priority >= 50) {
                val signatureFull = active.count { it.signature == r.signature } >= (signatureLimits[r.signature] ?: 8)
                val fileFull = r.file != null && active.count { it.file?.identity === r.file.identity } >= r.file.limit
                active.filter { !it.withdrawing && it.priority < r.priority &&
                    when {
                        signatureFull -> it.signature == r.signature
                        fileFull -> it.file?.identity === r.file?.identity
                        else -> active.size >= accountLimit
                    } }
                    .minByOrNull { it.priority }?.let {
                        it.withdrawing = true
                        it.job.cancel(TransferPreempted())
                    }
            }
        }
        inUse = active.size
        (active + pending).forEach { notifyStats(it) }
    }

    private fun notifyStats(r: Request) {
        r.file?.let { file -> file.onStats(active.count { it.file?.identity === file.identity },
            pending.count { it.file?.identity === file.identity }) }
    }

    suspend fun <T> withSlot(
        url: String,
        priority: Int = 0,
        order: Long = 0L,
        block: suspend () -> T,
    ): T = coroutineScope {
        // Host steering preserves path and signature. Conservatively sharing identical paths
        // and queries across unrelated hosts is safe; host changes must never create quota.
        val identity = identityOf(url)
        val demand = currentCoroutineContext()[TransferDemand]
        if (demand?.isClosed == true) throw CancellationException("demand closed")
        val requestOrder = order.takeIf { it != 0L }
            ?: currentCoroutineContext()[RequestOrder]?.value ?: RequestOrder.Sequence.next()
        val request = Request(identity, currentCoroutineContext()[FileAdmission], demand?.priority ?: priority,
            requestOrder, currentCoroutineContext()[Job]!!)
        mutex.withLock { pending.add(request); dispatch() }
        val updates = demand?.let { owner -> launch {
            owner.changes.collect {
                if (owner.isClosed) request.job.cancel(CancellationException("demand closed"))
                else mutex.withLock { request.priority = owner.priority; dispatch() }
            }
        } }
        try {
            request.granted.await()
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[RangeAdmission]?.grant()
            block().also {
                mutex.withLock {
                    signatureLimits[identity]?.let { limit -> signatureLimits[identity] = (limit + 1).coerceAtMost(8) }
                }
            }
        } catch (t: Throwable) {
            if (t is PikPakException && t.isThrottlePressure) {
                withContext(NonCancellable) { mutex.withLock {
                    val current = minOf(signatureLimits[identity] ?: 8, active.count { it.signature == identity })
                    signatureLimits[identity] = (current - 1).coerceAtLeast(1)
                } }
                currentCoroutineContext()[SignedUrlCongestion]?.onThrottled?.invoke()
            }
            throw t
        } finally {
            updates?.cancel()
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.remove(request)
                    active.remove(request)
                    dispatch()
                    notifyStats(request)
                }
            }
        }
    }
}
