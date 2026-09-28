package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.HostHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

class HostHealthTest {
    // A dropped Wi-Fi silences every request at once; marking those hosts would blacklist the pool
    @Test
    fun `a silence with no other host answering is not held against the host`() {
        val health = HostHealth()
        health.markSilent("https://a.example/file")
        assertFalse(health.isBad("https://a.example/other"))
    }

    @Test
    fun `a silence while another host answers marks the host for every link on it`() {
        val health = HostHealth()
        health.answered("https://b.example/file")
        health.markSilent("https://a.example/file?sig=1")
        assertTrue(health.isBad("https://a.example/another?sig=2"))
        assertFalse(health.isBad("https://b.example/file"))
    }

    private val link = "https://dl-z01a-0001.mypikpak.com/download/?fid=F&sign=S"

    private fun HostHealth.measure(host: String, bytesPerSecond: Long) =
        repeat(HostHealth.MIN_SAMPLES) { delivered(host, HostHealth.MIN_SAMPLE_BYTES, bytesPerSecond) }

    @Test
    fun `a host far slower than a measured sibling is left for it and the healthy spread is not`() {
        val health = HostHealth(seeds = listOf("dl-z01a-0002", "dl-z01a-0003"))
        health.measure("dl-z01a-0001.mypikpak.com", 170_000)
        health.measure("dl-z01a-0002.mypikpak.com", 5_000_000)
        health.measure("dl-z01a-0003.mypikpak.com", 2_500_000)

        assertEquals("https://dl-z01a-0002.mypikpak.com/download/?fid=F&sign=S", health.route(link, mayExplore = false))
        val halfAsFast = "https://dl-z01a-0003.mypikpak.com/download/?fid=F&sign=S"
        assertEquals(halfAsFast, health.route(halfAsFast, mayExplore = false))
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
}
