package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.junit.jupiter.api.Assumptions

/**
 * What the fan-out is actually worth on the link you are on right now.
 *
 * Meant to be run by hand, on purpose, while throttling the connection — the
 * whole library exists for the case where one connection is slow, and that is
 * the case a normal dev machine never sees. Nothing here asserts a speedup:
 * on a short route one connection already saturates the line and the honest
 * answer is "no gain", which is a result and not a failure. The assertions
 * only catch things being broken.
 *
 * Needs `PIKPAK_WEAKLINK=1` on top of credentials, so a normal `jvmTest` does
 * not pay for it. Run it all:
 *
 *     PIKPAK_WEAKLINK=1 ./gradlew jvmTest --tests '*WeakLinkSmoke*' --rerun
 *
 * Or one at a time, which is the point of splitting them:
 *
 *     PIKPAK_WEAKLINK=1 ./gradlew jvmTest --tests '*WeakLinkSmoke*fan-out*' --rerun
 *
 * `--rerun` matters: Gradle skips a test whose inputs did not change, and the
 * link conditions are not an input it can see.
 *
 * The read budgets are seconds of wall clock, not bytes, so a run costs about
 * the same on a fast link and a crawling one. Knobs, all optional:
 *
 *  - PIKPAK_SMOKE_SECONDS   per read sample (default 6)
 *  - PIKPAK_SMOKE_ROUNDS    alternating rounds in the fan-out test (default 3)
 *  - PIKPAK_SMOKE_SLICE_MB  prefix the download test transfers (default 8)
 *  - PIKPAK_SMOKE_MAGNET    something other than the Arch ISO
 *
 * On a link throttled hard enough to matter, start with
 * `PIKPAK_SMOKE_SLICE_MB=2`: the download test transfers its prefix four
 * times, twice of them single-connection.
 */
class WeakLinkSmokeTest {

    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val magnet = env["PIKPAK_SMOKE_MAGNET"]?.takeIf { it.isNotBlank() } ?: TestFixtures.ARCH_ISO_MAGNET

    /**
     * Off unless asked for, like [CdnNetworkProbeTest]. These are measurements,
     * not regressions: they take tens of seconds, create and delete a file in
     * the account each time, and on a throttled link they would hold up every
     * other test for minutes.
     */
    private val enabled = env["PIKPAK_WEAKLINK"] == "1"

    private val sampleSeconds = env["PIKPAK_SMOKE_SECONDS"]?.toIntOrNull() ?: 6
    private val rounds = env["PIKPAK_SMOKE_ROUNDS"]?.toIntOrNull() ?: 3

    /**
     * Prefix size for the download comparison. Unlike the read samples this
     * one is a byte count, because a download either finishes or does not —
     * so on a crawling link lower it rather than waiting: at 0.2 MB/s the
     * 8 MiB default takes about 40 s per single-connection pass, and there
     * are two of those.
     */
    private val sliceBytes = (env["PIKPAK_SMOKE_SLICE_MB"]?.toLongOrNull() ?: 8L) shl 20

    private val budget: Duration get() = sampleSeconds.seconds

    // ───────────────────────────────────────────────────────────── fixtures

    private class Fixture(
        val client: PikPakClient,
        val handle: PikPakFileHandle,
        val scratch: String,
        val size: Long,
    )

