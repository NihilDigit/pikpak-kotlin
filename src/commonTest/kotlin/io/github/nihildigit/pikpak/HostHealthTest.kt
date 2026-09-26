package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.HostHealth
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
