package com.splitfree.data.nostr

import org.junit.Assert.*
import org.junit.Test

class RelayHealthMonitorTest {

    private val monitor = RelayHealthMonitor()

    @Test
    fun `statuses initially empty`() {
        assertTrue(monitor.statuses.isEmpty())
    }

    @Test
    fun `getOnlineRelays returns all when no status known`() {
        val relays = listOf("wss://a", "wss://b")
        assertEquals(relays, monitor.getOnlineRelays(relays))
    }

    @Test
    fun `FALLBACK_RELAYS are all wss`() {
        assertTrue(RelayHealthMonitor.FALLBACK_RELAYS.all { it.startsWith("wss://") })
        assertTrue(RelayHealthMonitor.FALLBACK_RELAYS.isNotEmpty())
    }

    @Test
    fun `RelayStatus data class fields`() {
        val status = RelayStatus("wss://test", online = true, latencyMs = 42, checkedAt = 1000)
        assertEquals("wss://test", status.url)
        assertTrue(status.online)
        assertEquals(42L, status.latencyMs)
        assertEquals(1000L, status.checkedAt)
    }

    @Test
    fun `RelayStatus defaults`() {
        val status = RelayStatus("wss://test", online = false)
        assertEquals(0L, status.latencyMs)
    }
}
