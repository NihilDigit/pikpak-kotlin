package io.github.nihildigit.pikpak

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv

/**
 * Best-effort default via `$XDG_CONFIG_HOME` / `$HOME/.config`. iOS apps run
 * sandboxed and `$HOME` resolves to the app container — fine for sessions
 * written by that same app, but iOS callers who want a known absolute
 * location should construct `FileSessionStore(dir = ...)` explicitly with an
 * `NSURL`-derived path.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun defaultSessionDir(): Path {
    val xdg = getenv("XDG_CONFIG_HOME")?.toKString()?.takeIf { it.isNotBlank() }
    val home = getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }
    val base = xdg
        ?: home?.let { "$it/.config" }
        ?: "."
    return Path("$base/pikpak-kotlin")
}
