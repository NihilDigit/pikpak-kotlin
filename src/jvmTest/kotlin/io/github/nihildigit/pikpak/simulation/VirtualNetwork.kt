package io.github.nihildigit.pikpak.simulation

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.IOException
import kotlin.math.min

internal const val MIB = 1024L * 1024

/** Offset-dependent content catches swapped ranges, not just length and uniform-byte errors. */
internal fun contentByte(offset: Long): Byte {
    var x = offset xor (offset ushr 17) xor 0x5deece66dL
    x = (x xor (x ushr 13)) * 0x45d9f3bL
    return (x xor (x ushr 23)).toByte()
}
internal fun content(offset: Long, size: Int) = ByteArray(size) { contentByte(offset + it) }
internal fun verifyContent(offset: Long, bytes: ByteArray) {
    for (i in bytes.indices) check(bytes[i] == contentByte(offset + i)) { "corruption at ${offset + i}" }
}

internal data class NetworkProfile(
    val rttMs: Long = 30,
    val ttfbMs: Long = 30,
    val capacity: Long = 125_000_000,
    val initialCwnd: Long = 10 * 1460,
    val receiveWindow: Long = 8 * MIB,
    val lossEveryPackets: Long = 0,
    val degradedHost: String? = null,
    val degradedRate: Long = 400 * 1024,
    val failRequest: Int? = null,
    val failAfter: Long = 64 * 1024 + 17,
    val stallRequest: Int? = null,
    val stallMs: Long = 30_000,
)

