package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [sampleCid] reads the windows over HTTP ranges while [XunleiCid.of] reads
 * them from memory. A range one byte off, or windows read out of order, still
 * yields a well-formed CID that simply misses in [gcidByCid], so the check is
 * that both agree on the same bytes, across the whole-file cut-off.
 */
class SampleCidMockTest {

    @Test
    fun `a cid read over ranges matches one hashed from memory`() = runBlocking {
        for (size in listOf(1_000, 0xF000 - 1, 0xF000, 200_003)) {
            val content = ByteArray(size) { (it * 31 + it / 7).toByte() }
            val client = cdnServing(content)
            val detail = FileDetail(
                id = "f",
                size = size.toString(),
                links = mapOf(FileDetail.OCTET_STREAM to DownloadLink(url = "https://cdn/f")),
            )
            val expected = XunleiCid.of(size.toLong()) { offset, length -> content.copyOfRange(offset.toInt(), offset.toInt() + length) }
            assertEquals(expected, client.sampleCid(detail), "size $size")
            client.close()
        }
    }

    private fun cdnServing(content: ByteArray): PikPakClient {
        val engine = MockEngine { request ->
            val (start, end) = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-').map { it.toLong() }
            respond(
                content = ByteReadChannel(content.copyOfRange(start.toInt(), end.toInt() + 1)),
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentRange, "bytes $start-$end/${content.size}")
                    append(HttpHeaders.ContentLength, (end - start + 1).toString())
                },
            )
        }
        return PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
        )
    }
}
