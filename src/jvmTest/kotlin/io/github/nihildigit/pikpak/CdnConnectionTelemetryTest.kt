package io.github.nihildigit.pikpak

import com.sun.net.httpserver.HttpServer
import io.github.nihildigit.pikpak.internal.ReadTelemetry
import io.github.nihildigit.pikpak.internal.defaultCdnHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CdnConnectionTelemetryTest {
    @Test
    fun `real pooled request is warm and internal correlation never reaches server`() = runBlocking<Unit> {
        val headers = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            headers.add(exchange.requestHeaders.getFirst("X-PikPak-Internal-Connection-Observation"))
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
            exchange.close()
        }
        server.start()
        val client = defaultCdnHttpClient(2)
        try {
            val telemetry = ReadTelemetry()
            val url = "http://127.0.0.1:${server.address.port}/"
            assertNull(telemetry.connectionReused)
            withContext(telemetry) { assertEquals("ok", client.get(url).bodyAsText()) }
            assertEquals(false, telemetry.connectionReused)
            withContext(telemetry) { assertEquals("ok", client.get(url).bodyAsText()) }
            assertEquals(true, telemetry.connectionReused)
            assertEquals(listOf(null, null), headers.toList())
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