    /**
     * Resolves the magnet, instant-creates the biggest file in it and hands
     * back a handle. Also the fast path's own timing, printed on the way.
     */
    private suspend fun open(label: String): Fixture? {
        val client = PikPakClient(username!!, password!!, sessionStore = InMemorySessionStore())
        val t0 = TimeSource.Monotonic.markNow()
        client.login()
        val loginAt = t0.elapsedNow()

        val resource = client.resolveMagnet(magnet)
        val resolveAt = t0.elapsedNow()
        if (resource == null) {
            println("[$label] PikPak's index does not hold this magnet; nothing to measure")
            client.close()
            return null
        }
        val target = resource.files.filter { it.gcid != null }.maxByOrNull { it.size }
        if (target == null) {
            println("[$label] no file in this magnet carries a gcid")
            client.close()
            return null
        }

        val scratch = client.createFolder(parentId = "", name = "pikpak-kotlin-weaklink-${Clock.System.now().toEpochMilliseconds()}")
        val fileId = client.instantCreate(target, parentId = scratch)
        val createAt = t0.elapsedNow()

        println(
            "[$label] ${target.name.take(60)} (${target.size / 1024 / 1024} MiB)  " +
                "login=$loginAt resolve=${resolveAt - loginAt} instantCreate=${createAt - resolveAt}",
        )

        val handle = PikPakFileHandle(client, target.gcid!!, target.size, target.name, initialFileId = fileId)
        return Fixture(client, handle, scratch, target.size)
    }

    private suspend fun Fixture.cleanUp() {
        handle.close()
        runCatching { client.deleteFile(scratch) }
        client.close()
    }

