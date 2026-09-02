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

    // The separator is NUL because it can occur in neither a PikPak file id
    // nor a path segment, so no two distinct lookups can collide on it.
    private fun key(parentId: String, path: String): String =
        parentId + SEPARATOR + path.trim('/')

    private companion object {
        const val SEPARATOR = '\u0000'
    }
}
