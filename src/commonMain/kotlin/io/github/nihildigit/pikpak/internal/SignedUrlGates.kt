package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

internal class RangeAdmission(private val onGranted: () -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(RangeAdmission) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RangeAdmission>
    val granted = CompletableDeferred<Unit>()
    fun grant() {
        onGranted()
        granted.complete(Unit)
    }
}

internal class SignedRequestPriority(val value: Int) :
    kotlin.coroutines.AbstractCoroutineContextElement(SignedRequestPriority) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SignedRequestPriority>
}

internal class LiveSignedPriority(val flow: StateFlow<Int>) :
    kotlin.coroutines.AbstractCoroutineContextElement(LiveSignedPriority) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<LiveSignedPriority>
}

internal class SignedUrlCongestion(val onThrottled: () -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(SignedUrlCongestion) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SignedUrlCongestion>
}

/** Scheduling preemption preserves accepted bytes and carries no route penalty. */
internal class TransferPreempted(message: String = "higher priority byte demand") : CancellationException(message)

/** Explicit consumer/owner withdrawal preserves accepted bytes and carries no route penalty. */
internal class DemandWithdrawnException(message: String = "demand withdrawn") : CancellationException(message)

internal class FileAdmission(val identity: Any, val limit: Int, val onStats: (Int, Int) -> Unit = { _, _ -> }) :
    kotlin.coroutines.AbstractCoroutineContextElement(FileAdmission) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<FileAdmission>
}

/** One atomic account/file/signature ledger, including direct probes and sibling routes. */
internal class SignedUrlGates(private val accountLimit: Int = Int.MAX_VALUE) {
    @Volatile
    var inUse: Int = 0
        private set

    private class Request(
        val signature: String,
        val file: FileAdmission?,
        var priority: Int,
        val order: Long,
        val job: Job,
    ) {
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
        for (r in pending.sortedWith(
            compareByDescending<Request> { it.priority }
                .thenBy { if (it.priority >= 50) foregroundTurns[ownerOf(it)] ?: 0L else 0L }
                .thenBy { it.order },
        )) {
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
            } else if (r.priority >= 0) {
                fun canPreempt(victimPriority: Int): Boolean = when {
                    victimPriority < 0 -> true
                    r.priority >= 100 -> victimPriority < r.priority
                    r.priority >= 50 -> victimPriority in 1 until 10
                    else -> false
                }
                val signatureFull = active.count { it.signature == r.signature } >= (signatureLimits[r.signature] ?: 8)
                val fileFull = r.file != null && active.count { it.file?.identity === r.file.identity } >= r.file.limit
                active.filter {
                    !it.withdrawing && canPreempt(it.priority) &&
                        when {
                            signatureFull -> it.signature == r.signature
                            fileFull -> it.file?.identity === r.file.identity
                            else -> active.size >= accountLimit
                        }
                }
                    .minWithOrNull(compareBy<Request> { it.priority }.thenByDescending { it.order })
                    ?.let {
                        it.withdrawing = true
                        it.job.cancel(TransferPreempted())
                    }
            }
        }
        inUse = active.size
        (active + pending).forEach { notifyStats(it) }
    }

    private fun notifyStats(r: Request) {
        r.file?.let { file ->
            file.onStats(
                active.count { it.file?.identity === file.identity },
                pending.count { it.file?.identity === file.identity },
            )
        }
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
        val livePriority = currentCoroutineContext()[LiveSignedPriority]
        val explicitPriority = currentCoroutineContext()[SignedRequestPriority]
        val demand = if (livePriority == null) currentCoroutineContext()[TransferDemand] else null
        if (livePriority?.flow?.value == Int.MIN_VALUE) throw DemandWithdrawnException()
        if (demand?.isClosed == true) throw DemandWithdrawnException("demand closed")

        fun resolvePriority(): Int = when {
            livePriority != null -> livePriority.flow.value
            demand != null -> {
                val base = explicitPriority?.value ?: priority
                maxOf(base, demand.priority)
            }
            explicitPriority != null -> explicitPriority.value
            else -> priority
        }

        val requestOrder = order.takeIf { it != 0L }
            ?: currentCoroutineContext()[RequestOrder]?.value
            ?: RequestOrder.Sequence.next()
        val request = Request(
            identity,
            currentCoroutineContext()[FileAdmission],
            resolvePriority(),
            requestOrder,
            currentCoroutineContext()[Job]!!,
        )
        mutex.withLock {
            pending.add(request)
            dispatch()
        }
        val updates = when {
            livePriority != null -> launch {
                livePriority.flow.collect { next ->
                    if (next == Int.MIN_VALUE) {
                        request.job.cancel(DemandWithdrawnException())
                    } else {
                        mutex.withLock {
                            if (request.priority != next) {
                                request.priority = next
                                dispatch()
                            }
                        }
                    }
                }
            }
            demand != null -> launch {
                demand.priorityFlow.collect {
                    if (demand.isClosed) {
                        request.job.cancel(DemandWithdrawnException("demand closed"))
                    } else {
                        val next = resolvePriority()
                        mutex.withLock {
                            if (request.priority != next) {
                                request.priority = next
                                dispatch()
                            }
                        }
                    }
                }
            }
            else -> null
        }
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
                withContext(NonCancellable) {
                    mutex.withLock {
                        val current = minOf(signatureLimits[identity] ?: 8, active.count { it.signature == identity })
                        signatureLimits[identity] = (current - 1).coerceAtLeast(1)
                    }
                }
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
