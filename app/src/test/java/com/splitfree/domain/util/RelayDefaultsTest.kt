package com.splitfree.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDefaultsTest {
    private val allLists = RelayDefaults.DEFAULT_RELAYS + RelayDefaults.FALLBACK_RELAYS + RelayDefaults.KNOWN_RELAYS

    @Test
    fun `every relay URL is wss`() {
        allLists.forEach { assertTrue("Expected wss:// prefix: $it", it.startsWith("wss://")) }
    }

    @Test
    fun `no overlap between default and fallback`() {
        val overlap = RelayDefaults.DEFAULT_RELAYS.intersect(RelayDefaults.FALLBACK_RELAYS.toSet())
        assertTrue("Default and fallback should not overlap: $overlap", overlap.isEmpty())
    }

    @Test
    fun `DEFAULT_RELAYS is a subset of KNOWN_RELAYS`() {
        val unknown = RelayDefaults.DEFAULT_RELAYS - RelayDefaults.KNOWN_RELAYS.toSet()
        assertTrue("Defaults missing from KNOWN_RELAYS: $unknown", unknown.isEmpty())
    }

    @Test
    fun `FALLBACK_RELAYS is a subset of KNOWN_RELAYS`() {
        val unknown = RelayDefaults.FALLBACK_RELAYS - RelayDefaults.KNOWN_RELAYS.toSet()
        assertTrue("Fallbacks missing from KNOWN_RELAYS: $unknown", unknown.isEmpty())
    }

    @Test
    fun `KNOWN_RELAYS fits the 16-bit invite link bitmap`() {
        assertTrue(RelayDefaults.KNOWN_RELAYS.size <= 16)
    }

    @Test
    fun `DEFAULT_RELAYS has 5 relays`() {
        assertEquals(5, RelayDefaults.DEFAULT_RELAYS.size)
    }

    @Test
    fun `FALLBACK_RELAYS has 3 relays`() {
        assertEquals(3, RelayDefaults.FALLBACK_RELAYS.size)
    }

    @Test
    fun `no duplicate relays within lists`() {
        assertEquals(RelayDefaults.DEFAULT_RELAYS.size, RelayDefaults.DEFAULT_RELAYS.distinct().size)
        assertEquals(RelayDefaults.FALLBACK_RELAYS.size, RelayDefaults.FALLBACK_RELAYS.distinct().size)
        assertEquals(RelayDefaults.KNOWN_RELAYS.size, RelayDefaults.KNOWN_RELAYS.distinct().size)
    }

    @Test
    fun `MAX_GROUP_MEMBERS is reasonable`() {
        assertTrue(RelayDefaults.MAX_GROUP_MEMBERS in 10..200)
    }

    @Test
    fun `all relay URLs have no trailing slash`() {
        allLists.forEach { assertFalse("Trailing slash in: $it", it.endsWith("/")) }
    }
}
