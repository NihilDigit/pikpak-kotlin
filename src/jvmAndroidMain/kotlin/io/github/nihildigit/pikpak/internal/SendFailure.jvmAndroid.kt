package io.github.nihildigit.pikpak.internal

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

// The engine wraps the socket's exception, so the whole cause chain is searched
internal actual fun Throwable.failedBeforeSending(): Boolean =
    generateSequence(this) { it.cause }.take(8).any {
        it is ConnectTimeoutException || it is ConnectException || it is UnknownHostException ||
            it is NoRouteToHostException || it is SSLHandshakeException
    }
