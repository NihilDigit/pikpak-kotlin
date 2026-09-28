package io.github.nihildigit.pikpak

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The limiter under [RangeSource.downloadTo], on virtual time: the source answers instantly, so
 * every second that passes is one the limiter imposed, and the test runs in milliseconds.
 */
class BandwidthLimiterTest {
    private val scratch = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        scratch.forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
    }

    private val mib = 1024L * 1024

    // Two downloads with four connections each share one ceiling; limiting each connection or
    // each download instead would let the total run at eight or two times the setting
    @Test
    fun `concurrent downloads together stay under the ceiling`() = runTest {
        val limiter = BandwidthLimiter(mib, testScheduler.timeSource)
        val files = listOf(payload(2 * mib, 3), payload(2 * mib, 7))
        val started = testScheduler.timeSource.markNow()

        val dests = files.mapIndexed { i, body ->
            async { download(body, "limited-$i.bin", limiter) }
        }.awaitAll()
        val elapsed = started.elapsedNow()

        files.zip(dests).forEach { (body, dest) -> assertContentEquals(body, read(dest)) }
        // 4 MiB at 1 MiB/s, less the one second of credit a fresh bucket may hold and the block
        // granted on it; any more slack and a limit per download would pass as well
        assertTrue(elapsed >= 2.9.seconds, "4 MiB arrived in $elapsed under a 1 MiB/s ceiling")
        assertTrue(elapsed <= 4.2.seconds, "4 MiB took $elapsed; the limiter held back more than it should")
    }

    @Test
    fun `a changed rate applies to a download already waiting`() = runTest {
        val limiter = BandwidthLimiter(256 * 1024, testScheduler.timeSource)
        val started = testScheduler.timeSource.markNow()
        launch {
            delay(1.seconds)
            limiter.bytesPerSecond = null
        }
        val body = payload(4 * mib, 5)
        val dest = download(body, "lifted.bin", limiter)
        val elapsed = started.elapsedNow()

        assertContentEquals(body, read(dest))
        // 4 MiB at 256 KiB/s is 16 s; lifting the limit after one must end the wait then
        assertTrue(elapsed < 2.seconds, "lifting the limit mid-run still took $elapsed")
    }

    private suspend fun download(body: ByteArray, name: String, limiter: BandwidthLimiter): Path {
        val dir = Path("build", "dl-scratch")
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
        val dest = Path(dir, "bwl-$name").also { SystemFileSystem.delete(it, mustExist = false); scratch += it }
        InstantSource(body).downloadTo(dest, body.size.toLong(), concurrency = 4, blockSize = 64 * 1024, limiter = limiter)
        return dest
    }

    private fun payload(size: Long, seed: Int) = ByteArray(size.toInt()) { (it * seed % 251).toByte() }

    private fun read(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    /** Serves any range at once, so the only thing that can take time is the limiter. */
    private class InstantSource(private val body: ByteArray) : RangeSource {
        override suspend fun <T> read(start: Long, length: Long, priority: Int, block: suspend (ByteReadChannel) -> T): T {
            val end = minOf(body.size.toLong(), start + length).toInt()
            return block(ByteReadChannel(body.copyOfRange(start.toInt(), end)))
        }
    }
}
