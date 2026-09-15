package com.splitfree.data.ble

import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.splitfree.data.identity.IdentityManager
import com.splitfree.sync.nearby.NearbyConnection
import com.splitfree.sync.nearby.NearbyConnectionAttempt
import com.splitfree.sync.nearby.RadioFailureKind
import com.splitfree.sync.nearby.RadioOutcome
import com.splitfree.sync.nearby.ScriptedConnectionsClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises synchronous SecurityException handling for start, send, disconnect and teardown, including cancellation
 * ownership when SDK cleanup is refused.
 *
 * - Also checks full-buffer drop accounting separately from emissions with no subscribers.
 * - These stubs do not simulate every SDK failure or callback ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
        val connection = connectedLink()
        every { client.disconnectFromEndpoint(any()) } throws SecurityException("denied")

        val events = collectEvents {
            nearbySync.disconnect(connection)
            nearbySync.disconnect(connection)
        }

        assertEquals(
            listOf(
                BleEvent.Disconnected(connection),
                BleEvent.Error("disconnect", "denied", "ep1", RadioFailureKind.PERMISSION)
            ),
            events
        )
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
    }

    @Test
    fun `attempt cancellation stays revoked when the SDK disconnect is refused`() = runTest {
        val attempt = NearbyConnectionAttempt("ep1")
        val callback = slot<ConnectionLifecycleCallback>()
        val task = ScriptedConnectionsClient.FakeTask()
        every { client.requestConnection(any<String>(), "ep1", capture(callback)) } returns task.task
        every { client.disconnectFromEndpoint("ep1") } throws SecurityException("denied")
        val pending = async { nearbySync.requestConnection(attempt) }
        runCurrent()
        task.succeed()
        assertEquals(RadioOutcome.Success, pending.await())

        val events = collectEvents {
            nearbySync.cancelConnectionAttempt(attempt)
            nearbySync.cancelConnectionAttempt(attempt)
            callback.captured.onConnectionInitiated("ep1", ConnectionInfo("aabbccdd", "token", false))
        }

        assertEquals(listOf(BleEvent.Error("disconnect", "denied", "ep1", RadioFailureKind.PERMISSION)), events)
        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(attempt))
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
        verify(exactly = 1) { client.rejectConnection("ep1") }
        verify(exactly = 0) { client.acceptConnection(any(), any()) }
        verify(exactly = 1) { client.requestConnection(any<String>(), "ep1", any()) }
    }

    @Test
    fun `stop does not throw when Nearby is unavailable`() = runTest {
        every { client.stopAdvertising() } throws SecurityException("denied")
        every { client.stopDiscovery() } throws SecurityException("denied")
        every { client.stopAllEndpoints() } throws SecurityException("denied")

        nearbySync.stop()
        nearbySync.stopDiscovery()
        nearbySync.cancelConnectionAttempt(NearbyConnectionAttempt("ep1"))
    }

    @Test
    fun `startAdvertising refused at submission settles as a permission failure`() = runTest {
        every { client.startAdvertising(any<String>(), any(), any(), any()) } throws SecurityException("denied")

        val outcome = nearbySync.startAdvertising()

        assertEquals(RadioOutcome.Failure(RadioFailureKind.PERMISSION, null, "denied"), outcome)
    }

    @Test
    fun `sendPayload on a connected link reports revoked permission without ending the link`() = runTest {
        val connection = connectedLink()
        every { client.sendPayload("ep1", any<Payload>()) } throws SecurityException("missing BLUETOOTH_CONNECT")

        val events = collectEvents {
            nearbySync.sendPayload(connection, byteArrayOf(1))
            nearbySync.sendPayload(connection, byteArrayOf(2))
        }

        val expected = BleEvent.Error("send_payload", "missing BLUETOOTH_CONNECT", "ep1", RadioFailureKind.PERMISSION)
        assertEquals(listOf(expected, expected), events)
        verify(exactly = 2) { client.sendPayload("ep1", any<Payload>()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    // --- Backpressure ---

    @Test
    fun `events beyond the buffer are counted as dropped when the collector is stuck`() = runTest {
        every { client.stopDiscovery() } throws SecurityException("denied")
        // A collector that never finishes handling its first event, so the buffer cannot drain.
        val collector = launch { nearbySync.events.collect { awaitCancellation() } }
        runCurrent()

        val extra = 7
        repeat(NearbySync.EVENT_BUFFER_CAPACITY + extra) { nearbySync.stopDiscovery() }

        // Depending on whether the collector took one event before sticking, either `extra` or
        // `extra - 1` events had nowhere to go; none may vanish uncounted.
        val dropped = nearbySync.droppedEvents.get()
        assertTrue("expected ~$extra drops, got $dropped", dropped == extra.toLong() || dropped == extra - 1L)
        collector.cancel()
    }

    @Test
    fun `nothing is dropped while the buffer has room`() = runTest {
        every { client.stopDiscovery() } throws SecurityException("denied")
        val collector = launch { nearbySync.events.collect { awaitCancellation() } }
        runCurrent()

        repeat(NearbySync.EVENT_BUFFER_CAPACITY) { nearbySync.stopDiscovery() }

        assertEquals(0L, nearbySync.droppedEvents.get())
        collector.cancel()
    }

    @Test
    fun `nothing is counted as dropped without a subscriber`() = runTest {
        every { client.stopDiscovery() } throws SecurityException("denied")

        // With no collectors and no replay, events are discarded but do not increment the full-buffer counter.
        repeat(NearbySync.EVENT_BUFFER_CAPACITY + 5) { nearbySync.stopDiscovery() }

        assertEquals(0L, nearbySync.droppedEvents.get())
    }

    private fun TestScope.connectedLink(): NearbyConnection {
        val callback = slot<ConnectionLifecycleCallback>()
        val requestTask = ScriptedConnectionsClient.FakeTask()
        every { client.requestConnection(any<String>(), "ep1", capture(callback)) } returns requestTask.task
        every { client.acceptConnection("ep1", any()) } returns ScriptedConnectionsClient.FakeTask().task
        val connected = backgroundScope.async { nearbySync.events.filterIsInstance<BleEvent.Connected>().first() }
        val request = async { nearbySync.requestConnection(NearbyConnectionAttempt("ep1")) }
        runCurrent()
        requestTask.succeed()
        runCurrent()
        assertEquals(RadioOutcome.Success, request.getCompleted())

        callback.captured.onConnectionInitiated("ep1", ConnectionInfo("aabbccdd", "token", false))
        callback.captured.onConnectionResult("ep1", ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK)))
        runCurrent()
        return connected.getCompleted().connection
    }

    /** Run [action] while collecting the events it emits. */
    private suspend fun collectEvents(action: suspend () -> Unit): List<BleEvent> = kotlinx.coroutines.coroutineScope {
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
