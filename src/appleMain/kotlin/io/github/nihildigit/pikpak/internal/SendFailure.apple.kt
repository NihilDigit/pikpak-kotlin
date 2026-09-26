package io.github.nihildigit.pikpak.internal

import io.ktor.client.network.sockets.ConnectTimeoutException

// The Darwin engine reports the rest as an NSError wrapped in its own exception, with nothing
// typed to tell a refused connect from a reset after sending; so only the timeout counts
internal actual fun Throwable.failedBeforeSending(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is ConnectTimeoutException }
