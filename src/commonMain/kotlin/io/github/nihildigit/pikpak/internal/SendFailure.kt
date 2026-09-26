package io.github.nihildigit.pikpak.internal

/**
 * Whether this failure happened before any byte of the request reached the server: no
 * connection, no route, a name that did not resolve, a TLS handshake that was cut. Only then is a
 * request that changes something safe to send again, because the server cannot have acted on it.
 * The types that say so are the platform's own, hence per platform; one that cannot tell answers
 * false and the request is not replayed.
 */
internal expect fun Throwable.failedBeforeSending(): Boolean
