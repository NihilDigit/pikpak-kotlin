package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.HostHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class HostHealthTest {
    @Test
    fun `ambiguous signed queries neither steer nor teach sibling hosts`() {
        for (query in listOf(
            "category=original&t=1", "category=transcoded&t=0", "category=unknown", "category=", "t=2",
            "category=original&category=transcoded", "t=0&t=1", "category=original&%74=1",
            "category=original&category=original", "category=original%", "category=%GG", "category=%FF",
            "category", "=original", "category=original&&t=0",
        )) {
            val health = HostHealth()
            val ambiguous = "https://dl-z01a-0001.example/file?$query"
            assertNull(HostHealth.poolOf(ambiguous), query)
            health.observe(FileDetail(webContentLink = ambiguous))
            for (suffix in listOf("t=0", "category=original", "t=1")) {
                val valid = "https://dl-z01a-0002.example/file?$suffix"
                assertEquals(valid, health.route(valid, true), query)
                health.observe(FileDetail(webContentLink = valid))
                assertEquals(ambiguous, health.route(ambiguous, true), query)
            }
        }
    }

    @Test
    fun `encoded query values classify without rewriting signatures or reading fragments`() {
        assertNull(HostHealth.poolOf("https://dl-z01a-0001.example:invalid/f?t=0"))
        assertEquals("original", HostHealth.poolOf("https://dl-z01a-0001.example/f?%63ategory=%6Friginal&t=%30"))
        assertEquals("transcoded", HostHealth.poolOf("https://dl-z01a-0001.example/f?t=%31"))
        assertEquals("download", HostHealth.poolOf("https://dl-z01a-0001.example/f?t=0#category=original&t=1"))
        val health = HostHealth()
        health.observe(FileDetail(webContentLink = "https://dl-z01a-0002.example/f?category=original"))
        val url = "https://dl-z01a-0001.example/f?category=%6Friginal&sign=a%2Bb%2Fc#t=1"
        assertEquals(url, health.route(url, true))
        for (invalid in listOf(
            "ftp://dl-z01a-0001.example/f?t=0", "https://user@dl-z01a-0001.example/f?t=0",
            "https://dl-z01a-0001.example:443/f?t=0", "https://dl-z01a-0001.example/f?category=orig inal",
        )) assertEquals(invalid, health.route(invalid, true))
    }

    @Test
    fun `original download and transcoded candidates never cross service pools`() {
        val health = HostHealth()
        val download = "https://dl-z01a-0101.example/file?g=G&t=0"
        val original = "https://dl-z01a-0202.example/file?g=G&t=0&category=original"
        val transcoded = "https://dl-z01a-0303.example/file?g=G&t=1"
        health.observe(FileDetail(webContentLink = download, medias = listOf(
            MediaVariant(isOrigin = true, link = DownloadLink(original)),
            MediaVariant(link = DownloadLink(transcoded)))))
        for (url in listOf(download, original, transcoded)) {
            assertEquals(url, health.route(url, true))
            assertFalse(health.hasHealthySibling(url))
        }
        val sibling = original.replace("0202", "0404")
        health.observe(FileDetail(medias = listOf(MediaVariant(isOrigin = true, link = DownloadLink(sibling)))))
        assertEquals(original, health.route(original, true))
        assertEquals(download, health.route(download, true))
    }

    @Test
    fun `same host throughput and refusals are isolated by signed service class`() {
        val health = HostHealth()
        val original = "https://dl-z01a-0101.example/file?category=original"
        val sibling = original.replace("0101", "0202")
        val downloadSibling = sibling.substringBefore('?')
        health.observe(FileDetail(medias = listOf(MediaVariant(isOrigin = true, link = DownloadLink(sibling)))))
        repeat(2) {
            health.delivered(HostHealth.hostOf(original), HostHealth.MIN_SAMPLE_BYTES, 300_000, original)
            health.delivered(HostHealth.hostOf(downloadSibling), HostHealth.MIN_SAMPLE_BYTES, 12_000_000, downloadSibling)
        }
        assertEquals(original, health.route(original, false))
        health.refused(downloadSibling)
        repeat(2) { health.delivered(HostHealth.hostOf(sibling), HostHealth.MIN_SAMPLE_BYTES, 10_000_000, sibling) }
        health.refused(original)
        assertEquals(sibling, health.route(original, false))
    }

    // A dropped Wi-Fi silences every request at once; marking those hosts would blacklist the pool
    @Test
    fun `a silence with no other host answering is not held against the host`() {
        val health = HostHealth()
        health.markSilent("https://a.example/file", HostHealth.MIN_DEADLINE)
        assertFalse(health.isBad("https://a.example/other"))
    }

    @Test
    fun `a silence while another host answers marks the host for every link on it`() {
        val health = HostHealth()
        health.answered("https://b.example/file")
        health.markSilent("https://a.example/file?sig=1", HostHealth.MIN_DEADLINE)
        assertTrue(health.isBad("https://a.example/another?sig=2"))
        assertFalse(health.isBad("https://b.example/file"))
    }

    @Test
    fun `the first-response deadline follows the line and keeps its floor on a fast one`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        repeat(HostHealth.MIN_LATENCY_SAMPLES) { health.answered("https://a.example/f", 400.milliseconds) }
        assertEquals(HostHealth.MIN_DEADLINE, health.firstResponseDeadline())

        repeat(HostHealth.MAX_LATENCY_SAMPLES) { health.answered("https://a.example/f", 1500.milliseconds) }
        assertEquals(3.seconds, health.firstResponseDeadline())

        // A proxy switched on: the slow figures age out instead of holding the deadline up
        time += HostHealth.LATENCY_MEMORY
        assertEquals(HostHealth.INITIAL_DEADLINE, health.firstResponseDeadline())
    }

    // Pooled connections answer in one round trip and are most of the samples; a new connection
    // spends seconds in its handshake, never answers within a deadline set by the pooled ones,
    // and so would never be counted at all
    @Test
    fun `new connections cut off by the deadline lift it past their handshake`() {
        val health = HostHealth()
        repeat(24) { health.answered("https://a.example/f", 400.milliseconds) }
        repeat(8) { health.markSilent("https://a.example/f", health.firstResponseDeadline()) }
        assertTrue(health.firstResponseDeadline() > 3.seconds, "deadline ${health.firstResponseDeadline()}")
    }

    /** Epoch fixtures explicitly describe pooled RTTs rather than handshake waits. */
    private fun HostHealth.warmAnswer(url: String, after: Duration) = answered(url, after, connectionReused = true)

    private val link = "https://dl-z01a-0001.mypikpak.com/download/?fid=F&sign=S"

    private fun HostHealth.measure(host: String, bytesPerSecond: Long) =
        repeat(HostHealth.MIN_SAMPLES) { delivered(host, HostHealth.MIN_SAMPLE_BYTES, bytesPerSecond) }

    @Test
    fun `a sibling that refused a link is not a destination however fast`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        health.measure("dl-z01a-0001.mypikpak.com", 170_000)
        health.measure("dl-z01a-0002.mypikpak.com", 5_000_000)
        health.measure("dl-z01a-0003.mypikpak.com", 2_500_000)
        health.refused("https://dl-z01a-0002.mypikpak.com/other")

        health.refused(link)
        assertEquals("https://dl-z01a-0003.mypikpak.com/download/?fid=F&sign=S", health.route(link, mayExplore = false))
    }

    // Exploring is what finds a slow host at all, but an unmeasured sibling may be one of the slow
    // ones, so it is kept off blocking reads, rationed, and within the link's family and root

    @Test
    fun `healthy issued routes are not replaced based on request length`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        val sibling = link.replace("0001", "0002")
        health.measure("dl-z01a-0001.mypikpak.com", 1024 * 1024)
        health.measure("dl-z01a-0002.mypikpak.com", 10 * 1024 * 1024)
        repeat(2) {
            health.answered(link, 50.milliseconds)
            health.answered(sibling, 1350.milliseconds)
        }
        assertEquals(link, health.route(link, false, 64 * 1024))
        time += HostHealth.ROUTE_LEASE
        assertEquals(link, health.route(link, false, 64 * 1024 * 1024))
    }

    @Test
    fun `hosts outside the dl family shape are never rerouted`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002"))
        val other = "https://vod0001-aliyun.mypikpak.com/file?sign=S"
        assertEquals(other, health.route(other, mayExplore = true))
        val withPort = "https://dl-z01a-0001.mypikpak.com:8443/file"
        assertEquals(withPort, health.route(withPort, mayExplore = true))
    }

    @Test
    fun `steering off keeps every attempt on the link`() {
        val health = HostHealth(steering = false, seeds = listOf("dl-z01a-0002"))
        health.measure("dl-z01a-0001.mypikpak.com", 170_000)
        health.measure("dl-z01a-0002.mypikpak.com", 5_000_000)
        assertEquals(link, health.route(link, mayExplore = true))
    }

    @Test
    fun `a host that just answered another connection is not marked silent by a single timeout`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        health.answered("https://b.example/file")
        health.answered("https://a.example/file")
        time += 500.milliseconds
        health.markSilent("https://a.example/file", HostHealth.MIN_DEADLINE)
        assertFalse(health.isBad("https://a.example/file"))
    }

    @Test
    fun `cold host gets handshake headroom while warm pooled host keeps tight deadline`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        val warmUrl = "https://dl-z01a-0002.mypikpak.com/download/?fid=F"
        val coldUrl = "https://dl-z01a-0003.mypikpak.com/download/?fid=F"

        repeat(HostHealth.MIN_LATENCY_SAMPLES) {
            health.onRequestStarted(warmUrl)
            health.answered(warmUrl, 400.milliseconds)
            health.onSocketReturned(warmUrl)
        }

        assertEquals(HostHealth.MIN_DEADLINE, health.firstResponseDeadline(warmUrl))
        assertTrue(
            health.firstResponseDeadline(coldUrl) >= HostHealth.INITIAL_DEADLINE,
            "cold deadline=${health.firstResponseDeadline(coldUrl)}",
        )
        assertEquals(HostHealth.URGENT_BODY_SILENCE, health.bodySilenceDeadline(PikPakStreamReader.BLOCKING_PRIORITY))
        assertEquals(HostHealth.BODY_SILENCE, health.bodySilenceDeadline(PikPakStreamReader.READ_AHEAD_PRIORITY))
    }

    @Test
    fun `route epoch needs three clean samples separate from historical baseline`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        repeat(3) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        repeat(2) { health.warmAnswer(link, 1.seconds) }
        assertTrue(health.hasHealthySibling(link))
        health.warmAnswer(link, 1.seconds)
        assertFalse(health.hasHealthySibling(link))
        // Exactly the three new samples survive; five more meet the deadline sample floor.
        assertEquals(HostHealth.INITIAL_DEADLINE, health.firstResponseDeadline())
        repeat(5) { health.answered(link, 1.seconds) }
        assertEquals(2.seconds, health.firstResponseDeadline())
    }

    @Test
    fun `route epoch requires strict ratio and minimum absolute change`() {
        for ((baseline, next) in listOf(400 to 1000, 1000 to 400, 50 to 200)) {
            val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
            repeat(8) { health.warmAnswer(link, baseline.milliseconds) }
            health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
            repeat(3) { health.warmAnswer(link, next.milliseconds) }
            assertTrue(health.hasHealthySibling(link), "$baseline to $next must not reset")
        }
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        repeat(3) { health.warmAnswer(link, 350.milliseconds) }
        assertFalse(health.hasHealthySibling(link), "exactly 250 ms change resets")
    }

    @Test
    fun `timeouts retries isolated outliers and expired history do not reset route epoch`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        repeat(3) {
            health.markSilent(link, 3.seconds)
            health.answered(link)
        }
        health.warmAnswer(link, 2.seconds)
        health.warmAnswer(link, 100.milliseconds)
        health.warmAnswer(link, 100.milliseconds)
        assertTrue(health.hasHealthySibling(link))
        time += HostHealth.LATENCY_MEMORY
        repeat(3) { health.warmAnswer(link, 2.seconds) }
        assertTrue(health.hasHealthySibling(link))
    }

    @Test
    fun `cold and unknown connection waits never reset warm route evidence`() {
        for (reused in listOf(false, null)) {
            val health = HostHealth(seeds = listOf("dl-z01a-0002"))
            repeat(8) { health.warmAnswer(link, 100.milliseconds) }
            health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
            repeat(8) { health.answered(link, 4.seconds, connectionReused = reused) }
            assertTrue(health.hasHealthySibling(link))
            assertEquals(8.seconds, health.firstResponseDeadline())
            assertEquals(HostHealth.URGENT_BODY_SILENCE, health.bodySilenceDeadline(PikPakStreamReader.BLOCKING_PRIORITY))
            repeat(3) { health.warmAnswer(link, 1.seconds) }
            assertFalse(health.hasHealthySibling(link))
        }
    }

    // elapsedNow is a deterministic interleaving point inside evidence reads, without threads
    // or exposing mutable production state to tests. Clear first so nested reads cannot recurse.
    private class InterleavingTimeSource : TimeSource {
        private val clock = TestTimeSource()
        var onElapsed: (() -> Unit)? = null

        override fun markNow(): TimeMark {
            val mark = clock.markNow()
            return object : TimeMark {
                override fun elapsedNow(): Duration {
                    val action = onElapsed
                    onElapsed = null
                    action?.invoke()
                    return mark.elapsedNow()
                }
            }
        }
    }

    @Test
    fun `epoch boundary uses the median of three rather than requiring three changed waits`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        health.warmAnswer(link, 350.milliseconds)
        assertTrue(health.hasHealthySibling(link))
        health.warmAnswer(link, 350.milliseconds)
        assertFalse(health.hasHealthySibling(link))
    }

    @Test
    fun `mid-body stalled host is recorded and routed to usable sibling even below sample byte floor`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        val goodSibling = "https://dl-z01a-0002.mypikpak.com/download/?fid=F&sign=S"
        health.observe(FileDetail(webContentLink = goodSibling))
        health.answered(goodSibling, 250.milliseconds)
        health.delivered(HostHealth.hostOf(goodSibling), HostHealth.MIN_SAMPLE_BYTES, 1_000_000, goodSibling)

        // 0001 answered promptly, sent only 100 KiB (< MIN_SAMPLE_BYTES), then stalled for 3 s
        health.answered(link, 200.milliseconds)
        time += 3.seconds
        health.stalled(link, deliveredBytes = 100 * 1024L, bodyBytesPerSecond = 34 * 1024L, silenceDuration = 3.seconds)

        assertTrue(health.isBad(link))
        assertEquals(goodSibling, health.route(link, mayExplore = false))

        // Concurrent requests on the same host receiving HTTP 206 headers must not clear a mid-body stall
        health.answered(link, 150.milliseconds)
        assertTrue(health.isBad(link))
        assertEquals(goodSibling, health.route(link, mayExplore = false))

        // A short successful burst does not erase the preceding body stall
        health.delivered(HostHealth.hostOf(link), HostHealth.MIN_SAMPLE_BYTES, HostHealth.GOOD_RATE_BYTES_PER_SEC, link)
        assertTrue(health.isBad(link))
    }

    @Test
    fun `fast burst followed by mid-body silence still penalizes stalled host and does not hijack faster link`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        val fastPrimary = "https://dl-z01a-0063.mypikpak.com/download/?fid=F&sign=S"
        val flakySibling = "https://dl-z01a-0027.mypikpak.com/download/?fid=F&sign=S"
        health.observe(
            FileDetail(
                webContentLink = fastPrimary,
                medias = listOf(MediaVariant(link = DownloadLink(url = flakySibling))),
            ),
        )
        // Primary link 0063 has a strong measured throughput (6 MiB/s)
        health.measure("dl-z01a-0063.mypikpak.com", 6_000_000)
        // Flaky sibling 0027 had one decent 1.5 MiB/s sample, then delivered 1.1 MiB at 2.5 MiB/s burst before hanging 6.5 s
        health.measure("dl-z01a-0027.mypikpak.com", 1_500_000)
        health.stalled(
            flakySibling,
            deliveredBytes = 1_100 * 1024L,
            bodyBytesPerSecond = 2_500_000L,
            silenceDuration = 6500.milliseconds,
        )
        // Even on the first mid-body hang (suspects), primary link 0063 must remain on 0063 and never be hijacked onto 0027
        assertEquals(fastPrimary, health.route(fastPrimary, mayExplore = false))
        // A second mid-body hang escalates 0027 from suspect to stalled (isBad)
        health.stalled(
            flakySibling,
            deliveredBytes = 1_100 * 1024L,
            bodyBytesPerSecond = 2_500_000L,
            silenceDuration = 6500.milliseconds,
        )
        assertTrue(health.isBad(flakySibling))
        assertEquals(fastPrimary, health.route(fastPrimary, mayExplore = false))
    }

    @Test
    fun `stalled host is marked bad across pools and fails over to unmeasured usable sibling`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        val originalStalled = "https://dl-z01a-0054.mypikpak.com/download/?fid=F&category=original"
        val transcodedStalled = "https://dl-z01a-0054.mypikpak.com/download/?fid=F&t=1"
        val transcodedClean = "https://dl-z01a-0042.mypikpak.com/download/?fid=F&t=1"
        health.observe(
            FileDetail(
                medias = listOf(
                    MediaVariant(isOrigin = true, link = DownloadLink(url = originalStalled)),
                    MediaVariant(mediaId = "720", link = DownloadLink(url = transcodedStalled)),
                    MediaVariant(mediaId = "1080", link = DownloadLink(url = transcodedClean)),
                ),
            ),
        )
        health.stalled(originalStalled, deliveredBytes = 128 * 1024L, bodyBytesPerSecond = 20 * 1024L, silenceDuration = 3.seconds)
        assertTrue(health.isBad(transcodedStalled))
        assertEquals(transcodedClean, health.route(transcodedStalled, mayExplore = false))
    }

    @Test
    fun `sharded archive dl-a10b urls are never rerouted across sibling nodes`() {
        val health = HostHealth(seeds = listOf("dl-a10b-0861", "dl-a10b-1533"))
        val shardA = "https://dl-a10b-1556.mypikpak.com/download/?fid=F&t=1"
        val shardB = "https://dl-a10b-0861.mypikpak.com/download/?fid=F&t=1"
        health.observe(
            FileDetail(
                medias = listOf(
                    MediaVariant(mediaId = "720", link = DownloadLink(url = shardA)),
                    MediaVariant(mediaId = "480", link = DownloadLink(url = shardB)),
                ),
            ),
        )
        health.measure("dl-a10b-0861.mypikpak.com", 10_000_000)
        assertEquals(shardA, health.route(shardA, mayExplore = true))
    }

    @Test
    fun `proven fast host survives first transient body stall as suspect before second stall marks it bad`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = HostHealth.DEFAULT_DL_Z01A_SEEDS)
        val fastOctetTranscode = "https://dl-z01a-0048.mypikpak.com/download/?fid=F&t=1"
        repeat(HostHealth.MIN_SAMPLES) {
            health.delivered(HostHealth.hostOf(fastOctetTranscode), HostHealth.MIN_SAMPLE_BYTES, 4_000_000, fastOctetTranscode)
        }
        health.stalled(
            fastOctetTranscode,
            deliveredBytes = 64 * 1024L,
            bodyBytesPerSecond = 32 * 1024L,
            silenceDuration = 2.seconds,
        )
        assertFalse(health.isBad(fastOctetTranscode))
        assertEquals(fastOctetTranscode, health.route(fastOctetTranscode, mayExplore = false))

        // After SUSPECT_WINDOW elapses without another stall, a later isolated stall also remains suspect.
        time += HostHealth.SUSPECT_WINDOW
        repeat(HostHealth.MIN_SAMPLES) {
            health.delivered(HostHealth.hostOf(fastOctetTranscode), HostHealth.MIN_SAMPLE_BYTES, 4_000_000, fastOctetTranscode)
        }
        health.stalled(
            fastOctetTranscode,
            deliveredBytes = 64 * 1024L,
            bodyBytesPerSecond = 32 * 1024L,
            silenceDuration = 2.seconds,
        )
        assertFalse(health.isBad(fastOctetTranscode))

        // A second stall inside SUSPECT_WINDOW escalates the host to bad and fails over to a clean seed.
        health.stalled(
            fastOctetTranscode,
            deliveredBytes = 64 * 1024L,
            bodyBytesPerSecond = 32 * 1024L,
            silenceDuration = 2.seconds,
        )
        assertTrue(health.isBad(fastOctetTranscode))
        assertEquals("https://dl-z01a-0041.mypikpak.com/download/?fid=F&t=1", health.route(fastOctetTranscode, mayExplore = false))
    }

    @Test
    fun `unproven congested dl-z01a transcode link proactively steers to non-congested seed unless proven fast`() {
        val health = HostHealth(seeds = HostHealth.DEFAULT_DL_Z01A_SEEDS)
        val congestedTranscode = "https://dl-z01a-0054.mypikpak.com/download/?fid=F&t=1"
        assertEquals(
            "https://dl-z01a-0041.mypikpak.com/download/?fid=F&t=1",
            health.route(congestedTranscode, mayExplore = false),
        )

        // If the congested label itself has proven good throughput on this line, do not force a rewrite.
        repeat(HostHealth.MIN_SAMPLES) {
            health.delivered(HostHealth.hostOf(congestedTranscode), HostHealth.MIN_SAMPLE_BYTES, 3_000_000, congestedTranscode)
        }
        assertEquals(congestedTranscode, health.route(congestedTranscode, mayExplore = false))
    }
}
