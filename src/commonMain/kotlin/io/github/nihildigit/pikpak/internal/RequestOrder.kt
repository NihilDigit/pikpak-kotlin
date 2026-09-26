package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * When the demand behind a request was made, for breaking priority ties at the gates.
 *
 * Without it equal priorities were served in the order requests reached the gate, and a
 * worker only queues one block at a time, so eight files warmed together took turns block by
 * block: each got an eighth of the line and none finished early. Ordered by demand instead,
 * the first file asked for takes every slot it can use and is done before the second starts,
 * which is what a caller warming files in the order they will be played wants.
 *
 * Carried on the coroutine context rather than as a parameter because [io.github.nihildigit.pikpak.RangeSource]
 * is implemented outside this library, and the value has to cross such an implementation
 * untouched on its way from the stream reader down to the gate.
 */
internal class RequestOrder(val value: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestOrder>

    /**
     * One sequence for the process, not one per client or per file: a reader built on a bare
     * [io.github.nihildigit.pikpak.RangeSource] has no client to ask, and its orders still
     * have to compare with everyone else's at the account gate.
     */
    object Sequence {
        private val last = MutableStateFlow(0L)

        fun next(): Long = last.updateAndGet { it + 1 }
    }
}
