package io.github.nihildigit.pikpak.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.github.nihildigit.pikpak.CdnConnectionStats
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Dispatcher
import okhttp3.Handshake
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

internal actual fun defaultCdnHttpClient(connectionBudget: Int): HttpClient =
    try {
        okHttpCdnClient(connectionBudget)
    } catch (_: LinkageError) {
        // OkHttp is a compileOnly dependency: a consumer on a different JVM
        // engine still gets a working client, just without the per-host tuning.
        unconfiguredCdnClient()
    }

private fun okHttpCdnClient(connectionBudget: Int): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            // The CDN offers no h2. Pinning 1.1 skips an ALPN negotiation that
            // can only end here anyway.
            protocols(listOf(Protocol.HTTP_1_1))
            // Idle connections survived 90 s in testing, so keeping the pool
            // warm across seeks is free; sizing it below the budget would make
            // every read pay a fresh handshake.
            connectionPool(ConnectionPool(connectionBudget * 2, 5, TimeUnit.MINUTES))
            eventListenerFactory { CdnConnectionListener() }
            dispatcher(
                Dispatcher().apply {
                    maxRequestsPerHost = connectionBudget
                    maxRequests = connectionBudget * 4
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

/**
 * Counts connection reuse so a request's time to first byte can be split.
 *
 * A fresh TCP and TLS handshake is three to four round trips and a pooled
 * connection is one, and nothing above this layer can tell which a given
 * request paid -- both land in the same measurement. OkHttp builds one listener
 * per call, so the start times can be fields here rather than a map keyed by
 * call.
 *
 * The callbacks arrive as dnsStart, dnsEnd, connectStart, secureConnectStart,
 * secureConnectEnd, connectEnd: TLS is nested inside the connect span and DNS
 * sits outside it, so connectEnd - connectStart is the whole handshake and the
 * transport part only comes out by subtracting TLS from it. A call may run
 * through this sequence more than once -- another address, or a proxy tunnel --
 * and the runs do not overlap, so overwriting the fields is safe.
 *
 * Duplicated in the JVM and Android source sets because they do not share one
 * and the client builders above are already near-duplicates.
 */
private class CdnConnectionListener : EventListener() {
    private var dnsStartedAt = 0L
    private var connectStartedAt = 0L
    private var secureStartedAt = 0L
    private var secureNanos = 0L

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartedAt = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        CdnConnectionStats.recordResolved((System.nanoTime() - dnsStartedAt).nanoseconds)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartedAt = System.nanoTime()
        secureNanos = 0L
        CdnConnectionStats.recordConnectStart()
    }

    override fun secureConnectStart(call: Call) {
        secureStartedAt = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        secureNanos = System.nanoTime() - secureStartedAt
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        CdnConnectionStats.recordOpened(
            (System.nanoTime() - connectStartedAt).nanoseconds,
            secureNanos.nanoseconds,
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        CdnConnectionStats.recordFailed(
            ioe.javaClass.simpleName,
            ioe.message,
            (System.nanoTime() - connectStartedAt).nanoseconds,
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        CdnConnectionStats.recordAcquired()
    }
}
