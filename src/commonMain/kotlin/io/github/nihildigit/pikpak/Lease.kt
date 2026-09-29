package io.github.nihildigit.pikpak

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A [FileDetail] for content PikPak holds, without keeping a file for it: an
 * [instantCreate] under [parentId], the detail read off it, and the object
 * deleted again. The links in the detail stay valid after the delete, which
 * is the whole point: a signed link outlives the object that minted it,
 * permanent deletion included.
 *
 * For reads that may outlive a link, build a [fileHandle] from the result
 * with `leased = true`. When the link expires the handle creates a new object
 * from the gcid and deletes that one as soon as it has its link.
 *
 * Costs what an instant create costs: 15 % of the size from the monthly upload
 * allowance, and the file's size in storage for the moment the object exists,
 * so a free account can lease nothing larger than its free space.
 *
 * The delete runs in the background: the caller has its detail after two
 * requests instead of three, which counts when many leases queue behind the
 * rate limiter. Storage is not left to chance meanwhile: with a
 * [PikPakClient.leaseBudget] set, the next lease waits until enough earlier
 * deletes have landed. A delete still pending when the client closes is lost;
 * the object then waits in [parentId] for the caller's sweep.
 */
suspend fun PikPakClient.leaseDetail(
    file: ResolvedFile,
    parentId: String = "",
    name: String = file.name,
): FileDetail {
    val budget = leaseBudget
    val taken = budget?.acquire(file.size) ?: 0L
    val id = try {
        instantCreate(file, parentId, name)
    } catch (e: Throwable) {
        withContext(NonCancellable) { budget?.release(taken) }
        throw e
    }
    try {
        return getFile(id)
    } finally {
        // Also when the lookup failed or was cancelled: nobody else knows this id.
        // The room goes back only once the object is gone, not when the delete is asked for
        background.launch {
            deleteLease(id)
            budget?.release(taken)
        }
    }
}

/**
 * Deletes a leased object and swallows the failure. An object that survives
 * costs storage until something sweeps it, never a read: the link it minted
 * no longer depends on it.
 */
internal suspend fun PikPakClient.deleteLease(fileId: String) {
    try {
        deleteFile(fileId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
