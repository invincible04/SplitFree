package com.splitfree.data.nostr.relay

import com.splitfree.data.nostr.RelayConfig
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.data.nostr.relay.RelayStatus
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayHealthMonitorTest {
    private val monitor = RelayHealthMonitor()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `statuses initially empty`() {
        assertTrue(monitor.statuses.isEmpty())
    }

    @Test
    fun `getOnlineRelays returns empty when no status known`() {
        val relays = listOf("wss://a", "wss://b")
        assertEquals(emptyList<String>(), monitor.getOnlineRelays(relays))
    }

    @Test
    fun `getOnlineRelays filters offline relays`() {
        // Inject a known-offline status via checkRelays with invalid URL
        runBlocking { monitor.checkRelays(listOf("wss://localhost:1")) }
        // localhost:1 should fail → offline
        val status = monitor.statuses["wss://localhost:1"]
        assertNotNull(status)
        assertFalse(status!!.online)
        // getOnlineRelays should exclude it
        assertEquals(emptyList<String>(), monitor.getOnlineRelays(listOf("wss://localhost:1")))
    }

    @Test
    fun `checkRelays stores offline status for unreachable relay`() = runBlocking {
        monitor.checkRelays(listOf("wss://localhost:1"))
        val status = monitor.statuses["wss://localhost:1"]
        assertNotNull(status)
        assertEquals("wss://localhost:1", status!!.url)
        assertFalse(status.online)
    }

    @Test
    fun `checkRelays handles multiple relays`() = runBlocking {
        monitor.checkRelays(listOf("wss://localhost:1", "wss://localhost:2"))
        assertEquals(2, monitor.statuses.size)
        assertFalse(monitor.statuses["wss://localhost:1"]!!.online)
        assertFalse(monitor.statuses["wss://localhost:2"]!!.online)
    }

    @Test
    fun `checkRelays with empty list does nothing`() = runBlocking {
        monitor.checkRelays(emptyList())
        assertTrue(monitor.statuses.isEmpty())
    }

    @Test
    fun `FALLBACK_RELAYS are all wss`() {
        assertTrue(RelayConfig.FALLBACK_RELAYS.all { it.startsWith("wss://") })
        assertTrue(RelayConfig.FALLBACK_RELAYS.isNotEmpty())
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
