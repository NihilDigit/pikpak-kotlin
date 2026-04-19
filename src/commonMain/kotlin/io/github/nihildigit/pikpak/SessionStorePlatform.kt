package io.github.nihildigit.pikpak

import kotlinx.io.files.Path

/** Returns the platform-default session directory. */
expect fun defaultSessionDir(): Path
