package io.github.nihildigit.pikpak

/**
 * Somewhere outside memory to keep the blocks a [PikPakFileHandle] fetches, so that reopening
 * the same content does not fetch them again.
 *
 * The library still writes nothing to disk itself. What is worth keeping, how much, and when
 * to let it go are the caller's to decide, and they differ: a player wants the head of every
 * episode it has shown and not the whole film it streamed. So every block the handle fetches
 * is offered to [write] and the store keeps what it wants; every block the handle needs is
 * asked of [read] before a range request is spent on it.
 *
 * [file] names content, not a file object: the gcid and the variant, so a transcode and the
 * original never share blocks. A block is always asked for at the offset and length it was
 * written with, so a store can key on the pair and need not handle partial reads.
 *
 * [read] runs on the handle's workers, so a slow store slows the stream. [write] runs from a
 * short queue behind them: a store that cannot keep up misses offers rather than holding up
 * reads or piling fetched bytes in memory. A store that throws is taken as one that holds
 * nothing, so a full disk degrades to streaming.
 */
interface BlockStore {
    /** The [length] bytes written at [offset] for [file], or null when not held. */
    suspend fun read(file: String, offset: Long, length: Int): ByteArray?

    /** Offers the block at [offset]. Keeping it is optional. */
    suspend fun write(file: String, offset: Long, bytes: ByteArray)
}
