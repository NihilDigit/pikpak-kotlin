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
        assertEquals(url.replace("0001", "0002"), health.route(url, true))
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
        assertEquals(sibling, health.route(original, true))
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
        assertEquals(sibling, health.route(original, false))
    }

    @Test
    fun `unknown demand does not declare slow window limited routes sufficient`() {
        for (rate in listOf(180L, 300L, 820L)) {
            val health = HostHealth()
            val sibling = link.replace("0001", "0002")
            health.observe(FileDetail(webContentLink = sibling))
            health.measure(HostHealth.hostOf(link)!!, rate * 1024)
            assertEquals(sibling, health.route(link, true))
        }
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
    fun `measured completion benefit selects the faster sibling`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        health.measure("dl-z01a-0001.mypikpak.com", 170_000)
        health.measure("dl-z01a-0002.mypikpak.com", 5_000_000)
        health.measure("dl-z01a-0003.mypikpak.com", 2_500_000)

        assertEquals("https://dl-z01a-0002.mypikpak.com/download/?fid=F&sign=S", health.route(link, mayExplore = false))
        val halfAsFast = "https://dl-z01a-0003.mypikpak.com/download/?fid=F&sign=S"
        assertEquals(link.replace("0001", "0002"), health.route(halfAsFast, mayExplore = false))
    }

    @Test
    fun `a sibling that refused a link is not a destination however fast`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        health.measure("dl-z01a-0001.mypikpak.com", 170_000)
        health.measure("dl-z01a-0002.mypikpak.com", 5_000_000)
        health.measure("dl-z01a-0003.mypikpak.com", 2_500_000)
        health.refused("https://dl-z01a-0002.mypikpak.com/other")

        assertEquals("https://dl-z01a-0003.mypikpak.com/download/?fid=F&sign=S", health.route(link, mayExplore = false))
    }

    // Exploring is what finds a slow host at all, but an unmeasured sibling may be one of the slow
    // ones, so it is kept off blocking reads, rationed, and within the link's family and root
    @Test
    fun `only background reads explore and only one per interval within the family and root`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-a10b-0005", "dl-z01a-0002", "dl-z01a-0003"))
        val onOtherRoot = "https://dl-z01a-0001.pikpak.me/download/?fid=F&sign=S"

        assertEquals(onOtherRoot, health.route(onOtherRoot, mayExplore = false))
        assertEquals("https://dl-z01a-0002.pikpak.me/download/?fid=F&sign=S", health.route(onOtherRoot, mayExplore = true))
        assertEquals(onOtherRoot, health.route(onOtherRoot, mayExplore = true))

        time += HostHealth.EXPLORE_INTERVAL
        // 0002 has not delivered yet, so it is still the one being measured
        assertEquals("https://dl-z01a-0002.pikpak.me/download/?fid=F&sign=S", health.route(onOtherRoot, mayExplore = true))
    }

    @Test
    fun `exploring stops once enough of the family has figures`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002", "dl-z01a-0003", "dl-z01a-0004"))
        health.measure("dl-z01a-0001.mypikpak.com", 3_000_000)
        health.measure("dl-z01a-0002.mypikpak.com", 3_000_000)
        health.measure("dl-z01a-0003.mypikpak.com", 3_000_000)

        assertEquals(link, health.route(link, mayExplore = true))
    }

    @Test
    fun `steady viable throughput does not explore unknown siblings`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        health.measure("dl-z01a-0001.mypikpak.com", 350 * 1024)
        health.answered(link, 1350.milliseconds)
        assertEquals(link, health.route(link, true, demandBytesPerSecond = 175L * 1024))
        assertEquals(link, health.route(link, true, demandBytesPerSecond = 175L * 1024))
        time += 1.seconds
        assertEquals(link, health.route(link, true, demandBytesPerSecond = 175L * 1024))
    }

    @Test
    fun `small requests prefer latency while large requests prefer body rate`() {
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
        assertEquals(sibling, health.route(link, false, 64 * 1024 * 1024))
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
    fun `unmeasured sibling links reuse sticky preferred host until its samples age out`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        health.observe(
            FileDetail(
                webContentLink = "https://dl-z01a-0002.mypikpak.com/download/?fid=F",
                medias = listOf(MediaVariant(link = DownloadLink(url = "https://dl-z01a-0003.mypikpak.com/download/?fid=F"))),
            ),
        )
        health.measure("dl-z01a-0002.mypikpak.com", 1_200_000)

        assertEquals(
            "https://dl-z01a-0002.mypikpak.com/download/?fid=F&sign=S",
            health.route(link, mayExplore = false),
        )
        time += HostHealth.DEFAULT_MEMORY
        assertEquals(link, health.route(link, mayExplore = false))
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
    fun `route epoch resets speed preferences silences and refusals but keeps learned hosts`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time)
        val sibling = link.replace("0001", "0002")
        val silent = link.replace("0001", "0003")
        health.observe(FileDetail(webContentLink = sibling))
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0001.mypikpak.com", 200_000)
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, false))
        health.refused(sibling)
        health.markSilent(silent, 2.seconds)
        assertTrue(health.isBad(silent))

        repeat(3) { health.warmAnswer(link, 1.seconds) }

        assertFalse(health.isBad(silent))
        assertFalse(health.hasHealthySibling(link))
        assertEquals(link, health.route(link, false))
        assertEquals(sibling, health.route(link, true))
        // The old timeout and fast samples no longer contribute to the response deadline.
        repeat(5) { health.answered(link, 1.seconds) }
        assertEquals(2.seconds, health.firstResponseDeadline())
    }

    @Test
    fun `route epoch also detects a faster route and discards old host latency`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        val sibling = link.replace("0001", "0002")
        repeat(8) { health.warmAnswer(sibling, 2.seconds) }
        health.measure("dl-z01a-0001.mypikpak.com", 200_000)
        repeat(3) { health.warmAnswer(link, 100.milliseconds) }
        // Relearn only rates. The sibling's old two-second setup cost must be gone.
        health.measure("dl-z01a-0001.mypikpak.com", 200_000)
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, false))
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
    fun `high latency foreground suppresses cold exploration at the viable rate boundary`() {
        for (priority in listOf(PikPakStreamReader.READ_AHEAD_PRIORITY, PikPakStreamReader.BLOCKING_PRIORITY)) {
            val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
            repeat(3) { health.warmAnswer(link, 900.milliseconds) }
            health.measure("dl-z01a-0001.mypikpak.com", 175L * 1024)
            assertEquals(link, health.route(link, true, priority = priority, demandBytesPerSecond = 175L * 1024))
            assertEquals(link, health.route(link, true, priority = 0, demandBytesPerSecond = 175L * 1024))
        }
    }

    @Test
    fun `foreground explores below viable rate regardless of latency`() {
        for ((latency, rate, expired) in listOf(
            Triple(899, 175L * 1024 - 1, false),
            Triple(900, 175L * 1024 - 1, false),
            Triple(900, 175L * 1024 - 1, true),
        )) {
            val time = TestTimeSource()
            val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
            repeat(3) { health.warmAnswer(link, latency.milliseconds) }
            health.measure("dl-z01a-0001.mypikpak.com", rate)
            if (expired) time += HostHealth.LATENCY_MEMORY
            assertEquals(
                link.replace("0001", "0002"),
                health.route(link, true, priority = PikPakStreamReader.READ_AHEAD_PRIORITY),
            )
        }
    }

    @Test
    fun `viable foreground skips even warm exploration but accepts measured improvement`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        val sibling = link.replace("0001", "0002")
        repeat(3) { health.warmAnswer(link, 1.seconds) }
        health.measure("dl-z01a-0001.mypikpak.com", 175L * 1024)
        health.onSocketReturned(sibling)
        assertEquals(link, health.route(link, true, priority = PikPakStreamReader.READ_AHEAD_PRIORITY, demandBytesPerSecond = 175L * 1024))
        health.onRequestStarted(sibling)
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, true, priority = PikPakStreamReader.READ_AHEAD_PRIORITY))
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

    @Test
    fun `healthy viable preferred host stays leased for thirty seconds`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        val sibling = link.replace("0001", "0002")
        health.measure("dl-z01a-0001.mypikpak.com", 100_000)
        health.measure("dl-z01a-0002.mypikpak.com", 1_000_000)
        assertEquals(sibling, health.route(link, false))
        health.measure("dl-z01a-0003.mypikpak.com", 10_000_000)
        time += 29.seconds
        assertEquals(sibling, health.route(link, false))
        time += 1.seconds
        assertEquals(link.replace("0001", "0003"), health.route(link, false))
    }

    @Test
    fun `refusal breaks the preferred lease immediately`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        val sibling = link.replace("0001", "0002")
        health.measure("dl-z01a-0002.mypikpak.com", 1_000_000)
        assertEquals(sibling, health.route(link, false))
        health.refused(sibling)
        assertEquals(link, health.route(link, false))
    }

    @Test
    fun `hungry foreground suppresses exploration without spending its slot`() {
        var hungry = true
        val health = HostHealth(seeds = listOf("dl-z01a-0002"), foregroundHungry = { hungry })
        assertEquals(link, health.route(link, true))
        hungry = false
        assertEquals(link.replace("0001", "0002"), health.route(link, true))
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
    fun `route keeps one evidence snapshot and cannot overwrite a newer epoch preference`() {
        val time = InterleavingTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        val second = link.replace("0001", "0002")
        val third = link.replace("0001", "0003")
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)

        time.onElapsed = {
            repeat(3) { health.warmAnswer(link, 1.seconds) }
            health.measure("dl-z01a-0003.mypikpak.com", 2_000_000)
            assertEquals(third, health.route(link, false))
        }
        // The in-flight decision is valid for its original snapshot, including preferred cost.
        assertEquals(second, health.route(link, false))
        // Make the old preferred host eligible again. Only the new epoch's sticky choice wins.
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(third, health.route(link, false))
    }

    @Test
    fun `preferred completion cost survives an epoch reset during route evidence reads`() {
        val time = InterleavingTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        val sibling = link.replace("0001", "0002")
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, false))
        time.onElapsed = { repeat(3) { health.warmAnswer(link, 1.seconds) } }
        assertEquals(sibling, health.route(link, false))
        assertEquals(link, health.route(link, false))
    }

    @Test
    fun `clean latency CAS retry preserves observations already published in the new epoch`() {
        val time = InterleavingTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.warmAnswer(link, 100.milliseconds) }
        time.onElapsed = {
            repeat(3) { health.warmAnswer(link, 1.seconds) }
            health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        }
        health.warmAnswer(link, 1.seconds)
        assertTrue(health.hasHealthySibling(link))
        assertEquals(link.replace("0001", "0002"), health.route(link, false))
        repeat(4) { health.answered(link, 1.seconds) }
        assertEquals(2.seconds, health.firstResponseDeadline())
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

        // Only a completed transfer at a viable rate clears the mid-body stall penalty
        health.delivered(HostHealth.hostOf(link), HostHealth.MIN_SAMPLE_BYTES, HostHealth.GOOD_RATE_BYTES_PER_SEC, link)
        assertFalse(health.isBad(link))
    }

    @Test
    fun `cluster wide stall advances through usable siblings before returning to primary`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        val second = link.replace("0001", "0002")
        val third = link.replace("0001", "0003")

        // First stalled host fails over to the first usable sibling
        health.stalled(link, deliveredBytes = 64 * 1024L, bodyBytesPerSecond = 20 * 1024L, silenceDuration = 3.seconds)
        assertEquals(second, health.route(link, mayExplore = false))

        // If the first sibling also stalls, advance to the next usable sibling rather than returning to stalled 0001
        health.stalled(second, deliveredBytes = 0L, bodyBytesPerSecond = 0L, silenceDuration = 3.seconds)
        assertEquals(third, health.route(link, mayExplore = false))

        // Once all siblings are stalled, fall back to the primary link
        health.stalled(third, deliveredBytes = 0L, bodyBytesPerSecond = 0L, silenceDuration = 3.seconds)
        assertEquals(link, health.route(link, mayExplore = false))
    }
}
