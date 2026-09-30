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

/**
 * A [BlockStore] that keeps everything it is given, for a caller that wants a
 * file on disk rather than a cache of what happened to be played: what
 * [PikPakFileCache.download] needs.
 *
 * The contract is stricter than [BlockStore]'s in three places:
 *  - [write] returning means the block is held: [read] hands it back from then
 *    on, including to another reader of the same storage. A block that cannot
 *    be kept makes [write] throw; it is never dropped quietly. When the bytes
 *    also survive a crash is the store's business, as long as it never reports
 *    a block it could lose as held across a restart.
 *  - [read] returns only held blocks. A store that preallocates its file must
 *    not hand back the zeros of a block never written.
 *  - [missing] answers from what is held, so a download skips what an earlier
 *    run, or playback through the same store, already put there.
 *
 * Writes arrive at block boundaries of the cache ([PikPakStreamReader.DEFAULT_BLOCK_SIZE],
 * the last block shorter), from several coroutines at once.
 */
interface DurableBlockStore : BlockStore {
    /** The parts of [ranges] of [file] not held yet, in the order given. */
    suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange>
}
