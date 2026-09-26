package io.github.nihildigit.pikpak.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

internal actual fun defaultCdnHttpClient(perHostLimit: Int): HttpClient =
    try {
        okHttpCdnClient(perHostLimit)
    } catch (_: LinkageError) {
        // OkHttp is a compileOnly dependency: a consumer on a different JVM
        // engine still gets a working client, just without the per-host tuning.
        unconfiguredCdnClient()
    }

private fun okHttpCdnClient(perHostLimit: Int): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // The CDN offers no h2. Pinning 1.1 skips an ALPN negotiation that
            // can only end here anyway.
            protocols(listOf(Protocol.HTTP_1_1))
            // Idle connections survived 90 s in testing, so keeping the pool
            // warm across seeks is free; sizing it below the budget would make
            // every read pay a fresh handshake.
            connectionPool(ConnectionPool(perHostLimit * 2, 5, TimeUnit.MINUTES))
            dispatcher(
                Dispatcher().apply {
                    maxRequestsPerHost = perHostLimit
                    maxRequests = perHostLimit * 4
                },
            )
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = CDN_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = Long.MAX_VALUE
        socketTimeoutMillis = CDN_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}

private fun unconfiguredCdnClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = CDN_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = Long.MAX_VALUE
        socketTimeoutMillis = CDN_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}
