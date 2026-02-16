package com.splitfree.domain.usecase

import com.splitfree.data.nostr.RelayConfig
import org.junit.Assert.*
import org.junit.Test

class ConstantsTest {
    @Test
    fun `default relays are all wss`() {
        for (relay in RelayConfig.DEFAULT_RELAYS) {
            assertTrue("Relay must use wss://: $relay", relay.startsWith("wss://"))
        }
    }

    @Test
    fun `default relays has at least 3`() {
        assertTrue(RelayConfig.DEFAULT_RELAYS.size >= 3)
    }

    @Test
    fun `default relays are unique`() {
        val relays = RelayConfig.DEFAULT_RELAYS
        assertEquals(relays.size, relays.toSet().size)
    }

    @Test
    fun `max group members is reasonable`() {
        assertTrue(RelayConfig.MAX_GROUP_MEMBERS in 10..200)
    }

    @Test
    fun `fallback relays are all wss`() {
        for (relay in RelayConfig.FALLBACK_RELAYS) {
            assertTrue("Fallback relay must use wss://: $relay", relay.startsWith("wss://"))
        }
    }

    @Test
    fun `fallback relays are unique`() {
        val relays = RelayConfig.FALLBACK_RELAYS
        assertEquals(relays.size, relays.toSet().size)
    }

    @Test
    fun `fallback relays do not overlap with default relays`() {
        val defaults = RelayConfig.DEFAULT_RELAYS.toSet()
        val fallbacks = RelayConfig.FALLBACK_RELAYS.toSet()
        val overlap = defaults.intersect(fallbacks)
        assertTrue("Fallback relays should be different from defaults for redundancy", overlap.isEmpty())
    }
}
