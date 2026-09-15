package com.splitfree.data.ble

import com.splitfree.sync.nearby.NearbyConnection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class NearbyModelTest {
    @Test
    fun `connections to the same endpoint have distinct identities`() {
        val first = NearbyConnection("ep1")
        val second = NearbyConnection("ep1")

        assertEquals(first.endpointId, second.endpointId)
        assertNotSame(first, second)
        assertNotEquals(first, second)
        assertEquals(2, setOf(first, second).size)
    }

    @Test
    fun `NearbyPeer fields`() {
        val peer = NearbyPeer("ep1", "Alice")
        assertEquals("ep1", peer.endpointId)
        assertEquals("Alice", peer.name)
    }

    @Test
    fun `BleEvent PeerFound`() {
        val event = BleEvent.PeerFound(NearbyPeer("ep1", "Bob"))
        assertEquals("ep1", event.peer.endpointId)
    }

    @Test
    fun `BleEvent PeerLost`() {
        val event = BleEvent.PeerLost("ep1")
        assertEquals("ep1", event.endpointId)
    }

    @Test
    fun `BleEvent Connected`() {
        val connection = NearbyConnection("ep1")
        val event = BleEvent.Connected(connection)
        assertSame(connection, event.connection)
        assertEquals("ep1", event.endpointId)
    }

    @Test
    fun `BleEvent Disconnected`() {
        val connection = NearbyConnection("ep1")
        val event = BleEvent.Disconnected(connection)
        assertSame(connection, event.connection)
        assertEquals("ep1", event.endpointId)
    }

    @Test
    fun `BleEvent PayloadReceived`() {
        val data = byteArrayOf(1, 2, 3)
        val connection = NearbyConnection("ep1")
        val event = BleEvent.PayloadReceived(connection, data)
        assertSame(connection, event.connection)
        assertEquals("ep1", event.endpointId)
        assertArrayEquals(data, event.data)
    }

    @Test
    fun `BleEvent Error`() {
        val event = BleEvent.Error("discovery", "missing permission")
        assertEquals("discovery", event.operation)
        assertEquals("missing permission", event.reason)
    }
}
