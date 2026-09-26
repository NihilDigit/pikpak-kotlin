package io.github.nihildigit.pikpak

import kotlinx.io.files.Path

/**
 * There is no default on Android. `user.home` resolves to the process root there, which is not
 * writable, and a path that fails only on the first save let a sign-in succeed on the network
 * and then report failure. So a [FileSessionStore] or a [PikPakClient] built with the defaults
 * fails here, at construction, saying what to pass instead.
 */
actual fun defaultSessionDir(): Path = throw IllegalStateException(
    "No default session directory on Android: pass FileSessionStore(dir = Path(context.filesDir.absolutePath, \"pikpak-kotlin\")) " +
        "or your own SessionStore to PikPakClient",
)
