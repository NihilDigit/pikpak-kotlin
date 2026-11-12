package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.FetchClaimControl
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

/**
 * 1-shot active-slot handoff token for [io.github.nihildigit.pikpak.RangeAttempt.EndReason.OptimizedReplacement].
 *
 * Scoped via [SignedUrlGates.withReplacementHandoff] so any unconsumed reservation is guaranteed
 * to release under [NonCancellable] on scope exit, cancellation, or signature/file change.
 */
internal class ReplacementPermitHandoff(
    internal val gates: SignedUrlGates,
) : kotlin.coroutines.AbstractCoroutineContextElement(ReplacementPermitHandoff) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ReplacementPermitHandoff>

    @Volatile
    internal var armedSignature: String? = null

    internal var placeholder: SignedUrlGates.Request? = null

    fun armFor(nextUrl: String) {
        armedSignature = SignedUrlGates.identityOf(nextUrl)
    }

    fun disarm() {
        armedSignature = null
    }
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

    internal class Request(
        val signature: String,
        val file: FileAdmission?,
        val claimControl: FetchClaimControl?,
        var priority: Int,
        val order: Long,
        val job: Job?,
        val isIdleHandoff: Boolean = false,
    ) {
        val granted = CompletableDeferred<Unit>()
        var withdrawing = false
        var handoffRevoked = false
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

    private fun recordForegroundTurn(r: Request) {
        if (r.priority >= 50) {
            foregroundTurns[ownerOf(r)] = ++turn
            if (foregroundTurns.size > 1024) {
                foregroundTurns.entries.minByOrNull { it.value }?.key?.let { foregroundTurns.remove(it) }
            }
        }
    }

    /**
     * Single-block execution quantum: a request with `claimControl.inBlockQuantum == true` is
     * immune to same-tier preemption (`>= 100` vs `>= 100`, `50..99` vs `50..99`, `1..49` vs `1..49`).
     * Strictly higher service tiers (`100 > 50`, `>= 50 > 1..49`), `< 0` (`FinishInFlight`), or
     * explicit withdrawal still preempt immediately.
     */
    private fun canPreempt(challenger: Request, victim: Request): Boolean {
        val victimPriority = victim.priority
        if (victimPriority < 0) return true
        val inQuantum = victim.claimControl?.inBlockQuantum == true
        return when {
            challenger.priority >= 100 -> if (inQuantum) victimPriority < 100 else victimPriority < challenger.priority
            challenger.priority >= 50 -> victimPriority in 1 until 50
            else -> false
        }
    }

    private fun dispatch() {
        for (r in pending.sortedWith(
            compareByDescending<Request> { it.priority }
                .thenBy { if (it.priority >= 50) foregroundTurns[ownerOf(it)] ?: 0L else 0L }
                .thenBy { it.order },
        )) {
            if (fits(r)) {
                pending.remove(r)
                active.add(r)
                recordForegroundTurn(r)
                r.granted.complete(Unit)
            } else if (r.priority >= 0) {
                val signatureFull = active.count { it.signature == r.signature } >= (signatureLimits[r.signature] ?: 8)
                val fileFull = r.file != null && active.count { it.file?.identity === r.file.identity } >= r.file.limit
                val victim = active.filter {
                    !it.withdrawing && canPreempt(r, it) &&
                        when {
                            signatureFull -> it.signature == r.signature
                            fileFull -> it.file?.identity === r.file.identity
                            else -> active.size >= accountLimit
                        }
                }
                    .minWithOrNull(compareBy<Request> { it.priority }.thenByDescending { it.order })

                if (victim != null) {
                    victim.withdrawing = true
                    if (victim.isIdleHandoff) {
                        victim.handoffRevoked = true
                        active.remove(victim)
                        if (fits(r)) {
                            pending.remove(r)
                            active.add(r)
                            recordForegroundTurn(r)
                            r.granted.complete(Unit)
                        }
                    } else {
                        victim.job?.cancel(TransferPreempted())
                    }
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

    suspend fun <T> withReplacementHandoff(block: suspend (ReplacementPermitHandoff) -> T): T {
        val handoff = ReplacementPermitHandoff(this)
        return try {
            withContext(handoff) {
                block(handoff)
            }
        } finally {
            withContext(NonCancellable) {
                releaseHandoff(handoff)
            }
        }
    }

    suspend fun releaseHandoff(handoff: ReplacementPermitHandoff) {
        if (handoff.gates !== this) return
        mutex.withLock {
            handoff.armedSignature = null
            val held = handoff.placeholder
            handoff.placeholder = null
            if (held != null) {
                held.handoffRevoked = true
                if (active.remove(held)) {
                    dispatch()
                    notifyStats(held)
                }
            }
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
        val handoff = currentCoroutineContext()[ReplacementPermitHandoff]?.takeIf { it.gates === this@SignedUrlGates }
        val livePriority = currentCoroutineContext()[LiveSignedPriority]
        val explicitPriority = currentCoroutineContext()[SignedRequestPriority]
        val claimControl = currentCoroutineContext()[FetchClaimControl]
        val demand = if (livePriority == null) currentCoroutineContext()[TransferDemand] else null
        if (livePriority?.flow?.value == Int.MIN_VALUE) {
            if (handoff != null) releaseHandoff(handoff)
            throw DemandWithdrawnException()
        }
        if (demand?.isClosed == true) {
            if (handoff != null) releaseHandoff(handoff)
            throw DemandWithdrawnException("demand closed")
        }

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
        val fileAdmission = currentCoroutineContext()[FileAdmission]
        val request = Request(
            signature = identity,
            file = fileAdmission,
            claimControl = claimControl,
            priority = resolvePriority(),
            order = requestOrder,
            job = currentCoroutineContext()[Job]!!,
        )
        mutex.withLock {
            handoff?.armedSignature = null
            val held = handoff?.placeholder
            if (handoff != null) handoff.placeholder = null
            val sameIdentity = held != null &&
                !held.handoffRevoked &&
                held in active &&
                held.signature == identity &&
                ((held.file == null && fileAdmission == null) ||
                    (held.file != null && fileAdmission != null && held.file.identity === fileAdmission.identity))
            if (sameIdentity && held != null) {
                val idx = active.indexOf(held)
                active[idx] = request
                request.granted.complete(Unit)
                dispatch()
                notifyStats(request)
            } else {
                if (held != null) {
                    held.handoffRevoked = true
                    active.remove(held)
                }
                pending.add(request)
                dispatch()
                if (held != null) notifyStats(held)
            }
        }
        val updates = when {
            livePriority != null -> launch {
                livePriority.flow.collect { next ->
                    if (next == Int.MIN_VALUE) {
                        request.job?.cancel(DemandWithdrawnException())
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
                        request.job?.cancel(DemandWithdrawnException("demand closed"))
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
        var completedNormally = false
        try {
            request.granted.await()
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[RangeAdmission]?.grant()
            val result = block()
            completedNormally = true
            mutex.withLock {
                signatureLimits[identity]?.let { limit -> signatureLimits[identity] = (limit + 1).coerceAtMost(8) }
            }
            result
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
                    val armedSig = handoff?.armedSignature
                    handoff?.armedSignature = null
                    val previousHeld = handoff?.placeholder
                    if (previousHeld != null) {
                        previousHeld.handoffRevoked = true
                        active.remove(previousHeld)
                        handoff.placeholder = null
                    }
                    val withinLimits = active.size <= accountLimit &&
                        active.count { it.signature == request.signature } <= (signatureLimits[request.signature] ?: 8) &&
                        (request.file == null || active.count { it.file?.identity === request.file.identity } <= request.file.limit)
                    val canHoldForReplacement = completedNormally &&
                        handoff != null &&
                        armedSig != null &&
                        armedSig == request.signature &&
                        !request.withdrawing &&
                        request in active &&
                        withinLimits &&
                        livePriority?.flow?.value != Int.MIN_VALUE &&
                        demand?.isClosed != true
                    if (canHoldForReplacement && handoff != null) {
                        val placeholder = Request(
                            signature = request.signature,
                            file = request.file,
                            claimControl = request.claimControl,
                            priority = request.priority,
                            order = request.order,
                            job = null,
                            isIdleHandoff = true,
                        )
                        val idx = active.indexOf(request)
                        active[idx] = placeholder
                        handoff.placeholder = placeholder
                        dispatch()
                        if (placeholder.handoffRevoked) {
                            handoff.placeholder = null
                        }
                        notifyStats(request)
                    } else {
                        active.remove(request)
                        dispatch()
                        notifyStats(request)
                    }
                }
            }
        }
    }
}
