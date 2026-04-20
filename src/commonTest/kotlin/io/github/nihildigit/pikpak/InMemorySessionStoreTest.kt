package io.github.nihildigit.pikpak

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemorySessionStoreTest {

    private fun session(suffix: String) = Session(
        accessToken = "AT-$suffix",
        refreshToken = "RT-$suffix",
        sub = "SUB-$suffix",
        expiresAt = 1_700_000_000L,
    )

    @Test
    fun `load returns null before save`() = runTest {
        val store = InMemorySessionStore()
        assertNull(store.load("a@x"))
    }

    @Test
    fun `save then load returns the stored session`() = runTest {
        val store = InMemorySessionStore()
        val s = session("1")
        store.save("a@x", s)
        assertEquals(s, store.load("a@x"))
    }

    @Test
    fun `save overwrites previous session for the same account`() = runTest {
        val store = InMemorySessionStore()
        store.save("a@x", session("1"))
        store.save("a@x", session("2"))
        assertEquals(session("2"), store.load("a@x"))
    }

    @Test
    fun `clear removes the entry`() = runTest {
        val store = InMemorySessionStore()
        store.save("a@x", session("1"))
        store.clear("a@x")
        assertNull(store.load("a@x"))
    }

    @Test
    fun `clear is a no-op for unknown accounts`() = runTest {
        val store = InMemorySessionStore()
        store.clear("never@seen")
        assertNull(store.load("never@seen"))
    }

    @Test
    fun `entries for different accounts stay separated`() = runTest {
        val store = InMemorySessionStore()
        store.save("a@x", session("a"))
        store.save("b@x", session("b"))
        assertEquals(session("a"), store.load("a@x"))
        assertEquals(session("b"), store.load("b@x"))
        store.clear("a@x")
        assertNull(store.load("a@x"))
        assertEquals(session("b"), store.load("b@x"))
    }
}
