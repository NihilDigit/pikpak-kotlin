package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds

class TransportRegressionTest {
    @Test fun rateLimitRetriesWithoutRefresh(): Unit = runBlocking {
        var calls = 0
        var issued = 0
        val http = HttpClient(MockEngine {
            calls++
            if (calls <= 2) respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0"))
            else respond(ByteReadChannel(byteArrayOf(1)), HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange to listOf("bytes 0-0/1"), HttpHeaders.ContentLength to listOf("1")))
        })
        val client = PikPakClient("mock", "pw", httpClient = http, cdnHttpClient = http, sessionStore = InMemorySessionStore())
        val reader = RangeReader(client, { issued++; "https://cdn/file?sign=s" })
        try {
            assertContentEquals(byteArrayOf(1), reader.readBytes(0, 1))
            assertEquals(1, issued)
        } finally { reader.close(); client.close(); http.close() }
    }
}
