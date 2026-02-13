package com.splitfree.data.ble

import org.junit.Assert.*
import org.junit.Test

class NearbyModelTest {
    @Test
    fun `NearbyPeer fields`() {
        val peer = NearbyPeer("ep1", "Alice")
        assertEquals("ep1", peer.endpointId)
        assertEquals("Alice", peer.name)
    }

    @Test
    fun `BleEvent PeerFound`() {
        val event = BleEvent.PeerFound(NearbyPeer("ep1", "Bob"))
        assertTrue(event is BleEvent.PeerFound)
        assertEquals("ep1", event.peer.endpointId)
    }

    @Test
    fun `BleEvent PeerLost`() {
        val event = BleEvent.PeerLost("ep1")
        assertEquals("ep1", event.endpointId)
    }

    @Test
    fun `BleEvent Connected`() {
        val event = BleEvent.Connected("ep1")
        assertEquals("ep1", event.endpointId)
    }

    @Test
    fun `BleEvent Disconnected`() {
        val event = BleEvent.Disconnected("ep1")
        assertEquals("ep1", event.endpointId)
    }

    @Test
    fun `BleEvent PayloadReceived`() {
        val data = byteArrayOf(1, 2, 3)
        val event = BleEvent.PayloadReceived("ep1", data)
        assertEquals("ep1", event.endpointId)
        assertArrayEquals(data, event.data)
    }
}
