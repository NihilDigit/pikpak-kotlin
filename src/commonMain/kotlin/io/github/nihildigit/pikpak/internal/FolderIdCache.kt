package io.github.nihildigit.pikpak.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Memoizes `(parentId, path) -> folderId`.
 *
 * Resolving `a/b/c` costs one round trip per segment, and callers that key
 * their work on a path re-resolve it on every operation. Ids are stable for
 * the life of a folder, so the only way an entry goes wrong is the folder
 * being moved, renamed or deleted — which is why every mutating endpoint in
 * the SDK drops the whole map rather than trying to patch it. The map is small
 * and refilling it costs the same round trips the caller was making anyway; a
 * surgical invalidation would have to model every cached path that could pass
 * through a renamed segment.
 *
 * Nothing here is authoritative: a folder deleted from another device leaves a
 * stale entry until the next mutation or 404, and the caller then sees the
 * same error it would have seen without the cache.
 */
internal class FolderIdCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, String>()

    suspend fun get(parentId: String, path: String): String? = mutex.withLock {
        entries[key(parentId, path)]
    }

    suspend fun put(parentId: String, path: String, folderId: String) = mutex.withLock {
        entries[key(parentId, path)] = folderId
    }

    suspend fun invalidateAll() = mutex.withLock {
        entries.clear()
    }

    /**
     * Drops one folder and everything cached beneath it.
     *
     * For a caller that has just watched one specific folder answer 404, which
     * is narrower than what a rename or a move can break: the folder is gone
     * rather than relocated, so the only entries that can be wrong are the ones
     * whose path ran through it, and under a fixed parent those are exactly its
     * descendants. Everything else still resolves, which is why this does not
     * fall back on [invalidateAll].
     *
     * Descendants have to go as well — `pack` disappearing takes
     * `pack/specials` with it, and that entry names an id that is equally dead.
     */
    suspend fun invalidate(parentId: String, path: String) = mutex.withLock {
        val exact = key(parentId, path)
        val subtree = exact + '/'
        entries.keys.retainAll { it != exact && !it.startsWith(subtree) }
    }

    // The separator is NUL because it can occur in neither a PikPak file id
    // nor a path segment, so no two distinct lookups can collide on it.
    private fun key(parentId: String, path: String): String =
        parentId + SEPARATOR + path.trim('/')

    private companion object {
        const val SEPARATOR = '\u0000'
    }
}
