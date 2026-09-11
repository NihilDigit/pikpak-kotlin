package io.github.nihildigit.pikpak

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * Random access to the bytes of one remote file.
 *
 * This is what the SDK actually promises a consumer: a file somewhere in
 * PikPak, readable at any offset, with priority honoured when connections are
 * contended. [RangeReader] is the plain implementation over a fixed URL;
 * [PikPakFileHandle] is the one that keeps reading across everything that can
 * invalidate the URL underneath it.
 *
 * Both readers take this rather than a [RangeReader] because a handle retires
 * its reader — on signature expiry and when the file id has to be rebuilt —
 * and anything holding an instance it was handed once keeps reading through a
 * reader the handle has already closed. Taking the interface means every read
 * asks again, and the swap is invisible to whoever is reading.
 */
interface RangeSource {
    /**
     * Reads [length] bytes from [start] and hands them to [block] as a stream.
     *
     * @param priority higher wins a contended connection slot, both among this
     * file's reads and against every other file the client is reading. A read
     * at the playback head should outrank read-ahead, and both should outrank
     * a background download.
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
     * Overridable because [RangeReader] can fill a caller's array without the
     * intermediate channel; the default is here so an implementation only has
     * to provide [read].
     */
    suspend fun readBytes(start: Long, length: Long, priority: Int = 0): ByteArray {
        require(length <= Int.MAX_VALUE) { "readBytes cannot materialise $length bytes" }
        val out = ByteArray(length.toInt())
        var filled = 0
        read(start, length, priority) { channel ->
            while (filled < out.size) {
                val n = channel.readAvailable(out, filled, out.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        // A range that runs past the end of the file ends at EOF, like a file read.
        return if (filled == out.size) out else out.copyOf(filled)
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

    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray =
        reader.readBytes(start, length, priority)
}
