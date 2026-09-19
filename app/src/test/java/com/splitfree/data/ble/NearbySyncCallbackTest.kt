package com.splitfree.data.ble

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import com.splitfree.data.identity.IdentityManager
import com.splitfree.sync.nearby.NearbyConnection
import com.splitfree.sync.nearby.NearbyConnectionAttempt
import com.splitfree.sync.nearby.RadioFailureKind
import com.splitfree.sync.nearby.RadioOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises adapter ownership, task outcomes, cancellation, payload errors and overflow with captured SDK callbacks.
 *
 * - Each submission retains its callback; tests choose callback delivery and task completion order.
 * - These scripted schedules do not establish real SDK ordering, especially between incoming incarnations sharing one
 *   advertising callback.
 * - Request identities, link capabilities and SDK channel tokens are distinct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySyncCallbackTest {
    private val client = mockk<ConnectionsClient>(relaxed = true)
    private val identity = mockk<IdentityManager>(relaxed = true)

    private val lifecycle = slot<ConnectionLifecycleCallback>()
    private val discovery = slot<EndpointDiscoveryCallback>()
    private val payloads = slot<PayloadCallback>()

    /**
     * Every callback object the adapter has handed to the platform, in submission order.
     *
     * - The slots above hold the most recent one; ownership scenarios drive an earlier callback while a later one owns
     *   the link.
     */
    private val lifecycles = mutableListOf<ConnectionLifecycleCallback>()
    private val discoveries = mutableListOf<EndpointDiscoveryCallback>()
    private val payloadCallbacks = mutableListOf<PayloadCallback>()

    /** One task per acceptConnection call made through [stubAccept], in call order, paired with [payloadCallbacks]. */
    private val acceptTasks = mutableListOf<FakeTask>()

    private lateinit var nearbySync: NearbySync

    /** A platform task the test settles by hand, exactly once or as often as the scenario needs. */
    private class FakeTask {
        val task = mockk<Task<Void>>()
        private val onComplete = slot<OnCompleteListener<Void>>()
        private val onFailure = slot<OnFailureListener>()
        val completionRegistered = CountDownLatch(1)

        init {
            every { task.addOnCompleteListener(capture(onComplete)) } answers {
                completionRegistered.countDown()
                task
            }
            every { task.addOnFailureListener(capture(onFailure)) } returns task
        }

        val completionObserved: Boolean get() = onComplete.isCaptured

        fun succeed() {
            stub(successful = true, canceled = false, exception = null)
            onComplete.captured.onComplete(task)
        }

        fun cancel() {
            stub(successful = false, canceled = true, exception = null)
            onComplete.captured.onComplete(task)
        }

        fun fail(statusCode: Int) {
            val exception = ApiException(Status(statusCode))
            stub(successful = false, canceled = false, exception = exception)
            if (onComplete.isCaptured) onComplete.captured.onComplete(task)
            if (onFailure.isCaptured) onFailure.captured.onFailure(exception)
        }

        /** Re-invokes the completion listener with the already stubbed result. */
        fun completeAgain() {
            onComplete.captured.onComplete(task)
        }

        private fun stub(successful: Boolean, canceled: Boolean, exception: Exception?) {
            every { task.isSuccessful } returns successful
            every { task.isCanceled } returns canceled
            every { task.exception } returns exception
        }
    }

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

    // --- Start operation outcomes ---

    @Test
    fun `start outcome follows the platform status code`() = runTest {
        val expected =
            mapOf(
                ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING to RadioFailureKind.ALREADY_ACTIVE,
                ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN to RadioFailureKind.PERMISSION,
                ConnectionsStatusCodes.STATUS_RADIO_ERROR to RadioFailureKind.RADIO,
                ConnectionsStatusCodes.STATUS_ERROR to RadioFailureKind.RADIO,
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED to RadioFailureKind.ENDPOINT,
                ConnectionsStatusCodes.STATUS_PAYLOAD_IO_ERROR to RadioFailureKind.PAYLOAD,
                ConnectionsStatusCodes.API_CONNECTION_FAILED_ALREADY_IN_USE to RadioFailureKind.SERVICE
            )
        assertEquals(8001, ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING)
        assertEquals(8037, ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN)
        assertEquals(8007, ConnectionsStatusCodes.STATUS_RADIO_ERROR)
        assertEquals(13, ConnectionsStatusCodes.STATUS_ERROR)
        assertEquals(8004, ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED)
        assertEquals(8013, ConnectionsStatusCodes.STATUS_PAYLOAD_IO_ERROR)
        assertEquals(8050, ConnectionsStatusCodes.API_CONNECTION_FAILED_ALREADY_IN_USE)

        for ((code, kind) in expected) {
            val task = stubAdvertising()
            val pending = async { nearbySync.startAdvertising() }
            runCurrent()
            task.fail(code)

            val outcome = pending.await() as RadioOutcome.Failure
            assertEquals("code $code", kind, outcome.kind)
            assertEquals("code $code", code, outcome.statusCode)
        }
    }

    @Test
    fun `legacy platform codes retain their original failure classifications`() = runTest {
        // Historical SDK values, injected as raw codes so compatibility is tested independently of aliases.
        val expected = mapOf(8025 to RadioFailureKind.LOCATION_SETTING, 8000 to RadioFailureKind.SERVICE)
        for ((code, kind) in expected) {
            val task = stubAdvertising()
            val pending = async { nearbySync.startAdvertising() }
            runCurrent()
            task.fail(code)

            val outcome = pending.await() as RadioOutcome.Failure
            assertEquals("legacy code $code", kind, outcome.kind)
            assertEquals("legacy code $code", code, outcome.statusCode)
        }
    }

    @Test
    fun `successful start task settles as Success`() = runTest {
        val task = stubAdvertising()
        val pending = async { nearbySync.startAdvertising() }
        runCurrent()

        task.succeed()

        assertEquals(RadioOutcome.Success, pending.await())
    }

    @Test
    fun `cancelled start task settles as Cancelled`() = runTest {
        val task = stubDiscovery()
        val pending = async { nearbySync.startDiscovery() }
        runCurrent()

        task.cancel()

        assertEquals(RadioOutcome.Cancelled, pending.await())
    }

    @Test
    fun `SecurityException at submission settles as a permission failure without a task`() = runTest {
        every { client.requestConnection(any<String>(), any(), any()) } throws SecurityException("denied")

        val outcome = nearbySync.requestConnection(NearbyConnectionAttempt("ep1"))

        assertEquals(RadioOutcome.Failure(RadioFailureKind.PERMISSION, null, "denied"), outcome)
        verify(exactly = 1) { client.requestConnection(any<String>(), "ep1", any()) }
    }

    @Test
    fun `completion listener invoked twice resumes the caller once`() = runTest {
        val task = stubAdvertising()
        val pending = async { nearbySync.startAdvertising() }
        runCurrent()

        task.succeed()
        task.completeAgain()

        assertEquals(RadioOutcome.Success, pending.await())
    }

    // --- Run boundary fence ---

    @Test
    fun `stale initiation after stopAllEndpoints is rejected without an event`() = runTest {
        val events = collectEvents()
        advertise()
        nearbySync.stopAllEndpoints()

        lifecycle.captured.onConnectionInitiated("ep1", incomingInfo())
        runCurrent()

        verify(exactly = 1) { client.rejectConnection("ep1") }
        verify(exactly = 0) { client.acceptConnection(any(), any()) }
        assertEquals(emptyList<BleEvent>(), events)
    }

    @Test
    fun `stale success result after stopAllEndpoints closes the orphan without Connected`() = runTest {
        val events = collectEvents()
        advertise()
        lifecycle.captured.onConnectionInitiated("ep1", incomingInfo())
        nearbySync.stopAllEndpoints()

        lifecycle.captured.onConnectionResult("ep1", success())
        runCurrent()

        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
        assertEquals(emptyList<BleEvent>(), events)
    }

    @Test
    fun `the run boundary ends every connected link once and stale results and disconnections emit nothing`() =
        runTest {
            val events = collectEvents()
            advertise()
            connect("ep1")
            nearbySync.stopAllEndpoints()

            lifecycle.captured.onConnectionResult("ep2", failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
            lifecycle.captured.onDisconnected("ep1")
            runCurrent()

            // The boundary itself announced the end of ep1's link; the platform's later callbacks add nothing.
            assertEquals(listOf("Connected", "Disconnected"), events.map { it::class.simpleName })
            assertEquals("ep1", (events.last() as BleEvent.Disconnected).endpointId)
        }

    @Test
    fun `a local disconnect announces the link end once and later platform callbacks for it emit nothing`() = runTest {
        val events = collectEvents()
        advertise()
        connect("ep1")
        nearbySync.disconnect(events.connection("ep1"))

        payloads.captured.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(1, 2, 3)))
        lifecycle.captured.onDisconnected("ep1")
        runCurrent()

        assertEquals(listOf("Connected", "Disconnected"), events.map { it::class.simpleName })
        assertEquals("ep1", (events.last() as BleEvent.Disconnected).endpointId)
    }

    @Test
    fun `payload for a connected endpoint is emitted`() = runTest {
        val events = collectEvents()
        advertise()
        connect("ep1")

        payloads.captured.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(1, 2, 3)))
        runCurrent()

        val received = events.last() as BleEvent.PayloadReceived
        assertEquals("ep1", received.endpointId)
        assertSame(events.connection("ep1"), received.connection)
        assertArrayEquals(byteArrayOf(1, 2, 3), received.data)
    }

    @Test
    fun `endpoint found is emitted while discovering and dropped after stopDiscovery`() = runTest {
        val events = collectEvents()
        discover()

        discovery.captured.onEndpointFound("ep1", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "aabbccdd"))
        nearbySync.stopDiscovery()
        discovery.captured.onEndpointFound("ep2", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "11223344"))
        discovery.captured.onEndpointLost("ep1")
        runCurrent()

        assertEquals(listOf<BleEvent>(BleEvent.PeerFound(NearbyPeer("ep1", "aabbccdd"))), events)
    }

    @Test
    fun `discovery start failure turns discovery off so found endpoints are dropped`() = runTest {
        val events = collectEvents()
        val task = stubDiscovery()
        val pending = async { nearbySync.startDiscovery() }
        runCurrent()
        task.fail(ConnectionsStatusCodes.STATUS_RADIO_ERROR)
        assertEquals(RadioFailureKind.RADIO, (pending.await() as RadioOutcome.Failure).kind)

        discovery.captured.onEndpointFound("ep1", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "aabbccdd"))
        runCurrent()

        assertEquals(emptyList<BleEvent>(), events)
    }

    @Test
    fun `already discovering keeps discovery on`() = runTest {
        val events = collectEvents()
        val task = stubDiscovery()
        val pending = async { nearbySync.startDiscovery() }
        runCurrent()
        task.fail(ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING)
        assertEquals(RadioFailureKind.ALREADY_ACTIVE, (pending.await() as RadioOutcome.Failure).kind)

        discovery.captured.onEndpointFound("ep1", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "aabbccdd"))
        runCurrent()

        assertEquals(listOf<BleEvent>(BleEvent.PeerFound(NearbyPeer("ep1", "aabbccdd"))), events)
    }

    @Test
    fun `endpoint found before the discovery task settles is emitted`() = runTest {
        val events = collectEvents()
        stubDiscovery()
        val pending = async { nearbySync.startDiscovery() }
        runCurrent()

        discovery.captured.onEndpointFound("ep1", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "aabbccdd"))
        runCurrent()

        assertEquals(listOf<BleEvent>(BleEvent.PeerFound(NearbyPeer("ep1", "aabbccdd"))), events)
        pending.cancel()
    }

    // --- Connection results ---

    @Test
    fun `duplicate success result emits exactly one Connected`() = runTest {
        val events = collectEvents()
        advertise()
        connect("ep1")

        lifecycle.captured.onConnectionResult("ep1", success())
        runCurrent()

        assertEquals(1, events.count { it is BleEvent.Connected })
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    @Test
    fun `Connected carries role token and name from initiation`() = runTest {
        val events = collectEvents()
        advertise()

        lifecycle.captured.onConnectionInitiated("ep1", ConnectionInfo("0123abcd", "token-1", false))
        lifecycle.captured.onConnectionResult("ep1", success())
        runCurrent()

        val connected = events.single() as BleEvent.Connected
        assertEquals("ep1", connected.endpointId)
        assertFalse(connected.isIncoming)
        assertArrayEquals("token-1".toByteArray(), connected.authToken)
        assertEquals("0123abcd", connected.endpointName)
    }

    @Test
    fun `rejected result emits ConnectionFailed with the endpoint kind and status code`() = runTest {
        val events = collectEvents()
        advertise()
        lifecycle.captured.onConnectionInitiated("ep1", incomingInfo())

        lifecycle.captured.onConnectionResult("ep1", failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
        runCurrent()

        val failed = events.single() as BleEvent.ConnectionFailed
        assertEquals("ep1", failed.endpointId)
        assertEquals(RadioFailureKind.ENDPOINT, failed.kind)
        assertEquals(8004, failed.statusCode)
    }

    @Test
    fun `accept failure emits ConnectionFailed and forgets the pending endpoint`() = runTest {
        val events = collectEvents()
        advertise()
        val accept = FakeTask()
        every { client.acceptConnection("ep1", capture(payloads)) } returns accept.task
        lifecycle.captured.onConnectionInitiated("ep1", incomingInfo())

        accept.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        lifecycle.captured.onConnectionResult("ep1", success())
        runCurrent()

        val failed = events.single() as BleEvent.ConnectionFailed
        assertEquals("ep1", failed.endpointId)
        assertEquals(RadioFailureKind.ENDPOINT, failed.kind)
        assertEquals(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN, failed.statusCode)
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
    }

    @Test
    fun `initiation refused by a SecurityException emits ConnectionFailed with the permission kind`() = runTest {
        val events = collectEvents()
        advertise()
        every { client.acceptConnection(any(), any()) } throws SecurityException("missing BLUETOOTH_CONNECT")

        lifecycle.captured.onConnectionInitiated("ep1", incomingInfo())
        lifecycle.captured.onConnectionResult("ep1", success())
        runCurrent()

        assertEquals(
            listOf<BleEvent>(
                BleEvent.ConnectionFailed("ep1", RadioFailureKind.PERMISSION, null, "missing BLUETOOTH_CONNECT")
            ),
            events
        )
    }

    @Test
    fun `invalid endpoint name is rejected and never accepted`() = runTest {
        val events = collectEvents()
        advertise()

        lifecycle.captured.onConnectionInitiated("ep1", ConnectionInfo("Bob's phone", "token", true))
        lifecycle.captured.onConnectionResult("ep1", failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
        runCurrent()

        verify(exactly = 1) { client.rejectConnection("ep1") }
        verify(exactly = 0) { client.acceptConnection(any(), any()) }
        val failed = events.single() as BleEvent.ConnectionFailed
        assertEquals("ep1", failed.endpointId)
        assertEquals(RadioFailureKind.ENDPOINT, failed.kind)
        assertNull(failed.statusCode)
    }

    @Test
    fun `reject failure emits Error with the endpoint id`() = runTest {
        val events = collectEvents()
        advertise()
        val reject = FakeTask()
        every { client.rejectConnection("ep1") } returns reject.task

        lifecycle.captured.onConnectionInitiated("ep1", ConnectionInfo("Bob's phone", "token", true))
        reject.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        runCurrent()

        val error = events.filterIsInstance<BleEvent.Error>().single()
        assertEquals("reject_connection", error.operation)
        assertEquals("ep1", error.endpointId)
        assertEquals(RadioFailureKind.ENDPOINT, error.kind)
    }

    @Test
    fun `disconnection of a connected endpoint emits Disconnected once`() = runTest {
        val events = collectEvents()
        advertise()
        connect("ep1")

        lifecycle.captured.onDisconnected("ep1")
        lifecycle.captured.onDisconnected("ep1")
        payloads.captured.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(9)))
        runCurrent()

        assertEquals(listOf(BleEvent.Disconnected(events.connection("ep1"))), events.drop(1))
    }

    @Test
    fun `an unknown connection cannot send a payload`() = runTest {
        val events = collectEvents()
        advertise()

        nearbySync.sendPayload(NearbyConnection("unknown"), byteArrayOf(1))
        runCurrent()

        verify(exactly = 0) { client.sendPayload(any<String>(), any<Payload>()) }
        assertEquals(emptyList<BleEvent>(), events)
    }

    @Test
    fun `attempt cancellation cannot end a link whose Connected event is still queued`() = runTest {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val callback = request(attempt)
        stubAccept()
        callback.onConnectionInitiated("ep1", outgoingInfo())
        callback.onConnectionResult("ep1", success())
        assertEquals(emptyList<BleEvent>(), events)

        nearbySync.cancelConnectionAttempt(attempt)
        runCurrent()

        assertConnected(events.single(), "ep1", incoming = false)
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        val connection = events.connection("ep1")
        stubSend("ep1")
        nearbySync.sendPayload(connection, byteArrayOf(1))
        verify(exactly = 1) { client.sendPayload("ep1", any<Payload>()) }
        nearbySync.disconnect(connection)
        runCurrent()
        assertEquals(BleEvent.Disconnected(connection), events.last())
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
    }

    @Test
    fun `cancellation before request invocation skips the SDK and a fresh token can connect`() = runTest {
        val events = collectEvents()
        val cancelled = NearbyConnectionAttempt("ep1")
        nearbySync.cancelConnectionAttempt(cancelled)
        nearbySync.cancelConnectionAttempt(cancelled)

        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(cancelled))
        verify(exactly = 0) { client.requestConnection(any<String>(), any(), any()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        assertEquals(emptyList<BleEvent>(), events)

        val fresh = NearbyConnectionAttempt("ep1")
        val callback = request(fresh)
        nearbySync.cancelConnectionAttempt(cancelled)
        connect("ep1", via = callback, info = outgoingInfo())

        assertConnected(events.single(), "ep1", incoming = false)
        verify(exactly = 1) { client.requestConnection(any<String>(), "ep1", any()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    @Test
    fun `a duplicate request invocation cannot cancel the original submitted attempt`() = runTest {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val task = stubRequest("ep1")
        val pending = async { nearbySync.requestConnection(attempt) }
        runCurrent()
        val callback = lifecycles.last()

        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(attempt))
        assertFalse(pending.isCompleted)
        verify(exactly = 1) { client.requestConnection(any<String>(), "ep1", any()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }

        task.succeed()
        assertEquals(RadioOutcome.Success, pending.await())
        connect("ep1", via = callback, info = outgoingInfo())
        assertConnected(events.single(), "ep1", incoming = false)
    }

    @Test
    fun `an unchecked submission exception revokes the captured request callback`() = runTest {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val error = IllegalStateException("client unavailable")
        every { client.requestConnection(any<String>(), "ep1", capture(lifecycle)) } throws error

        val failure = runCatching { nearbySync.requestConnection(attempt) }.exceptionOrNull()
        assertSame(error, failure)
        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(attempt))
        lifecycle.captured.onConnectionInitiated("ep1", outgoingInfo())
        runCurrent()

        assertEquals(emptyList<BleEvent>(), events)
        verify(exactly = 1) { client.requestConnection(any<String>(), "ep1", any()) }
        verify(exactly = 1) { client.rejectConnection("ep1") }
        verify(exactly = 0) { client.acceptConnection(any(), any()) }
    }

    @Test
    fun `cancelled old pending link cannot emit an accept failure against a newer request`() = runTest {
        val events = collectEvents()
        val old = NearbyConnectionAttempt("ep1")
        val oldCallback = request(old)
        stubAccept()
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        val accept = acceptTasks.last()
        val fresh = request("ep1")

        nearbySync.cancelConnectionAttempt(old)
        accept.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        oldCallback.onConnectionResult("ep1", success())
        runCurrent()

        assertEquals(emptyList<BleEvent>(), events)
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        connect("ep1", via = fresh, info = outgoingInfo())
        assertConnected(events.single(), "ep1", incoming = false)
    }

    @Test
    fun `failed request outcome still settles when SDK cleanup throws`() = runTest {
        requestCleanupFailureStillSettles(cancelled = false)
    }

    @Test
    fun `cancelled request outcome still settles when SDK cleanup throws`() = runTest {
        requestCleanupFailureStillSettles(cancelled = true)
    }

    private suspend fun TestScope.requestCleanupFailureStillSettles(cancelled: Boolean) {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val task = stubRequest("ep1")
        val pending = async { nearbySync.requestConnection(attempt) }
        runCurrent()
        val oldCallback = lifecycles.last()
        stubAccept()
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        val oldAccept = acceptTasks.last()
        every { client.disconnectFromEndpoint("ep1") } throws IllegalStateException("client unavailable")

        val callbackFailure = runCatching {
            if (cancelled) task.cancel() else task.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        }.exceptionOrNull()
        runCurrent()
        assertNull("Cleanup must not throw out of the SDK completion callback", callbackFailure)
        assertTrue("The already-settled SDK Task must settle its waiter", pending.isCompleted)
        if (cancelled) {
            assertEquals(RadioOutcome.Cancelled, pending.await())
        } else {
            assertEquals(RadioFailureKind.ENDPOINT, (pending.await() as RadioOutcome.Failure).kind)
        }
        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(attempt))
        oldAccept.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        runCurrent()
        assertTrue(events.isEmpty())
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }

        every { client.disconnectFromEndpoint("ep1") } returns Unit
        val fresh = request("ep1")
        connect("ep1", via = fresh, info = outgoingInfo())
        assertConnected(events.single(), "ep1", incoming = false)
    }

    @Test
    fun `a failed request outcome revokes its pending link and rejects later initiation`() = runTest {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val task = stubRequest("ep1")
        val pending = async { nearbySync.requestConnection(attempt) }
        runCurrent()
        val callback = lifecycles.last()
        stubAccept()
        callback.onConnectionInitiated("ep1", outgoingInfo())

        task.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        assertEquals(RadioFailureKind.ENDPOINT, (pending.await() as RadioOutcome.Failure).kind)
        callback.onConnectionResult("ep1", success())
        callback.onConnectionInitiated("ep1", outgoingInfo())
        runCurrent()

        assertEquals(emptyList<BleEvent>(), events)
        verify(exactly = 1) { client.acceptConnection("ep1", any()) }
        verify(exactly = 1) { client.rejectConnection("ep1") }
        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(attempt))
    }

    @Test
    fun `cancellation and SDK request submission are atomic and submitted outcome remains a barrier`() = runTest {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val cancellationStarted = CountDownLatch(1)
        val task = FakeTask()
        every { client.requestConnection(any<String>(), "ep1", capture(lifecycle)) } answers {
            requestEntered.countDown()
            assertTrue("SDK request was not released", releaseRequest.await(5, TimeUnit.SECONDS))
            task.task
        }
        val requesting = FutureTask {
            runBlocking { withTimeout(10_000) { nearbySync.requestConnection(attempt) } }
        }
        val cancelling = FutureTask {
            cancellationStarted.countDown()
            nearbySync.cancelConnectionAttempt(attempt)
        }
        val requester = Thread(requesting, "nearby-attempt-request").apply { isDaemon = true }
        val canceller = Thread(cancelling, "nearby-attempt-cancel").apply { isDaemon = true }

        try {
            requester.start()
            assertTrue("request did not reach the SDK", requestEntered.await(5, TimeUnit.SECONDS))
            canceller.start()
            assertTrue("cancellation did not start", cancellationStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { cancelling.get(200, TimeUnit.MILLISECONDS) }
            verify(exactly = 0) { client.disconnectFromEndpoint(any()) }

            releaseRequest.countDown()
            cancelling.get(5, TimeUnit.SECONDS)
            assertTrue("request task was not observed", task.completionRegistered.await(5, TimeUnit.SECONDS))
            assertFalse("cancellation must not substitute for the SDK outcome", requesting.isDone)
            verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }

            lifecycle.captured.onConnectionInitiated("ep1", outgoingInfo())
            runCurrent()
            verify(exactly = 1) { client.rejectConnection("ep1") }
            verify(exactly = 0) { client.acceptConnection(any(), any()) }
            assertEquals(emptyList<BleEvent>(), events)

            task.succeed()
            assertEquals(RadioOutcome.Success, requesting.get(5, TimeUnit.SECONDS))
        } finally {
            releaseRequest.countDown()
            requesting.cancel(true)
            cancelling.cancel(true)
            requester.join(5_000)
            canceller.join(5_000)
            assertFalse("requester thread leaked", requester.isAlive)
            assertFalse("canceller thread leaked", canceller.isAlive)
        }
    }

    @Test
    fun `old attempt cancellation and callbacks cannot mutate a newer request before initiation`() = runTest {
        val events = collectEvents()
        val old = NearbyConnectionAttempt("ep1")
        val oldCallback = request(old)
        val fresh = NearbyConnectionAttempt("ep1")
        val task = stubRequest("ep1")
        val pending = async { nearbySync.requestConnection(fresh) }
        runCurrent()
        val freshCallback = lifecycles.last()

        nearbySync.cancelConnectionAttempt(old)
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        oldCallback.onConnectionResult("ep1", success())
        oldCallback.onDisconnected("ep1")
        runCurrent()

        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        verify(exactly = 0) { client.rejectConnection(any()) }
        verify(exactly = 0) { client.acceptConnection(any(), any()) }
        assertFalse(pending.isCompleted)
        assertEquals(emptyList<BleEvent>(), events)

        task.succeed()
        assertEquals(RadioOutcome.Success, pending.await())
        connect("ep1", via = freshCallback, info = outgoingInfo())
        assertConnected(events.single(), "ep1", incoming = false)
        nearbySync.cancelConnectionAttempt(old)
        stubSend("ep1")
        nearbySync.sendPayload(events.connection("ep1"), byteArrayOf(1))
        verify(exactly = 1) { client.sendPayload("ep1", any<Payload>()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
    }

    @Test
    fun `cancelled request callbacks cannot reject a replacement pending or connected link`() = runTest {
        val events = collectEvents()
        val old = NearbyConnectionAttempt("ep1")
        val oldCallback = request(old)
        nearbySync.cancelConnectionAttempt(old)
        val fresh = NearbyConnectionAttempt("ep1")
        val freshCallback = request(fresh)
        stubAccept()
        freshCallback.onConnectionInitiated("ep1", outgoingInfo())

        nearbySync.cancelConnectionAttempt(old)
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        oldCallback.onConnectionResult("ep1", success())
        oldCallback.onDisconnected("ep1")
        runCurrent()
        assertEquals(emptyList<BleEvent>(), events)
        verify(exactly = 1) { client.acceptConnection("ep1", any()) }
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
        verify(exactly = 0) { client.rejectConnection(any()) }

        freshCallback.onConnectionResult("ep1", success())
        nearbySync.cancelConnectionAttempt(old)
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        oldCallback.onConnectionResult("ep1", failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
        oldCallback.onDisconnected("ep1")
        runCurrent()

        assertConnected(events.single(), "ep1", incoming = false)
        payloadCallbacks.last().onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(2)))
        runCurrent()
        assertSame(events.connection("ep1"), (events.last() as BleEvent.PayloadReceived).connection)
        verify(exactly = 1) { client.acceptConnection("ep1", any()) }
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
        verify(exactly = 0) { client.rejectConnection(any()) }
    }

    @Test
    fun `outgoing attempt cancellation cannot end a replacement incoming pending link`() = runTest {
        val events = collectEvents()
        val incoming = advertise()
        val attempt = NearbyConnectionAttempt("ep1")
        val outgoing = request(attempt)
        stubAccept()
        outgoing.onConnectionInitiated("ep1", outgoingInfo())
        incoming.onConnectionInitiated("ep1", incomingInfo())

        nearbySync.cancelConnectionAttempt(attempt)
        outgoing.onConnectionInitiated("ep1", outgoingInfo())
        incoming.onConnectionResult("ep1", success())
        runCurrent()

        assertConnected(events.single(), "ep1", incoming = true)
        verify(exactly = 2) { client.acceptConnection("ep1", any()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        verify(exactly = 0) { client.rejectConnection(any()) }
    }

    @Test
    fun `run boundary forgets submitted requests and old callbacks cannot affect a fresh run`() = runTest {
        val events = collectEvents()
        val old = NearbyConnectionAttempt("ep1")
        val oldCallback = request(old)
        nearbySync.stopAllEndpoints()
        nearbySync.cancelConnectionAttempt(old)
        assertEquals(RadioOutcome.Cancelled, nearbySync.requestConnection(old))
        val fresh = request("ep1")

        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        oldCallback.onConnectionResult("ep1", success())
        connect("ep1", via = fresh, info = outgoingInfo())
        oldCallback.onConnectionInitiated("ep1", outgoingInfo())
        oldCallback.onDisconnected("ep1")
        runCurrent()

        assertConnected(events.single(), "ep1", incoming = false)
        verify(exactly = 2) { client.requestConnection(any<String>(), "ep1", any()) }
        verify(exactly = 1) { client.stopAllEndpoints() }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        verify(exactly = 0) { client.rejectConnection(any()) }
    }

    @Test
    fun `an old capability cannot send or disconnect a replacement link with the same endpoint`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        connect("ep1", via = callback)
        val oldConnection = events.connection("ep1")
        val oldSend = stubSend("ep1")
        nearbySync.sendPayload(oldConnection, byteArrayOf(1))

        connect("ep1", via = callback)
        val newConnection = events.connection("ep1")
        assertNotSame(oldConnection, newConnection)
        assertEquals(oldConnection.endpointId, newConnection.endpointId)
        assertEquals(BleEvent.Disconnected(oldConnection), events[1])
        val beforeStaleCalls = events.toList()

        nearbySync.sendPayload(oldConnection, byteArrayOf(9))
        nearbySync.disconnect(oldConnection)
        oldSend.fail(ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)
        runCurrent()

        verify(exactly = 1) { client.sendPayload("ep1", any<Payload>()) }
        verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        assertEquals(beforeStaleCalls, events)

        val sent = slot<Payload>()
        val newSend = FakeTask()
        every { client.sendPayload("ep1", capture(sent)) } returns newSend.task
        nearbySync.sendPayload(newConnection, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), sent.captured.asBytes())
        verify(exactly = 2) { client.sendPayload("ep1", any<Payload>()) }
        payloadCallbacks.last().onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(3)))
        runCurrent()
        val received = events.last() as BleEvent.PayloadReceived
        assertSame(newConnection, received.connection)
        assertArrayEquals(byteArrayOf(3), received.data)

        nearbySync.disconnect(newConnection)
        runCurrent()
        assertEquals(BleEvent.Disconnected(newConnection), events.last())
        assertEquals(beforeStaleCalls.size + 2, events.size)
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
    }

    @Test
    fun `capability validation and SDK send exclude concurrent endpoint replacement`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        connect("ep1", via = callback)
        val oldConnection = events.connection("ep1")
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val sendTask = FakeTask()
        every { client.sendPayload("ep1", any<Payload>()) } answers {
            sendEntered.countDown()
            assertTrue("SDK send was not released", releaseSend.await(5, TimeUnit.SECONDS))
            sendTask.task
        }
        val sending = FutureTask {
            nearbySync.sendPayload(oldConnection, byteArrayOf(1))
        }
        val replacing = FutureTask {
            replacementStarted.countDown()
            callback.onConnectionInitiated("ep1", incomingInfo())
            callback.onConnectionResult("ep1", success())
        }
        val sender = Thread(sending, "nearby-capability-send").apply { isDaemon = true }
        val replacer = Thread(replacing, "nearby-capability-replace").apply { isDaemon = true }

        try {
            sender.start()
            assertTrue("send did not reach the SDK", sendEntered.await(5, TimeUnit.SECONDS))
            replacer.start()
            assertTrue("replacement did not start", replacementStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { replacing.get(200, TimeUnit.MILLISECONDS) }

            releaseSend.countDown()
            sending.get(5, TimeUnit.SECONDS)
            replacing.get(5, TimeUnit.SECONDS)
            runCurrent()

            val newConnection = events.connection("ep1")
            assertNotSame(oldConnection, newConnection)
            assertEquals(listOf("Connected", "Disconnected", "Connected"), events.map { it::class.simpleName })
            assertEquals(BleEvent.Disconnected(oldConnection), events[1])
            verify(exactly = 1) { client.sendPayload("ep1", any<Payload>()) }
            verify(exactly = 0) { client.disconnectFromEndpoint(any()) }
        } finally {
            releaseSend.countDown()
            sender.join(5_000)
            replacer.join(5_000)
            assertFalse("sender thread leaked", sender.isAlive)
            assertFalse("replacement thread leaked", replacer.isAlive)
        }
    }

    // --- Payload errors ---

    @Test
    fun `sendPayload failure emits Error with endpoint id and kind`() = runTest {
        val events = collectEvents()
        advertise()
        connect("ep1")
        val send = stubSend("ep1")

        nearbySync.sendPayload(events.connection("ep1"), byteArrayOf(1))
        send.fail(ConnectionsStatusCodes.STATUS_PAYLOAD_IO_ERROR)
        runCurrent()

        val error = events.drop(1).single() as BleEvent.Error
        assertEquals("send_payload", error.operation)
        assertEquals("ep1", error.endpointId)
        assertEquals(RadioFailureKind.PAYLOAD, error.kind)
    }

    @Test
    fun `payload transfer failure emits a payload Error for the connected endpoint`() = runTest {
        val events = collectEvents()
        advertise()
        connect("ep1")

        payloads.captured.onPayloadTransferUpdate("ep1", transferUpdate(PayloadTransferUpdate.Status.IN_PROGRESS))
        payloads.captured.onPayloadTransferUpdate("ep1", transferUpdate(PayloadTransferUpdate.Status.SUCCESS))
        payloads.captured.onPayloadTransferUpdate("ep1", transferUpdate(PayloadTransferUpdate.Status.FAILURE))
        payloads.captured.onPayloadTransferUpdate("ep1", transferUpdate(PayloadTransferUpdate.Status.CANCELED))
        payloads.captured.onPayloadTransferUpdate("ep2", transferUpdate(PayloadTransferUpdate.Status.FAILURE))
        runCurrent()

        val errors = events.filterIsInstance<BleEvent.Error>()
        assertEquals(
            listOf(
                BleEvent.Error("payload_transfer", "status=FAILURE", "ep1", RadioFailureKind.PAYLOAD),
                BleEvent.Error("payload_transfer", "status=CANCELED", "ep1", RadioFailureKind.PAYLOAD)
            ),
            errors
        )
    }

    // --- Backpressure and the fault channel ---

    @Test
    fun `overflow publishes a fault that stopAllEndpoints clears together with the endpoints`() = runTest {
        advertise()
        connect("ep1")
        every { client.stopDiscovery() } throws SecurityException("denied")
        val stuck = launch { nearbySync.events.collect { awaitCancellation() } }
        runCurrent()

        repeat(NearbySync.EVENT_BUFFER_CAPACITY + 3) { nearbySync.stopDiscovery() }

        val fault = nearbySync.fault.value
        assertNotNull(fault)
        assertEquals("event_overflow", fault!!.kind)
        assertEquals("Error", fault.detail)
        assertTrue(fault.sequence >= 2)

        nearbySync.stopAllEndpoints()
        stuck.cancel()
        runCurrent()

        assertNull(nearbySync.fault.value)
        val events = collectEvents()
        payloads.captured.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(1)))
        lifecycle.captured.onDisconnected("ep1")
        runCurrent()
        assertEquals(emptyList<BleEvent>(), events)
    }

    // --- Lifecycle submission ownership and link-specific task/payload callbacks ---

    @Test
    fun `a disconnection through an earlier submission's callback leaves a later submission's connected link intact`() =
        runTest {
            val events = collectEvents()
            val earlier = advertise()
            val later = request("ep1")
            connect("ep1", via = later, info = outgoingInfo())
            assertConnected(events.single(), "ep1", incoming = false)

            earlier.onDisconnected("ep1")
            runCurrent()
            assertEquals(1, events.size)

            // The link is still the connected one: its own payload callback keeps delivering.
            payloadCallbacks.last().onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(4, 2)))
            runCurrent()
            val received = events.last() as BleEvent.PayloadReceived
            assertEquals("ep1", received.endpointId)
            assertArrayEquals(byteArrayOf(4, 2), received.data)
            assertEquals(listOf("Connected", "PayloadReceived"), events.map { it::class.simpleName })
        }

    @Test
    fun `a success result through an earlier callback neither settles nor closes a later submission's link`() =
        runTest {
            val events = collectEvents()
            val earlier = advertise()
            val later = request("ep1")
            stubAccept()
            later.onConnectionInitiated("ep1", outgoingInfo())
            runCurrent()

            earlier.onConnectionResult("ep1", success())
            runCurrent()

            assertEquals(emptyList<BleEvent>(), events)
            verify(exactly = 0) { client.disconnectFromEndpoint(any()) }

            // The pending link still belongs to the later submission, which settles it.
            later.onConnectionResult("ep1", success())
            runCurrent()
            assertConnected(events.single(), "ep1", incoming = false)
        }

    @Test
    fun `a failed accept task of a replaced link emits nothing and leaves the replacement link in place`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        stubAccept()
        callback.onConnectionInitiated("ep1", incomingInfo())
        callback.onConnectionInitiated("ep1", incomingInfo())
        runCurrent()
        assertEquals(2, acceptTasks.size)

        // The platform settles the first (superseded) accept as failed after the second initiation replaced it.
        acceptTasks[0].fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        runCurrent()
        assertEquals(emptyList<BleEvent>(), events)

        callback.onConnectionResult("ep1", success())
        runCurrent()
        assertConnected(events.single(), "ep1", incoming = true)
    }

    @Test
    fun `an initiation from a cancelled request is rejected and a later request's is accepted`() = runTest {
        val events = collectEvents()
        val attempt = NearbyConnectionAttempt("ep1")
        val abandoned = request(attempt)
        nearbySync.cancelConnectionAttempt(attempt)
        runCurrent()
        assertEquals(emptyList<BleEvent>(), events)

        abandoned.onConnectionInitiated("ep1", outgoingInfo())
        abandoned.onConnectionResult("ep1", failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
        runCurrent()

        verify(exactly = 1) { client.rejectConnection("ep1") }
        verify(exactly = 0) { client.acceptConnection(any(), any()) }
        assertEquals(emptyList<BleEvent>(), events)

        val renewed = request("ep1")
        stubAccept()
        renewed.onConnectionInitiated("ep1", outgoingInfo())
        renewed.onConnectionResult("ep1", success())
        runCurrent()

        verify(exactly = 1) { client.acceptConnection("ep1", any()) }
        verify(exactly = 1) { client.rejectConnection("ep1") }
        assertConnected(events.single(), "ep1", incoming = false)
    }

    @Test
    fun `local disconnect announces a link once while attempt cancellation stays silent and drops the platform echo`() =
        runTest {
            val events = collectEvents()
            val callback = advertise()
            connect("ep1", via = callback)
            val attempt = NearbyConnectionAttempt("ep2")
            val outgoing = request(attempt)
            stubAccept()
            outgoing.onConnectionInitiated("ep2", outgoingInfo())
            runCurrent()
            assertEquals(listOf("Connected"), events.map { it::class.simpleName })

            nearbySync.disconnect(events.connection("ep1"))
            nearbySync.cancelConnectionAttempt(attempt)
            runCurrent()
            assertEquals(listOf<BleEvent>(BleEvent.Disconnected(events.connection("ep1"))), events.drop(1))
            verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
            verify(exactly = 1) { client.disconnectFromEndpoint("ep2") }

            callback.onDisconnected("ep1")
            outgoing.onDisconnected("ep2")
            outgoing.onConnectionResult("ep2", success())
            runCurrent()
            assertEquals(listOf<BleEvent>(BleEvent.Disconnected(events.connection("ep1"))), events.drop(1))
        }

    @Test
    fun `the run boundary announces each connected link once and a pending link not at all`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        connect("ep1", via = callback)
        connect("ep2", via = callback)
        callback.onConnectionInitiated("ep3", incomingInfo())
        runCurrent()
        assertEquals(listOf("Connected", "Connected"), events.map { it::class.simpleName })

        nearbySync.stopAllEndpoints()
        runCurrent()

        val ended = events.drop(2)
        assertEquals(2, ended.size)
        assertEquals(setOf("ep1", "ep2"), ended.map { (it as BleEvent.Disconnected).endpointId }.toSet())

        callback.onDisconnected("ep3")
        callback.onConnectionResult("ep3", failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
        runCurrent()
        assertEquals(2, events.drop(2).size)
    }

    @Test
    fun `a send refused as not connected emits Error then Disconnected whereas a payload IO error keeps the link`() =
        runTest {
            assertEquals(8005, ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)
            assertEquals(8013, ConnectionsStatusCodes.STATUS_PAYLOAD_IO_ERROR)
            val events = collectEvents()
            val callback = advertise()
            connect("ep1", via = callback)
            val payloadOfEp1 = payloadCallbacks.last()
            connect("ep2", via = callback)
            val payloadOfEp2 = payloadCallbacks.last()
            val started = events.size

            // A payload IO error is reported and the link stays connected.
            val ioSend = stubSend("ep2")
            nearbySync.sendPayload(events.connection("ep2"), byteArrayOf(2))
            ioSend.fail(ConnectionsStatusCodes.STATUS_PAYLOAD_IO_ERROR)
            payloadOfEp2.onPayloadReceived("ep2", Payload.fromBytes(byteArrayOf(2, 2)))
            runCurrent()

            val ioError = events[started] as BleEvent.Error
            assertEquals("send_payload", ioError.operation)
            assertEquals("ep2", ioError.endpointId)
            assertEquals(RadioFailureKind.PAYLOAD, ioError.kind)
            assertEquals("PayloadReceived", events[started + 1]::class.simpleName)
            assertEquals(started + 2, events.size)
            verify(exactly = 0) { client.disconnectFromEndpoint(any()) }

            // A send the platform refuses as not connected ends the link on the adapter and the platform.
            val refusedSend = stubSend("ep1")
            nearbySync.sendPayload(events.connection("ep1"), byteArrayOf(1))
            refusedSend.fail(ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)
            runCurrent()

            val refused = events[started + 2] as BleEvent.Error
            assertEquals("send_payload", refused.operation)
            assertEquals("ep1", refused.endpointId)
            assertEquals(RadioFailureKind.ENDPOINT, refused.kind)
            assertEquals(BleEvent.Disconnected(events.connection("ep1")), events[started + 3])
            assertEquals(started + 4, events.size)
            verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }

            callback.onDisconnected("ep1")
            payloadOfEp1.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(1, 1)))
            runCurrent()
            assertEquals(started + 4, events.size)
        }

    @Test
    fun `a send failure settling after the link ended locally emits nothing`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        connect("ep1", via = callback)
        val send = stubSend("ep1")
        nearbySync.sendPayload(events.connection("ep1"), byteArrayOf(1))

        nearbySync.disconnect(events.connection("ep1"))
        send.fail(ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)
        runCurrent()

        assertEquals(listOf("Connected", "Disconnected"), events.map { it::class.simpleName })
        assertEquals(BleEvent.Disconnected(events.connection("ep1")), events.last())
        verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
    }

    @Test
    fun `a replacement initiation ends the connected link and only the new payload callback delivers afterwards`() =
        runTest {
            val events = collectEvents()
            val callback = advertise()
            connect("ep1", via = callback)
            val oldPayloads = payloadCallbacks.last()

            callback.onConnectionInitiated("ep1", incomingInfo())
            runCurrent()
            assertEquals(listOf<BleEvent>(BleEvent.Disconnected(events.connection("ep1"))), events.drop(1))
            assertEquals(2, payloadCallbacks.size)
            val newPayloads = payloadCallbacks.last()

            callback.onConnectionResult("ep1", success())
            runCurrent()
            assertEquals(listOf("Connected", "Disconnected", "Connected"), events.map { it::class.simpleName })

            oldPayloads.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(9)))
            newPayloads.onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(7)))
            newPayloads.onPayloadTransferUpdate("ep2", transferUpdate(PayloadTransferUpdate.Status.FAILURE))
            runCurrent()

            val delivered = events.drop(3)
            assertEquals(1, delivered.size)
            val received = delivered.single() as BleEvent.PayloadReceived
            assertEquals("ep1", received.endpointId)
            assertArrayEquals(byteArrayOf(7), received.data)
        }

    @Test
    fun `a discovery callback from before the run boundary drops endpoints once discovery is on again`() = runTest {
        val events = collectEvents()
        val stale = discover()
        nearbySync.stopAllEndpoints()
        val fresh = discover()

        stale.onEndpointFound("ep1", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "aabbccdd"))
        stale.onEndpointLost("ep1")
        fresh.onEndpointFound("ep2", DiscoveredEndpointInfo(NearbySync.SERVICE_ID, "11223344"))
        runCurrent()

        assertEquals(listOf<BleEvent>(BleEvent.PeerFound(NearbyPeer("ep2", "11223344"))), events)
    }

    // --- Same-advertising-submission retirement ---
    // A matching terminal event clears retirement; these tests do not prove which incarnation it belongs to.

    @Test
    fun `an incoming link ended locally keeps its endpoint refused for its advertising submission until confirmed`() =
        runTest {
            val events = collectEvents()
            val callback = advertise()
            connect("ep1", via = callback)
            assertConnected(events.single(), "ep1", incoming = true)

            nearbySync.disconnect(events.connection("ep1"))
            runCurrent()
            assertEquals(listOf<BleEvent>(BleEvent.Disconnected(events.connection("ep1"))), events.drop(1))
            verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }

            // No terminal callback has cleared retirement, so this initiation is refused without a new link or event.
            callback.onConnectionInitiated("ep1", incomingInfo())
            runCurrent()
            verify(exactly = 1) { client.rejectConnection("ep1") }
            verify(exactly = 1) { client.acceptConnection("ep1", any()) }
            assertEquals(2, events.size)

            // This script treats the disconnection as the old end; the endpoint-only callback itself cannot prove that.
            callback.onDisconnected("ep1")
            runCurrent()
            assertEquals(2, events.size)
            verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }

            connect("ep1", via = callback)
            verify(exactly = 2) { client.acceptConnection("ep1", any()) }
            assertConnected(events.last(), "ep1", incoming = true)
            assertEquals(3, events.size)

            // The new link is settled by the same callback as any other incoming link.
            callback.onDisconnected("ep1")
            runCurrent()
            assertEquals(listOf<BleEvent>(BleEvent.Disconnected(events.connection("ep1"))), events.drop(3))
        }

    @Test
    fun `a failed result for a locally disconnected incoming link confirms its end and lifts the retirement`() =
        runTest {
            val events = collectEvents()
            val callback = advertise()
            connect("ep1", via = callback)
            nearbySync.disconnect(events.connection("ep1"))
            runCurrent()
            assertEquals(listOf("Connected", "Disconnected"), events.map { it::class.simpleName })

            callback.onConnectionInitiated("ep1", incomingInfo())
            runCurrent()
            verify(exactly = 1) { client.rejectConnection("ep1") }

            // A rejected successor can also produce this endpoint-only failure; clearing retirement does not
            // establish that the original link's terminal callback has already arrived.
            callback.onConnectionResult("ep1", failure(ConnectionsStatusCodes.STATUS_ERROR))
            runCurrent()
            assertEquals(listOf("Connected", "Disconnected"), events.map { it::class.simpleName })

            connect("ep1", via = callback)
            assertConnected(events.last(), "ep1", incoming = true)
            assertEquals(3, events.size)
            verify(exactly = 1) { client.rejectConnection("ep1") }
        }

    @Test
    fun `a retired endpoint is still connectable through a local request and a later advertising submission`() =
        runTest {
            val events = collectEvents()
            val first = advertise()
            connect("ep1", via = first)
            nearbySync.disconnect(events.connection("ep1"))
            runCurrent()
            assertEquals(listOf("Connected", "Disconnected"), events.map { it::class.simpleName })

            // A request has its own callback, so earlier submission results/disconnections cannot settle its link.
            val outgoing = request("ep1")
            connect("ep1", via = outgoing, info = outgoingInfo())
            assertConnected(events.last(), "ep1", incoming = false)
            first.onDisconnected("ep1")
            first.onConnectionResult("ep1", failure(ConnectionsStatusCodes.STATUS_ERROR))
            runCurrent()
            assertEquals(3, events.size)
            verify(exactly = 1) { client.disconnectFromEndpoint("ep1") }
            payloadCallbacks.last().onPayloadReceived("ep1", Payload.fromBytes(byteArrayOf(1)))
            runCurrent()
            assertEquals("PayloadReceived", events.last()::class.simpleName)

            nearbySync.disconnect(events.connection("ep1"))
            runCurrent()
            assertEquals("Disconnected", events.last()::class.simpleName)

            // A later advertising submission is a different owner: its incoming initiation is accepted.
            nearbySync.stopAdvertising()
            val second = advertise()
            connect("ep1", via = second)
            assertConnected(events.last(), "ep1", incoming = true)
            verify(exactly = 0) { client.rejectConnection(any()) }

            // A disconnection through the first submission cannot settle the second submission's link.
            first.onDisconnected("ep1")
            runCurrent()
            assertEquals("Connected", events.last()::class.simpleName)
        }

    @Test
    fun `the run boundary clears retirements`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        connect("ep1", via = callback)
        nearbySync.disconnect(events.connection("ep1"))
        runCurrent()
        callback.onConnectionInitiated("ep1", incomingInfo())
        runCurrent()
        verify(exactly = 1) { client.rejectConnection("ep1") }

        nearbySync.stopAllEndpoints()
        val renewed = advertise()
        connect("ep1", via = renewed)
        assertConnected(events.last(), "ep1", incoming = true)
        verify(exactly = 1) { client.rejectConnection("ep1") }
    }

    @Test
    fun `a send refused as not connected retires the incoming endpoint until the platform confirms`() = runTest {
        val events = collectEvents()
        val callback = advertise()
        connect("ep1", via = callback)
        val send = stubSend("ep1")
        nearbySync.sendPayload(events.connection("ep1"), byteArrayOf(1))
        send.fail(ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)
        runCurrent()
        assertEquals(listOf("Connected", "Error", "Disconnected"), events.map { it::class.simpleName })

        callback.onConnectionInitiated("ep1", incomingInfo())
        runCurrent()
        verify(exactly = 1) { client.rejectConnection("ep1") }

        callback.onDisconnected("ep1")
        connect("ep1", via = callback)
        assertConnected(events.last(), "ep1", incoming = true)
        assertEquals(4, events.size)
    }

    // --- Helpers ---

    private fun stubAdvertising(): FakeTask {
        val task = FakeTask()
        every { client.startAdvertising(any<String>(), any(), capture(lifecycle), any()) } answers {
            lifecycles += lifecycle.captured
            task.task
        }
        return task
    }

    private fun stubDiscovery(): FakeTask {
        val task = FakeTask()
        every { client.startDiscovery(any(), capture(discovery), any()) } answers {
            discoveries += discovery.captured
            task.task
        }
        return task
    }

    private fun stubRequest(endpointId: String): FakeTask {
        val task = FakeTask()
        every { client.requestConnection(any<String>(), endpointId, capture(lifecycle)) } answers {
            lifecycles += lifecycle.captured
            task.task
        }
        return task
    }

    /**
     * Gives each acceptConnection call a fresh task and payload callback entry.
     *
     * - [payloadCallbacks] and [acceptTasks] use matching zero-based indices in call order.
     */
    private fun stubAccept() {
        every { client.acceptConnection(any(), capture(payloads)) } answers {
            payloadCallbacks += payloads.captured
            FakeTask().also { acceptTasks += it }.task
        }
    }

    /** Starts advertising and settles the task as successful; returns the callback of this submission. */
    private fun TestScope.advertise(): ConnectionLifecycleCallback {
        val task = stubAdvertising()
        val pending: Deferred<RadioOutcome> = async { nearbySync.startAdvertising() }
        runCurrent()
        task.succeed()
        runCurrent()
        assertEquals(RadioOutcome.Success, pending.getCompleted())
        assertTrue(task.completionObserved)
        return lifecycles.last()
    }

    /** Starts discovery and settles the task as successful; returns the callback of this submission. */
    private fun TestScope.discover(): EndpointDiscoveryCallback {
        val task = stubDiscovery()
        val pending: Deferred<RadioOutcome> = async { nearbySync.startDiscovery() }
        runCurrent()
        task.succeed()
        runCurrent()
        assertEquals(RadioOutcome.Success, pending.getCompleted())
        return discoveries.last()
    }

    private fun TestScope.request(endpointId: String): ConnectionLifecycleCallback =
        request(NearbyConnectionAttempt(endpointId))

    /** Submits [attempt] and settles the task as successful; returns this submission's callback. */
    private fun TestScope.request(attempt: NearbyConnectionAttempt): ConnectionLifecycleCallback {
        val task = stubRequest(attempt.endpointId)
        val pending: Deferred<RadioOutcome> = async { nearbySync.requestConnection(attempt) }
        runCurrent()
        task.succeed()
        runCurrent()
        assertEquals(RadioOutcome.Success, pending.getCompleted())
        return lifecycles.last()
    }

    /**
     * Completes a connection with [endpointId] through [via] so that it is connected and its payload callback is
     * the last in [payloadCallbacks] and in [payloads].
     */
    private fun TestScope.connect(
        endpointId: String,
        via: ConnectionLifecycleCallback = lifecycle.captured,
        info: ConnectionInfo = incomingInfo()
    ) {
        stubAccept()
        via.onConnectionInitiated(endpointId, info)
        via.onConnectionResult(endpointId, success())
        runCurrent()
    }

    /** Collects every event emitted after this call into the returned list. */
    private fun TestScope.collectEvents(): List<BleEvent> {
        val seen = mutableListOf<BleEvent>()
        backgroundScope.launch { nearbySync.events.collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun List<BleEvent>.connection(endpointId: String): NearbyConnection =
        filterIsInstance<BleEvent.Connected>().last { it.endpointId == endpointId }.connection

    /** Stubs sendPayload to [endpointId] with a task the test settles by hand. */
    private fun stubSend(endpointId: String): FakeTask {
        val task = FakeTask()
        every { client.sendPayload(endpointId, any<Payload>()) } returns task.task
        return task
    }

    /** [event] is the Connected event for [endpointId] carrying the role, token and name captured at initiation. */
    private fun assertConnected(event: BleEvent, endpointId: String, incoming: Boolean) {
        val connected = event as BleEvent.Connected
        assertEquals(endpointId, connected.endpointId)
        assertEquals(incoming, connected.isIncoming)
        assertArrayEquals(TOKEN.toByteArray(), connected.authToken)
        assertEquals(PEER_NAME, connected.endpointName)
    }

    private fun incomingInfo() = ConnectionInfo(PEER_NAME, TOKEN, true)

    private fun outgoingInfo() = ConnectionInfo(PEER_NAME, TOKEN, false)

    private fun success() = ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))

    private fun failure(statusCode: Int) = ConnectionResolution(Status(statusCode))

    private fun transferUpdate(status: Int): PayloadTransferUpdate =
        PayloadTransferUpdate.Builder().setPayloadId(7).setStatus(status).build()

    private companion object {
        const val PEER_NAME = "aabbccdd"
        const val TOKEN = "token"
    }
}
