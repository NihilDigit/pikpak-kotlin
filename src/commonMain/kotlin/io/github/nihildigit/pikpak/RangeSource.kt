package io.github.nihildigit.pikpak

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Random access to the bytes of one remote file.
 *
 * This is what the SDK actually promises a consumer: a file somewhere in
 * PikPak, readable at any offset, with priority honoured when connections are
 * contended. [RangeReader] is the plain implementation over a fixed URL;
 * [PikPakFileHandle] is the one that keeps reading across everything that can
 * invalidate the URL underneath it.
 *
 * The stream reader and [downloadTo] take this rather than a [RangeReader]
 * because a handle retires its reader — on signature expiry and when the file
 * id has to be rebuilt — and anything holding an instance it was handed once
 * keeps reading through a reader the handle has already closed. Taking the
 * interface means every read asks again, and the swap is invisible to whoever
 * is reading.
 */
interface RangeSource {
    /**
     * Reads [length] bytes from [start] and hands them to [block] as a stream.
     *
     * @param priority higher wins a contended connection slot, both among this
     * file's reads and against every other file the client is reading. A read
     * at the playback head should outrank read-ahead, and both should outrank
     * a background download. [PikPakStreamReader.BLOCKING_PRIORITY] and
     * [PikPakStreamReader.READ_AHEAD_PRIORITY] are the scale this SDK reads on.
     *
     * Priority decides who takes the next free slot, never who keeps one: a
     * slot is not taken back from a read already running. So [length] on the
     * low priority reads is what bounds how long a high priority read waits,
     * and a caller issuing large background requests picks that bound itself.
     * Measured on a saturated 50 Mbit/s line across eight connections, one
     * megabyte per background request made playback wait up to three seconds;
     * halving the request brought the worst wait under one.
     */
    suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int = 0,
        block: suspend (ByteReadChannel) -> T,
    ): T

    /**
     * Reads a range into memory. Only for ranges small enough to hold.
     *
     * A range that runs past the end of the file ends at EOF, like a file
     * read: the array comes back shorter than [length] rather than failing.
     * A caller that needs the exact length checks it.
     */
    suspend fun readBytes(start: Long, length: Long, priority: Int = 0): ByteArray {
        require(length <= Int.MAX_VALUE) { "readBytes cannot materialise $length bytes" }
        return read(start, length, priority) { channel -> channel.readFully(length.toInt()) }
    }
}

/** Serves a fixed [RangeReader]. Use [PikPakFileHandle] instead when the file id may move. */
fun RangeReader.asRangeSource(): RangeSource = RangeReaderSource(this)

private class RangeReaderSource(private val reader: RangeReader) : RangeSource {
    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T = reader.read(start, length, priority, block)
}

/** Up to [length] bytes, fewer only at EOF. */
internal suspend fun ByteReadChannel.readFully(length: Int): ByteArray {
    val out = ByteArray(length)
    var filled = 0
    while (filled < out.size) {
        // readAvailable hands over already-buffered bytes without suspending,
        // so without this a cancelled read could run to the end of its range
        // before reaching a cancellation point.
        currentCoroutineContext().ensureActive()
        val n = readAvailable(out, filled, out.size - filled)
        if (n < 0) break
        filled += n
    }
    return if (filled == out.size) out else out.copyOf(filled)
}
