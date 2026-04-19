package io.github.nihildigit.pikpak

import kotlinx.io.files.Path

actual fun defaultSessionDir(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val home = System.getProperty("user.home") ?: "."
    val base = xdg ?: "$home/.config"
    return Path("$base/pikpak-kotlin")
}
