package io.github.nihildigit.pikpak.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

internal actual fun defaultCdnHttpClient(connectionBudget: Int): HttpClient = cioCdnClient(connectionBudget)

internal fun cioCdnClient(connectionBudget: Int): HttpClient = HttpClient(CIO) {
    engine {
        maxConnectionsCount = connectionBudget * 4
        endpoint.apply {
            maxConnectionsPerRoute = connectionBudget
            // CIO speaks HTTP/1.1 only, so nothing to pin. Pipelining is off
            // by default and must stay off: the CDN answers ranges out of
            // order under load and a pipelined connection cannot recover.
            pipelineMaxSize = 1
            connectTimeout = CDN_CONNECT_TIMEOUT_MS
            keepAliveTime = 60_000L
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CDN_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = Long.MAX_VALUE
        socketTimeoutMillis = CDN_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}
