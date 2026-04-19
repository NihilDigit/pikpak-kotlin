package io.github.nihildigit.pikpak

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.kotlincrypto.hash.sha1.SHA1

/**
 * PikPak's content hash ("gcid") used as the dedup/upload identity.
 *
 * Algorithm: split the file into chunks of [chunkSizeFor] bytes, take SHA1 of
 * each chunk, concatenate the raw 20-byte digests in order, then take SHA1 of
 * the concatenation. Result is lowercase hex.
 *
 * The chunk-size table is copied verbatim from
 * github.com/52funny/pikpakhash@v0.0.0-20231104025731-ef91a56eff9c — empty
 * file and `size == 0x10000000` boundary cases included, since PikPak rejects
 * uploads where the hash differs by even one boundary choice.
 */
object PikPakHash {

    fun chunkSizeFor(fileSize: Long): Int = when {
        fileSize > 0 && fileSize < 0x8000000L -> 0x40000      // <128MB  → 256KB
        fileSize >= 0x8000000L && fileSize < 0x10000000L -> 0x80000  // 128-256MB → 512KB
        fileSize <= 0x10000000L || fileSize > 0x20000000L -> 0x200000 // <=256MB or >512MB → 2MB
        else -> 0x100000                                       // 256MB-512MB → 1MB
    }

    /** Compute the gcid for a file at [path]. */
    fun fromPath(path: Path): String {
        val size = SystemFileSystem.metadataOrNull(path)?.size
            ?: throw IllegalArgumentException("file does not exist: $path")
        return SystemFileSystem.source(path).buffered().use { fromSource(it, size) }
    }

    /**
     * Compute the gcid by streaming [size] bytes from [source]. Caller is
     * responsible for the lifetime of [source].
     */
    fun fromSource(source: RawSource, size: Long): String {
        if (size <= 0) return SHA1().digest(ByteArray(0)).toHex()

        val chunkSize = chunkSizeFor(size)
        val outer = SHA1()
        val buffered = if (source is kotlinx.io.Source) source else source.buffered()
        var remaining = size
        while (remaining > 0) {
            val want = minOf(remaining, chunkSize.toLong()).toInt()
            val bytes = buffered.readByteArray(want)
            if (bytes.size != want) throw IllegalStateException("source ended early at offset ${size - remaining}")
            outer.update(SHA1().digest(bytes))
            remaining -= want
        }
        return outer.digest().toHex()
    }
}