    /**
     * Reads for [budget] at [concurrency], starting at [from], and reports
     * MB/s. Blocks are issued in waves of [concurrency] so a wave is one
     * round trip's worth of work at that width; the loop stops at the first
     * wave boundary past the budget, so a very slow link overshoots by at
     * most one wave rather than being cut mid-read.
     */
    private suspend fun RangeSource.measure(from: Long, concurrency: Int, limit: Long): Double {
        val chunk = 256L * 1024
        var offset = from
        var bytes = 0L
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow() < budget && offset < limit) {
            val wave = coroutineScope {
                (0 until concurrency).mapNotNull { i ->
                    val start = offset + i * chunk
                    if (start + chunk > limit) null else async { readBytes(start, chunk, priority = 5).size.toLong() }
                }.awaitAll().sum()
            }
            bytes += wave
            offset += concurrency * chunk
            if (wave == 0L) break
        }
        val seconds = mark.elapsedNow().inWholeMilliseconds / 1000.0
        return bytes / seconds / (1024 * 1024)
    }

    private fun report(label: String, single: List<Double>, fanned: List<Double>) {
        val s = single.average()
        val f = fanned.average()
        // ASCII only in anything printed: a Windows console defaults to the
        // system code page, and box-drawing characters arrive as mojibake
        // there, which is a poor trade for a divider.
        println("[$label] == results over ${single.size} rounds ==")
        single.indices.forEach { i ->
            println("[$label]   round ${i + 1}: 1 conn %.2f MB/s   8 conn %.2f MB/s   x%.1f"
                .format(single[i], fanned[i], fanned[i] / single[i]))
        }
        println("[$label]   mean:    1 conn %.2f MB/s   8 conn %.2f MB/s   x%.1f".format(s, f, f / s))
        println(
            "[$label]   " + when {
                f / s >= 3 -> "fan-out is doing its job — this is the link the library is for"
                f / s >= 1.5 -> "some gain; the route is partly round-trip-bound"
                else -> "no real gain — one connection already saturates this route"
            },
        )
    }

    // ───────────────────────────────────────────────────────────── tests

    /**
     * The headline number: the same bytes, read one connection at a time and
     * eight at a time, alternating.
     *
     * Alternating rather than one block of each is the whole point. A weak
     * link drifts over tens of seconds, and measuring all of A then all of B
     * attributes that drift to the difference between A and B. Each round also
     * starts at a fresh offset so neither side is served from a warm edge.
     */
    @Test
    fun `fan-out versus one connection`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_WEAKLINK=1 to run the link measurements")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val f = open("fan-out") ?: return@runBlocking
        try {
            val single = mutableListOf<Double>()
            val fanned = mutableListOf<Double>()
            // Disjoint regions per round per width, far enough apart that
            // read-ahead from one never warms the next.
            var offset = 8L shl 20
            val stride = 24L shl 20
            repeat(rounds) {
                single += f.handle.measure(offset, concurrency = 1, limit = f.size)
                offset += stride
                fanned += f.handle.measure(offset, concurrency = 8, limit = f.size)
                offset += stride
            }
            report("fan-out", single, fanned)
            assertTrue(single.all { it > 0 } && fanned.all { it > 0 }, "a sample read nothing at all")
        } finally {
            f.cleanUp()
        }
    }

    /**
     * The playback path: how long until the first byte, how fast it settles,
     * and what a seek costs — the three numbers a viewer actually feels.
     */
    @Test
    fun `playback cold open and seek`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_WEAKLINK=1 to run the link measurements")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val f = open("playback") ?: return@runBlocking
        val stream = f.handle.openStream()
        try {
            val buf = ByteArray(64 * 1024)

            val coldMark = TimeSource.Monotonic.markNow()
            stream.seekTo(0)
            stream.read(buf, 0, buf.size)
            println("[playback] cold open to first byte: ${coldMark.elapsedNow()}")

            var delivered = 0L
            val steadyMark = TimeSource.Monotonic.markNow()
            while (steadyMark.elapsedNow() < budget) {
                val n = stream.read(buf, 0, buf.size)
                if (n <= 0) break
                delivered += n
            }
            val mbps = delivered / (steadyMark.elapsedNow().inWholeMilliseconds / 1000.0) / (1024 * 1024)
            println("[playback] steady state: %.2f MB/s over ${steadyMark.elapsedNow()}".format(mbps))

            val target = f.size / 2
            val seekMark = TimeSource.Monotonic.markNow()
            stream.seekTo(target)
            stream.read(buf, 0, buf.size)
            println("[playback] seek to mid-file, first byte after: ${seekMark.elapsedNow()}")
            println("[playback] reader-reported last seek latency: ${stream.lastSeekLatency}")

            assertTrue(delivered > 0, "playback delivered nothing")
        } finally {
            stream.close()
            f.cleanUp()
        }
    }

    /**
     * The download path, same file and same byte count at one connection and
     * at eight, so the comparison is one API against itself rather than two
     * implementations against each other.
     */
    @Test
    fun `downloadTo at one connection versus eight`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_WEAKLINK=1 to run the link measurements")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")
        val f = open("download") ?: return@runBlocking
        // A short prefix: totalSize is what the caller asks for, so a smaller
        // figure downloads a prefix and keeps a weak-link run bounded.
        val slice = minOf(f.size, sliceBytes)
        val dir = SystemFileSystem.resolve(Path("build")).toString()
        try {
            val results = mutableListOf<Pair<Int, Duration>>()
            for (concurrency in listOf(1, 8, 1, 8)) {
                val dest = Path(dir, "weaklink-$concurrency-${Clock.System.now().toEpochMilliseconds()}.bin")
                SystemFileSystem.delete(dest, mustExist = false)
                val mark = TimeSource.Monotonic.markNow()
                f.handle.downloadTo(dest, totalSize = slice, concurrency = concurrency, priority = 1)
                val took = mark.elapsedNow()
                results += concurrency to took
                val onDisk = SystemFileSystem.metadataOrNull(dest)?.size ?: -1
                assertTrue(onDisk == slice, "expected $slice bytes on disk, found $onDisk")
                SystemFileSystem.delete(dest, mustExist = false)
                println("[download] ${slice / 1024 / 1024} MiB at $concurrency conn: $took")
            }
            val one = results.filter { it.first == 1 }.map { it.second.inWholeMilliseconds }.average()
            val eight = results.filter { it.first == 8 }.map { it.second.inWholeMilliseconds }.average()
            println("[download] mean: 1 conn ${one.toLong()} ms, 8 conn ${eight.toLong()} ms, x%.1f".format(one / eight))
        } finally {
            f.cleanUp()
        }
    }
}
