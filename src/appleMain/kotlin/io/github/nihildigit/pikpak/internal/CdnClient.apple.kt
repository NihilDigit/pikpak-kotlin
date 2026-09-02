package io.github.nihildigit.pikpak.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

internal actual fun defaultCdnHttpClient(connectionBudget: Int): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            // NSURLSession defaults to 4 connections per host on iOS and 6 on
            // macOS, both below any useful range-reader budget. There is no
            // protocol knob here: NSURLSession decides HTTP/1.1 vs h2 by ALPN,
            // and the CDN only offers 1.1, so the outcome is the same.
            setHTTPMaximumConnectionsPerHost(connectionBudget.toLong())
            setHTTPShouldUsePipelining(false)
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CDN_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = Long.MAX_VALUE
        socketTimeoutMillis = CDN_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}
