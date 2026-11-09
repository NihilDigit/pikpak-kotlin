package io.github.nihildigit.pikpak

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark

/** A logical consumer shared by preparation and reads. Promotion preserves queued work. */
class TransferDemand(priority: Int = PikPakStreamReader.STREAMING_PRIORITY) : AbstractCoroutineContextElement(Key), AutoCloseable {
    companion object Key : CoroutineContext.Key<TransferDemand>
    private val mutablePriority = MutableStateFlow(priority)
    val priority: Int get() = mutablePriority.value
    internal val changes: StateFlow<Int> = mutablePriority.asStateFlow()
    internal class ByteDeadline(val endExclusive: Long, val at: TimeMark)
    internal val deadline = MutableStateFlow<ByteDeadline?>(null)
    /** Absolute monotonic deadline for a missing prefix; retries consume it once, without restarting its clock. */
    internal fun requireBytesBy(endExclusive: Long, at: TimeMark) {
        check(!isClosed) { "demand closed" }
        require(endExclusive >= 0)
        deadline.value = ByteDeadline(endExclusive, at)
    }
    internal var onChanged: ((Int?) -> Unit)? = null
    private val closed = MutableStateFlow(false)
    val isClosed: Boolean get() = closed.value
    fun updatePriority(priority: Int) {
        check(!closed.value) { "demand closed" }
        mutablePriority.value = priority
        onChanged?.invoke(priority)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            deadline.value = null
            onChanged?.invoke(null)
            mutablePriority.value = Int.MIN_VALUE
        }
    }
}
