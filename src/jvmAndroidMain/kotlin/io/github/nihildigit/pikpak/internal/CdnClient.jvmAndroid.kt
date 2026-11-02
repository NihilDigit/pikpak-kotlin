package io.github.nihildigit.pikpak.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

private val TLS_1_2_FALLBACK: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_2)
    .build()

private val RESILIENT_CONNECTION_SPECS: List<ConnectionSpec> = listOf(
    ConnectionSpec.MODERN_TLS,
    TLS_1_2_FALLBACK,
    ConnectionSpec.CLEARTEXT,
)

internal actual fun defaultCdnHttpClient(perHostLimit: Int): HttpClient =
    try {
        okHttpCdnClient(perHostLimit)
    } catch (_: LinkageError) {
        // OkHttp is a compileOnly dependency: a consumer on a different JVM
        // engine still gets a working client, just without the per-host tuning.
        unconfiguredCdnClient()
    }

internal actual fun defaultApiHttpClient(perHostLimit: Int): HttpClient =
    try {
        okHttpApiClient(perHostLimit)
    } catch (_: LinkageError) {
        unconfiguredApiClient()
    }

private fun okHttpApiClient(perHostLimit: Int): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // Keep default ALPN (HTTP/2 + HTTP/1.1): api-drive negotiates h2.
            // Include an explicit TLS 1.2 fallback spec so Android 17+ Conscrypt
            // can retry without oversized TLS 1.3 + ML-KEM ClientHello when a
            // mobile TUN/proxy middlebox drops the initial handshake with EOF.
            connectionSpecs(RESILIENT_CONNECTION_SPECS)
            connectionPool(ConnectionPool(perHostLimit.coerceAtLeast(16), 5, TimeUnit.MINUTES))
            dispatcher(
                Dispatcher().apply {
                    maxRequestsPerHost = perHostLimit
                    maxRequests = perHostLimit * 2
                },
            )
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = API_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = API_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = API_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}

private fun unconfiguredApiClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = API_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = API_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = API_SOCKET_TIMEOUT_MS
    }
    expectSuccess = false
}

private fun okHttpCdnClient(perHostLimit: Int): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // The CDN offers no h2. Pinning 1.1 skips an ALPN negotiation that
            // can only end here anyway. Aliyun OSS endpoints also speak TLS 1.2
            // only; keeping an explicit TLS 1.2 fallback spec recovers immediately
            // when a mobile carrier/TUN drops a TLS 1.3 + ML-KEM ClientHello.
            protocols(listOf(Protocol.HTTP_1_1))
            connectionSpecs(RESILIENT_CONNECTION_SPECS)
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
