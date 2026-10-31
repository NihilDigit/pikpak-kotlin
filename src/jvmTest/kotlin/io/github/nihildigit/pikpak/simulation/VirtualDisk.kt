package io.github.nihildigit.pikpak.simulation

import io.github.nihildigit.pikpak.DurableBlockStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serial bounded disk. Retains only committed block metadata, never the virtual file body. */
internal class VirtualDisk(val blockSize: Int = 256 * 1024, val bytesPerSecond: Long = 500_000_000) : DurableBlockStore {
    private val mutex = Mutex()
    private val committed = mutableMapOf<Long, Int>()
    var uniqueBytes = 0L; private set
    var peakWriteBytes = 0L; private set
    private var writeBytes = 0L
    var duplicateWrites = 0; private set
    override suspend fun read(file: String, offset: Long, length: Int): ByteArray? =
        if (committed[offset] == length) content(offset, length) else null

    override suspend fun write(file: String, offset: Long, bytes: ByteArray) {
        writeBytes += bytes.size
        peakWriteBytes = maxOf(peakWriteBytes, writeBytes)
        try {
            mutex.withLock {
                verifyContent(offset, bytes)
                delay((bytes.size * 1000L + bytesPerSecond - 1) / bytesPerSecond)
                if (committed.put(offset, bytes.size) == null) uniqueBytes += bytes.size else duplicateWrites++
            }
        } finally { writeBytes -= bytes.size }
    }

    override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = buildList {
        for (range in ranges) {
            var position = range.first
            while (position <= range.last) {
                val count = minOf(blockSize.toLong(), range.last - position + 1).toInt()
                if (committed[position] != count) add(position until position + count)
                position += count
            }
        }
    }
}
