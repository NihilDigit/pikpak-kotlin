package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.HostHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class HostHealthTest {
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
    fun `steady moderate throughput still explores unknown siblings`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        health.measure("dl-z01a-0001.mypikpak.com", 350 * 1024)
        health.answered(link, 1350.milliseconds)
        assertEquals(link.replace("0001", "0002"), health.route(link, true))
        assertEquals(link, health.route(link, true))
        time += 1.seconds
        assertEquals(link, health.route(link, true))
    }

    @Test
    fun `small requests prefer latency while large requests prefer body rate`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002"))
        val sibling = link.replace("0001", "0002")
        health.measure("dl-z01a-0001.mypikpak.com", 1024 * 1024)
        health.measure("dl-z01a-0002.mypikpak.com", 10 * 1024 * 1024)
        repeat(2) {
            health.answered(link, 50.milliseconds)
            health.answered(sibling, 1350.milliseconds)
        }
        assertEquals(link, health.route(link, false, 64 * 1024))
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
        repeat(8) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0001.mypikpak.com", 200_000)
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, false))
        health.refused(sibling)
        health.markSilent(silent, 2.seconds)
        assertTrue(health.isBad(silent))

        repeat(3) { health.answered(link, 1.seconds) }

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
        repeat(8) { health.answered(sibling, 2.seconds) }
        health.measure("dl-z01a-0001.mypikpak.com", 200_000)
        repeat(3) { health.answered(link, 100.milliseconds) }
        // Relearn only rates. The sibling's old two-second setup cost must be gone.
        health.measure("dl-z01a-0001.mypikpak.com", 200_000)
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, false))
    }

    @Test
    fun `route epoch needs three clean samples separate from historical baseline`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        repeat(3) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        repeat(2) { health.answered(link, 1.seconds) }
        assertTrue(health.hasHealthySibling(link))
        health.answered(link, 1.seconds)
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
            repeat(8) { health.answered(link, baseline.milliseconds) }
            health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
            repeat(3) { health.answered(link, next.milliseconds) }
            assertTrue(health.hasHealthySibling(link), "$baseline to $next must not reset")
        }
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        repeat(3) { health.answered(link, 350.milliseconds) }
        assertFalse(health.hasHealthySibling(link), "exactly 250 ms change resets")
    }

    @Test
    fun `timeouts retries isolated outliers and expired history do not reset route epoch`() {
        val time = TestTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        repeat(3) {
            health.markSilent(link, 3.seconds)
            health.answered(link)
        }
        health.answered(link, 2.seconds)
        health.answered(link, 100.milliseconds)
        health.answered(link, 100.milliseconds)
        assertTrue(health.hasHealthySibling(link))
        time += HostHealth.LATENCY_MEMORY
        repeat(3) { health.answered(link, 2.seconds) }
        assertTrue(health.hasHealthySibling(link))
    }

    @Test
    fun `high latency foreground suppresses cold exploration at the viable rate boundary`() {
        for (priority in listOf(PikPakStreamReader.READ_AHEAD_PRIORITY, PikPakStreamReader.BLOCKING_PRIORITY)) {
            val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
            repeat(3) { health.answered(link, 900.milliseconds) }
            health.measure("dl-z01a-0001.mypikpak.com", 175L * 1024)
            assertEquals(link, health.route(link, true, priority = priority))
            // Suppression must not spend the background exploration slot.
            assertEquals(link.replace("0001", "0002"), health.route(link, true, priority = 0))
        }
    }

    @Test
    fun `foreground still explores on low latency slow hosts or expired latency`() {
        for ((latency, rate, expired) in listOf(
            Triple(899, 175L * 1024, false),
            Triple(900, 175L * 1024 - 1, false),
            Triple(900, 175L * 1024, true),
        )) {
            val time = TestTimeSource()
            val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
            repeat(3) { health.answered(link, latency.milliseconds) }
            health.measure("dl-z01a-0001.mypikpak.com", rate)
            if (expired) time += HostHealth.LATENCY_MEMORY
            assertEquals(
                link.replace("0001", "0002"),
                health.route(link, true, priority = PikPakStreamReader.READ_AHEAD_PRIORITY),
            )
        }
    }

    @Test
    fun `high latency foreground can explore warm sibling and select measured improvement`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        val sibling = link.replace("0001", "0002")
        repeat(3) { health.answered(link, 1.seconds) }
        health.measure("dl-z01a-0001.mypikpak.com", 175L * 1024)
        health.onSocketReturned(sibling)
        assertEquals(sibling, health.route(link, true, priority = PikPakStreamReader.READ_AHEAD_PRIORITY))
        health.onRequestStarted(sibling)
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, true, priority = PikPakStreamReader.READ_AHEAD_PRIORITY))
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
        repeat(8) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)

        time.onElapsed = {
            repeat(3) { health.answered(link, 1.seconds) }
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
        repeat(8) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        assertEquals(sibling, health.route(link, false))
        time.onElapsed = { repeat(3) { health.answered(link, 1.seconds) } }
        assertEquals(sibling, health.route(link, false))
        assertEquals(link, health.route(link, false))
    }

    @Test
    fun `clean latency CAS retry preserves observations already published in the new epoch`() {
        val time = InterleavingTimeSource()
        val health = HostHealth(timeSource = time, seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.answered(link, 100.milliseconds) }
        time.onElapsed = {
            repeat(3) { health.answered(link, 1.seconds) }
            health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        }
        health.answered(link, 1.seconds)
        assertTrue(health.hasHealthySibling(link))
        assertEquals(link.replace("0001", "0002"), health.route(link, false))
        repeat(4) { health.answered(link, 1.seconds) }
        assertEquals(2.seconds, health.firstResponseDeadline())
    }

    @Test
    fun `epoch boundary uses the median of three rather than requiring three changed waits`() {
        val health = HostHealth(timeSource = TestTimeSource(), seeds = listOf("dl-z01a-0002"))
        repeat(8) { health.answered(link, 100.milliseconds) }
        health.measure("dl-z01a-0002.mypikpak.com", 2_000_000)
        health.answered(link, 350.milliseconds)
        assertTrue(health.hasHealthySibling(link))
        health.answered(link, 350.milliseconds)
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
    }
}
