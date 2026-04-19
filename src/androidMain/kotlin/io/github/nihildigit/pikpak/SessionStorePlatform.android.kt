package io.github.nihildigit.pikpak

import kotlinx.io.files.Path

/**
 * Android-default path. `user.home` on Android typically resolves to the
 * process root (`/`) which is not writable — consumers that care about a
 * stable on-disk location should instead construct
 * `FileSessionStore(dir = Path(context.filesDir.absolutePath, "pikpak-kotlin"))`
 * from their application, or swap in [InMemorySessionStore].
 */
actual fun defaultSessionDir(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val home = System.getProperty("user.home") ?: "."
    val base = xdg ?: "$home/.config"
    return Path("$base/pikpak-kotlin")
}
