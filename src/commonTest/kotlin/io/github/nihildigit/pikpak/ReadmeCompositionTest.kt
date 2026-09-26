package io.github.nihildigit.pikpak

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compiles the arrangement the README's "Playing a magnet" section shows.
 *
 * Documentation that no longer compiles is worse than none, and this one spans
 * four types that were designed apart: a rename in any of them silently rots
 * the example a reader is copying. Nothing here runs against a network — the
 * point is that the pieces still fit together in that order.
 */
class ReadmeCompositionTest {

    /**
     * A client that never reaches a socket. The engine has to be supplied:
     * Ktor has no default one on Kotlin/Native, so constructing a client
     * without it compiles everywhere and then fails at class initialisation on
     * the native targets only.
     */
    private fun newClient() = PikPakClient(
        account = "mock@x",
        password = "pw",
        sessionStore = InMemorySessionStore(),
        httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }),
    )

    @Test
    fun `the documented arrangement still type-checks`() = runBlocking<Unit> {
        val client = newClient()

        val episode = ResolvedFile(path = "pack/ep01.mkv", size = 1_000, gcid = "A".repeat(40))

        val handle = PikPakFileHandle(
            client = client,
            gcid = episode.gcid!!,
            size = episode.size,
            name = episode.name,
            initialFileId = "f1",
        )

        val stream: PikPakStreamReader = handle.openStream()

        // A source over a fixed URL is the other accepted shape.
        val plain: RangeSource = RangeReader(client, { "https://cdn/x" }).asRangeSource()

        stream.close()
        handle.close()
        client.close()
        assertTrue(plain is RangeSource)
    }

    /**
     * The opening example reaches for `use`, which only works while `close`
     * stays non-suspending — and it is non-suspending on purpose, so that a
     * player can release its input from a lifecycle callback. Nothing else
     * would catch that pairing breaking.
     */
    @Test
    fun `a stream can be closed by use`() = runBlocking<Unit> {
        val client = newClient()
        val handle = PikPakFileHandle(client, gcid = "C".repeat(40), size = 64, name = "x.mkv")
        var entered = false
        try {
            handle.openStream().use { stream ->
                entered = true
                assertEquals(64L, stream.bytesRemaining)
            }
        } finally {
            handle.close()
            client.close()
        }
        assertTrue(entered, "the use block never ran")
    }

    /** The download line in the README, on the same handle the playback line uses. */
    @Test
    fun `a handle is also what downloadTo takes`() {
        // Compiled, not called: a real download would need a network. What is
        // being checked is that `handle.downloadTo(...)` resolves at all, since
        // downloadTo is an extension on RangeSource and PikPakFileHandle is one.
        val call: suspend (PikPakFileHandle, Path) -> Long = { handle, dest ->
            handle.downloadTo(dest, totalSize = 1_000, priority = 1)
        }
        assertNotNull(call)
    }

    /**
     * The gcid is what survives; a file id is a cache of it. A handle built
     * without one must still be constructible, because that is the shape a
     * caller resuming from persisted state has.
     */
    @Test
    fun `a handle needs no file id`() {
        val client = newClient()
        val handle = PikPakFileHandle(client, gcid = "B".repeat(40), size = 10, name = "x.mkv")
        assertNull(handle.currentFileId)
        handle.close()
        client.close()
    }

    /** The README derives the bare name from a torrent-relative path. */
    @Test
    fun `resolved file exposes the bare name`() {
        assertEquals("S00E01.mkv", ResolvedFile("specials/S00E01.mkv", 1, null).name)
        assertEquals("solo.mkv", ResolvedFile("solo.mkv", 1, null).name)
    }

    /** A range source only has to provide `read`; the README relies on that for custom sources. */
    @Test
    fun `a range source needs only one member`() {
        val source = object : RangeSource {
            override suspend fun <T> read(
                start: Long,
                length: Long,
                priority: Int,
                block: suspend (ByteReadChannel) -> T,
            ): T = block(ByteReadChannel(ByteArray(length.toInt())))
        }
        assertTrue(source is RangeSource)
    }
}