/**
 * One shared fluid bottleneck, one millisecond scheduling quantum. ACKs arrive after RTT;
 * cwnd grows by acknowledged bytes in slow start and MSS*acked/cwnd in avoidance.
 * Deterministic packet losses consume wire capacity, then retransmit after one RTT.
 * This is an explanatory Reno-like model, not a kernel TCP emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VirtualNetwork(private val scope: TestScope, val size: Long, val profile: NetworkProfile) {
    private data class Ack(val at: Long, val bytes: Long)
    private class Socket(val host: String, var cwnd: Long) {
        var threshold = Long.MAX_VALUE
        var inFlight = 0L
        val acks = ArrayDeque<Ack>()
        var idleAt = 0L
    }
    private class Flow(val socket: Socket, val id: Int, val start: Long, val end: Long, val channel: ByteChannel) {
        var writer: Job? = null
        var ready = false
        var remaining = 0
        var grant: CompletableDeferred<Int>? = null
        var blockedUntil = 0L
    }
    data class Request(val id: Int, val host: String, val start: Long, val end: Long, val atMs: Long, val reused: Boolean,
        var finishedAtMs: Long? = null, var completed: Boolean? = null)
    data class Reset(val requestId: Int, val nextOffset: Long, val atMs: Long)
    val resets = mutableListOf<Reset>()
    val requests = mutableListOf<Request>()
    private val flows = mutableListOf<Flow>()
    private val idle = mutableListOf<Socket>()
    var wireBytes = 0L; private set
    var peakActive = 0; private set
    var rejected = 0; private set
    var teardowns = 0; private set
    var losses = 0; private set
    var active = 0; private set
    private var packetCounter = 0L
    private var rotation = 0
    private val now get() = scope.testScheduler.currentTime
    private val tickBytes = mutableMapOf<Long, Long>()
    private val pump = scope.backgroundScope.launch {
        while (isActive) {
            delay(1)
            tick()
        }
    }

    val engine = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(scope.testScheduler)
        addHandler { request ->
            // Cancellation can free a production permit and admit its replacement in the
            // same virtual millisecond. Only closed channels have ceased being real flows.
            reapClosedFlows()
            if (active >= 8) {
                rejected++
                return@addHandler respond("busy", HttpStatusCode.ServiceUnavailable)
            }
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.headers[HttpHeaders.Range] ?: error("missing Range"))!!
            val start = match.groupValues[1].toLong()
            val end = min(match.groupValues[2].toLongOrNull() ?: (size - 1), size - 1)
            require(start in 0 until size && end >= start)
            val host = request.url.host
            val socket = idle.firstOrNull { it.host == host }
            val reused = socket != null
            if (socket != null) idle.remove(socket)
            val selected = socket ?: Socket(host, profile.initialCwnd)
            if (reused && now - selected.idleAt > profile.rttMs * 10) selected.cwnd = profile.initialCwnd
            val id = requests.size + 1
            val channel = ByteChannel(autoFlush = true)
            val flow = Flow(selected, id, start, end, channel)
            requests += Request(id, host, start, end, now, reused)
            flows += flow
            active++
            peakActive = maxOf(peakActive, active)
            try {
                delay(profile.ttfbMs + if (reused) 0 else profile.rttMs)
            } catch (t: Throwable) {
                release(flow, false)
                throw t
            }
            flow.writer = scope.backgroundScope.launch {
                var offset = start
                var complete = false
                try {
                    if (id == profile.stallRequest) delay(profile.stallMs)
                    while (offset <= end) {
                        if (channel.isClosedForWrite) break
                        if (id == profile.failRequest && offset - start == profile.failAfter) {
                            // Let the consumer drain the last grant before the reset discards channel buffers.
                            delay(1)
                            resets += Reset(id, offset, now)
                            throw IOException("injected reset at $offset")
                        }
                        val untilReset = if (id == profile.failRequest) profile.failAfter - (offset - start) else Long.MAX_VALUE
                        val wanted = minOf(128 * 1024L, end - offset + 1, untilReset).toInt()
                        val grant = CompletableDeferred<Int>()
                        flow.remaining = wanted
                        flow.grant = grant
                        flow.ready = true
                        val count = grant.await()
                        flow.ready = false
                        channel.writeFully(content(offset, count))
                        offset += count
                    }
                    complete = offset > end
                    channel.close()
                } catch (t: Throwable) {
                    channel.cancel(t)
                } finally {
                    release(flow, complete)
                }
            }
            respond(channel, HttpStatusCode.PartialContent, headersOf(
                HttpHeaders.ContentRange to listOf("bytes $start-$end/$size"),
                HttpHeaders.ContentLength to listOf((end - start + 1).toString()),
            ))
        }
    })

    private fun release(flow: Flow, complete: Boolean) {
        if (!flows.remove(flow)) return
        active--
        requests[flow.id - 1].apply { finishedAtMs = now; completed = complete }
        if (complete) { flow.socket.idleAt = now; idle += flow.socket } else teardowns++
    }

    private fun reapClosedFlows() {
        flows.filter { it.channel.isClosedForWrite }.forEach {
            it.writer?.cancel()
            release(it, false)
        }
    }

    private fun tick() {
        reapClosedFlows()
        for (socket in (flows.map { it.socket } + idle)) {
            while (socket.acks.isNotEmpty() && socket.acks.first().at <= now) {
                val ack = socket.acks.removeFirst()
                socket.inFlight -= ack.bytes
                socket.cwnd = min(profile.receiveWindow, socket.cwnd +
                    if (socket.cwnd < socket.threshold) ack.bytes else maxOf(1, 1460 * ack.bytes / socket.cwnd))
            }
        }
        val ready = flows.filter { it.ready && it.blockedUntil <= now && !it.channel.isClosedForWrite }
        if (ready.isEmpty()) return
        var capacity = profile.capacity / 1000
        val ordered = ready.drop(rotation % ready.size) + ready.take(rotation % ready.size)
        rotation++
        // Water-fill a fair allocation before waking producers. Credits are not carried
        // over to idle ticks, so a TTFB pause cannot earn an unphysical later burst.
        val grants = mutableMapOf<Flow, Long>()
        var pending = ordered
        while (capacity > 0 && pending.isNotEmpty()) {
            val share = maxOf(1, capacity / pending.size)
            val next = mutableListOf<Flow>()
            for (flow in pending) {
                val already = grants[flow] ?: 0
                val hostCap = if (flow.socket.host == profile.degradedHost) profile.degradedRate / 1000 else Long.MAX_VALUE
                val room = minOf(flow.remaining.toLong(), flow.socket.cwnd - flow.socket.inFlight, hostCap) - already
                val count = minOf(share, capacity, room).coerceAtLeast(0)
                grants[flow] = already + count
                capacity -= count
                if (room > count) next += flow
            }
            pending = next
        }
        for ((flow, count) in grants) {
            if (count == 0L) continue
            val socket = flow.socket
            wireBytes += count
            tickBytes[now] = (tickBytes[now] ?: 0) + count
            socket.inFlight += count
            socket.acks += Ack(now + profile.rttMs, count)
            packetCounter += (count + 1459) / 1460
            if (profile.lossEveryPackets > 0 && packetCounter >= profile.lossEveryPackets) {
                packetCounter = 0
                losses++
                socket.threshold = maxOf(2 * 1460L, socket.cwnd / 2)
                socket.cwnd = socket.threshold
                flow.blockedUntil = now + profile.rttMs
            } else {
                flow.ready = false
                flow.grant!!.complete(count.toInt())
            }
        }
    }

    fun assertConservation() {
        check(tickBytes.values.all { it <= profile.capacity / 1000 }) { "global bottleneck oversubscribed" }
        check(peakActive <= 8)
        check(flows.count { !it.channel.isClosedForWrite } <= 8)
    }

    fun printRequestDiagnostics() {
        requests.forEach {
            println("SIM request id=${it.id} host=${it.host} span=${it.start}..${it.end} " +
                "bytes=${it.end - it.start + 1} startMs=${it.atMs} finishMs=${it.finishedAtMs} " +
                "completed=${it.completed} reused=${it.reused}")
        }
    }
    /** Wait for real handler/body release, without clearing counters or stopping the pump. */
    suspend fun awaitIdle() {
        val started = now
        // MockEngine may finish a delayed header handler before seeing consumer cancellation.
        // Body cancellation is then observed by the pump, including a sleeping stalled writer.
        val deadlineMs = profile.ttfbMs + profile.rttMs + 1000
        do {
            delay(1)
        } while (active != 0 && now - started < deadlineMs)
        check(active == 0) {
            "transport did not quiesce in ${deadlineMs}ms: " +
                flows.joinToString { "id=${it.id} writer=${it.writer?.isActive} closed=${it.channel.isClosedForWrite}" }
        }
    }

    fun close() { pump.cancel(); engine.close() }
}
