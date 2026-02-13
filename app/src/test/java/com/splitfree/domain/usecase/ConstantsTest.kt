package com.splitfree.domain.usecase

import com.splitfree.data.nostr.RelayHealthMonitor
import org.junit.Assert.*
import org.junit.Test

class ConstantsTest {

    @Test
    fun `default relays are all wss`() {
        for (relay in CreateGroupUseCase.DEFAULT_RELAYS) {
            assertTrue("Relay must use wss://: $relay", relay.startsWith("wss://"))
        }
    }

    @Test
    fun `default relays has at least 3`() {
        assertTrue(CreateGroupUseCase.DEFAULT_RELAYS.size >= 3)
    }

    @Test
    fun `default relays are unique`() {
        val relays = CreateGroupUseCase.DEFAULT_RELAYS
        assertEquals(relays.size, relays.toSet().size)
    }

    @Test
    fun `max group members is reasonable`() {
        assertTrue(CreateGroupUseCase.MAX_GROUP_MEMBERS in 10..200)
    }

    @Test
    fun `fallback relays are all wss`() {
        for (relay in RelayHealthMonitor.FALLBACK_RELAYS) {
            assertTrue("Fallback relay must use wss://: $relay", relay.startsWith("wss://"))
        }
    }

    @Test
    fun `fallback relays are unique`() {
        val relays = RelayHealthMonitor.FALLBACK_RELAYS
        assertEquals(relays.size, relays.toSet().size)
    }

    @Test
    fun `fallback relays do not overlap with default relays`() {
        val defaults = CreateGroupUseCase.DEFAULT_RELAYS.toSet()
        val fallbacks = RelayHealthMonitor.FALLBACK_RELAYS.toSet()
        val overlap = defaults.intersect(fallbacks)
        assertTrue("Fallback relays should be different from defaults for redundancy", overlap.isEmpty())
    }
}
