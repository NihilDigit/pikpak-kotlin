package io.github.nihildigit.pikpak

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * A [RangeSource] over a byte array, recording what was asked for.
 *
 * The stream reader's scheduling is the thing under test — block sizes, how
 * many requests are outstanding, what a seek cancels — and none of it needs a
 * PikPak account or a socket.
 */
internal class FakeRangeSource(
    private val content: ByteArray,
    /** Held for this long before any byte is produced, so a test can seek while reads are in flight. */
    private val latency: Duration = Duration.ZERO,
    /** Offsets whose first attempt throws, standing in for a link the CDN rejected. */
    failFirstAttemptAt: Set<Long> = emptySet(),
    /** Offsets that throw on every attempt, standing in for a range the CDN will never serve. */
    private val failAlwaysAt: Set<Long> = emptySet(),
) : RangeSource {
    data class Request(val start: Long, val length: Long, val priority: Int)

    private val mutex = Mutex()
    private val _requests = mutableListOf<Request>()
    private val _cancelled = mutableListOf<Request>()
    private val remainingFailures = failFirstAttemptAt.associateWith { 1 }.toMutableMap()
    private var active = 0
    private var peak = 0

    suspend fun requests(): List<Request> = snapshot { _requests.toList() }

    suspend fun cancelled(): List<Request> = snapshot { _cancelled.toList() }

    suspend fun peakConcurrency(): Int = snapshot { peak }

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T {
        val request = Request(start, length, priority)
        mutex.withLock {
            _requests += request
            active++
            peak = maxOf(peak, active)
        }
        try {
            if (latency > Duration.ZERO) delay(latency)
            val shouldFail = mutex.withLock {
                if (start in failAlwaysAt) return@withLock true
                val left = remainingFailures[start] ?: 0
                if (left <= 0) false else { remainingFailures[start] = left - 1; true }
            }
            if (shouldFail) throw IllegalStateException("simulated 403 at $start")
            val end = minOf(content.size.toLong(), start + length).toInt()
            return block(ByteReadChannel(content.copyOfRange(start.toInt(), end)))
        } catch (e: CancellationException) {
            record { _cancelled += request }
            throw e
        } finally {
            record { active-- }
        }
    }

    /**
     * Both bookkeeping sites run on the cancellation path, where taking a
     * suspending lock would throw before the record was made. A test polling
     * [cancelled] while a seek cancels reads would then see a list that never
     * fills, or one being appended to while it is copied.
     */
    private suspend fun record(body: () -> Unit) = withContext(NonCancellable) { mutex.withLock { body() } }

    private suspend fun <T> snapshot(body: () -> T): T = mutex.withLock { body() }
}
