package io.github.nihildigit.pikpak

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Inputs the live tests share. */
internal object TestFixtures {
    /**
     * A freely redistributable release large enough to time reads against.
     * PikPak keeps popular torrents cached, so submitting it usually completes
     * without a real fetch.
     */
    const val ARCH_ISO_MAGNET =
        "magnet:?xt=urn:btih:157e0a57e1af0e1cfd46258ba6c62938c21b6ee8&dn=archlinux-2026.04.01-x86_64.iso"
}

/** Downloads [fileId]'s original to [dest] the way a consumer would: a handle over its detail, then downloadTo. */
internal suspend fun PikPakClient.downloadFile(fileId: String, dest: kotlinx.io.files.Path): Long {
    val detail = getFile(fileId)
    return fileHandle(detail).use { it.downloadTo(dest, totalSize = detail.sizeBytes) }
}

/**
 * Polls [check] until it holds or [timeout] passes, and prints how long it took.
 *
 * The folder listing trails trash, restore and delete. Asserted right after the call it failed
 * at whichever of those steps happened to be slow, one run at the trash, the next at the restore,
 * so the live tests wait for the listing instead of asserting it at once. Measured 2026-09-27:
 * a restored folder took 0.8 s to be listed again, the other steps 0.1 to 0.4 s.
 */
internal suspend fun eventually(what: String, timeout: Duration = 15.seconds, check: suspend () -> Boolean): Boolean {
    val started = TimeSource.Monotonic.markNow()
    while (true) {
        if (check()) {
            println("[listing] $what after ${started.elapsedNow()}")
            return true
        }
        if (started.elapsedNow() > timeout) return false
        delay(500.milliseconds)
    }
}
