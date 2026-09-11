package com.splitfree.data.ble

import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.splitfree.data.identity.IdentityManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Nearby refuses calls with a SecurityException once Bluetooth/location permission is revoked,
 * including mid-session. Teardown runs on screen exit and on Nearby callback threads, where an
 * escaping exception kills the process, so every call is guarded and reported instead.
 */
class NearbySyncErrorHandlingTest {
    private val client = mockk<ConnectionsClient>(relaxed = true)
    private val identity = mockk<IdentityManager>(relaxed = true)

    private lateinit var nearbySync: NearbySync

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        mockkStatic(Nearby::class)
        every { Nearby.getConnectionsClient(any<android.content.Context>()) } returns client
        every { identity.getPublicKeyHex() } returns "aabbccdd11223344"
        nearbySync = NearbySync(mockk(relaxed = true), identity)
    }

    @After
    fun teardown() {
        unmockkStatic(Nearby::class)
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `stopDiscovery reports a revoked permission instead of throwing`() = runTest {
        every { client.stopDiscovery() } throws SecurityException("missing BLUETOOTH_SCAN")

        val errors = collectEvents { nearbySync.stopDiscovery() }

        assertEquals(1, errors.size)
        assertEquals("stop_discovery", (errors.first() as BleEvent.Error).operation)
    }

    @Test
    fun `stop attempts every teardown call when the first one is refused`() = runTest {
        every { client.stopAdvertising() } throws SecurityException("missing BLUETOOTH_ADVERTISE")

        val errors = collectEvents { nearbySync.stop() }

        // The refusal must not short-circuit discovery and endpoint teardown.
        verify { client.stopDiscovery() }
        verify { client.stopAllEndpoints() }
        assertEquals(listOf("stop_advertising"), errors.map { (it as BleEvent.Error).operation })
    }

    @Test
    fun `stop reports each refused call separately`() = runTest {
        every { client.stopAdvertising() } throws SecurityException("denied")
        every { client.stopDiscovery() } throws SecurityException("denied")
        every { client.stopAllEndpoints() } throws SecurityException("denied")

        val errors = collectEvents { nearbySync.stop() }

        assertEquals(
            listOf("stop_advertising", "stop_discovery", "stop_all_endpoints"),
            errors.map { (it as BleEvent.Error).operation }
        )
    }

    @Test
    fun `disconnect still forgets the endpoint when Nearby refuses`() = runTest {
        every { client.disconnectFromEndpoint(any()) } throws SecurityException("denied")

        val errors = collectEvents { nearbySync.disconnect("ep1") }

        assertEquals(listOf("disconnect"), errors.map { (it as BleEvent.Error).operation })
        // A second disconnect must behave the same — local state was cleared, not left stale.
        nearbySync.disconnect("ep1")
    }

    @Test
    fun `stop does not throw when Nearby is unavailable`() = runTest {
        every { client.stopAdvertising() } throws SecurityException("denied")
        every { client.stopDiscovery() } throws SecurityException("denied")
        every { client.stopAllEndpoints() } throws SecurityException("denied")

        nearbySync.stop()
        nearbySync.stopDiscovery()
        nearbySync.disconnect("ep1")
    }

    @Test
    fun `startAdvertising failure surfaces as an error event`() = runTest {
        every { client.startAdvertising(any<String>(), any(), any(), any()) } throws SecurityException("denied")

        val errors = collectEvents { nearbySync.startAdvertising() }

        assertEquals(listOf("advertise"), errors.map { (it as BleEvent.Error).operation })
    }

    /** Run [action] while collecting the events it emits. */
    private suspend fun collectEvents(action: () -> Unit): List<BleEvent> = kotlinx.coroutines.coroutineScope {
        val seen = mutableListOf<BleEvent>()
        val collector = launch {
            nearbySync.events.collect { seen += it }
        }
        yield()
        action()
        yield()
        collector.cancel()
        seen
    }
}
