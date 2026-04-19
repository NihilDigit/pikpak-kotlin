package io.github.nihildigit.pikpak

import kotlinx.io.Buffer
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the gcid algorithm against the Go reference implementation
 * (52funny/pikpakhash). If a future refactor breaks any of these vectors
 * the upload init endpoint will silently start mismatching the server-side
 * dedup index — fail fast here instead.
 */
class PikPakHashTest {

    @Test
    fun `empty input matches reference`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hashOf(ByteArray(0)))
    }

    @Test
    fun `hello matches reference`() {
        assertEquals("6b4f89a54e2d27ecd7e8da05b4ab8fd9d1d8b119", hashOf("hello".encodeToByteArray()))
    }

    @Test
    fun `exactly one chunk size matches reference`() {
        val bytes = ByteArray(256 * 1024) { (it % 256).toByte() }
        assertEquals("1592a0a4c2552dce960c4302c5fcbeab74ad90b7", hashOf(bytes))
    }

    @Test
    fun `one byte over chunk size triggers two chunks`() {
        val bytes = ByteArray(256 * 1024 + 1) { if (it < 256 * 1024) (it % 256).toByte() else 0xFF.toByte() }
        assertEquals("8d8e0641a07d3b8680976cfe8b4d0aca47d0f844", hashOf(bytes))
    }

    private fun hashOf(bytes: ByteArray): String {
        val buf = Buffer().apply { write(bytes, 0, bytes.size) }
        return PikPakHash.fromSource(buf, bytes.size.toLong())
    }
}
