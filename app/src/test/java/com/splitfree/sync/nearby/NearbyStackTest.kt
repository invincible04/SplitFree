package com.splitfree.sync.nearby

import android.content.Context
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.NearbySync
import com.splitfree.data.identity.IdentityManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adapter/controller/coordinator integration over [ScriptedConnectionsClient] and [FakeReconciliationStore].
 *
 * - Tests invoke captured callback objects and choose task completion order on a virtual-time scheduler, including
 *   reentrant injection at the session/adapter boundary.
 * - They verify cross-layer ownership under those interleavings, not Google Play services scheduling or physical radio
 *   behavior.
 * - Silent remote fixtures leave open sessions AUTHENTICATING; full authenticated exchange is covered by the
 *   coordinator/Room suites.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class NearbyStackTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val client = ScriptedConnectionsClient()
    private val identity = TestIdentity(1)
    private val identityManager = mockk<IdentityManager>()
    private val store = FakeReconciliationStore(identity.pub, mutableSetOf(identity.pub), identity.pub)
    private lateinit var nearbySync: NearbySync
    private lateinit var coordinator: NearbySessionCoordinator
    private lateinit var controller: NearbySessionController

    /** Hold only the controller subscriber or request entry, leaving the real adapter and coordinator running. */
    private var controllerSubscribeGate: CompletableDeferred<Unit>? = null
    private var requestSubmitGate: CompletableDeferred<Unit>? = null

    /** Inject SDK callbacks at the session/adapter boundary while the coordinator still holds its lock. */
    private var beforeSend: ((NearbyConnection, ByteArray) -> Unit)? = null

    /** Every adapter event in emission order, delivered on the next scheduler pass. */
    private val adapterEvents = mutableListOf<BleEvent>()

    /** Eagerly observed coordinator snapshots for these interleavings; StateFlow is not an event history. */
    private val coordinatorHistory = mutableListOf<NearbySessionsState>()

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        mockkStatic(Nearby::class)
        every { Nearby.getConnectionsClient(any<Context>()) } returns client.mock
        every { identityManager.getPublicKeyHex() } returns identity.pub
        assertEquals(64, identity.pub.length)
        nearbySync = NearbySync(mockk<Context>(relaxed = true), identityManager)
        coordinator =
            NearbySessionCoordinator(
                transport = object : NearbyTransport by nearbySync {
                    override fun sendPayload(connection: NearbyConnection, data: ByteArray) {
                        beforeSend?.invoke(connection, data)
                        nearbySync.sendPayload(connection, data)
                    }
                },
                identity = identity.contract,
                store = store,
                appScope = scope,
                clock = { scheduler.currentTime }
            )
        val controllerRadio = object : NearbyRadio by nearbySync {
            override val events = object : SharedFlow<BleEvent> {
                override val replayCache get() = nearbySync.events.replayCache

                override suspend fun collect(collector: FlowCollector<BleEvent>): Nothing {
                    controllerSubscribeGate?.await()
                    nearbySync.events.collect(collector)
                }
            }

            override suspend fun requestConnection(attempt: NearbyConnectionAttempt): RadioOutcome {
                requestSubmitGate?.await()
                return nearbySync.requestConnection(attempt)
            }
        }
        controller = NearbySessionController(controllerRadio, coordinator, scope, { scheduler.currentTime }, { 0.0 })
        scope.launch { nearbySync.events.collect { adapterEvents += it } }
        scope.launch(UnconfinedTestDispatcher(scheduler)) { coordinator.state.collect { coordinatorHistory += it } }
        run()
    }

    @After
    fun teardown() {
        scope.cancel()
        unmockkStatic(Nearby::class)
        unmockkStatic(android.util.Log::class)
    }

    // ------------------------------------------------------------------ helpers

    private val state: NearbyRunState get() = controller.state.value

    private fun run() = scheduler.runCurrent()

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun attempt(endpointId: String): AttemptState? = state.peers[endpointId]?.attempt

    private fun session(endpointId: String): PeerProgress? = coordinator.state.value.peers[endpointId]

    private fun timeline(runId: Long, event: String, endpointId: String? = null): List<TimelineEntry> =
        controller.timeline.value.filter {
            it.runId == runId && it.event == event && (endpointId == null || it.endpointId == endpointId)
        }

    private fun success() = ConnectionResolution(Status.RESULT_SUCCESS)

    private fun failure(statusCode: Int) = ConnectionResolution(Status(statusCode))

    private fun info(name: String, incoming: Boolean, token: String = TOKEN) = ConnectionInfo(name, token, incoming)

    /** Begins a run for the current owner and settles both start tasks with success. */
    private fun startRun(owner: NearbyOwner) {
        controller.start(owner, GROUP_ID)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        val advertising = client.advertisings.last()
        val discovery = client.discoveries.last()
        assertFalse(advertising.task!!.settled)
        assertFalse(discovery.task!!.settled)
        advertising.task.succeed()
        discovery.task.succeed()
        run()
        assertEquals(CapabilityState.Running, state.advertising)
        assertEquals(CapabilityState.Running, state.discovery)
        assertTrue(coordinator.state.value.active)
    }

    private fun startRunning(): NearbyOwner {
        val owner = controller.acquire()
        startRun(owner)
        return owner
    }

    /** Reports [endpointId] found through the latest discovery submission's callback. */
    private fun found(endpointId: String, name: String = PEER_NAME) {
        client.discoveries.last().discovery!!.onEndpointFound(
            endpointId,
            DiscoveredEndpointInfo(NearbySync.SERVICE_ID, name)
        )
        run()
        assertEquals(true, state.peers[endpointId]?.discovered)
        assertEquals(AttemptState.None, attempt(endpointId))
    }

    /** Requests a connection and settles the request task with success; returns the request call. */
    private fun request(owner: NearbyOwner, endpointId: String): ScriptedConnectionsClient.Call {
        val before = client.requests(endpointId).size
        controller.connect(owner, endpointId)
        run()
        assertEquals(before + 1, client.requests(endpointId).size)
        val request = client.requests(endpointId).last()
        request.task!!.succeed()
        run()
        assertTrue("attempt is ${attempt(endpointId)}", attempt(endpointId) is AttemptState.AwaitingConnection)
        return request
    }

    /** Delivers an initiation through [lifecycle] and returns the accept call the adapter made for it. */
    private fun initiate(
        lifecycle: ConnectionLifecycleCallback,
        endpointId: String,
        name: String = PEER_NAME,
        incoming: Boolean = false,
        token: String = TOKEN
    ): ScriptedConnectionsClient.Call {
        val before = client.accepts.size
        lifecycle.onConnectionInitiated(endpointId, info(name, incoming, token))
        assertEquals("acceptConnection submitted", before + 1, client.accepts.size)
        val accept = client.accepts.last()
        assertEquals(endpointId, accept.endpointId)
        return accept
    }

    /**
     * Connects [endpointId] outgoing: request, initiation through the request's callback, accept success and result
     * success.
     *
     * - Leaves the controller attempt Connected and the coordinator session AUTHENTICATING.
     */
    private fun connectOutgoing(owner: NearbyOwner, endpointId: String): ScriptedConnectionsClient.Call {
        val request = request(owner, endpointId)
        val accept = initiate(request.lifecycle!!, endpointId)
        accept.task!!.succeed()
        request.lifecycle.onConnectionResult(endpointId, success())
        run()
        val connected = attempt(endpointId)
        assertTrue("attempt is $connected", connected is AttemptState.Connected && !connected.incoming)
        assertEquals(PeerPhase.AUTHENTICATING, session(endpointId)?.phase)
        assertTrue(client.messagesTo(endpointId).last() is Hello)
        return request
    }

    private fun touchStore() {
        store.changes.value = StoreVersion(store.changes.value.revision + 1)
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    fun `radios wait for controller subscription and retain the very first discovered peer`() {
        val gate = CompletableDeferred<Unit>()
        controllerSubscribeGate = gate
        val owner = controller.acquire()
        controller.start(owner, GROUP_ID)
        run()

        assertTrue(coordinator.state.value.active)
        assertEquals(RunPhase.STARTING, state.phase)
        assertTrue(client.advertisings.isEmpty())
        assertTrue(client.discoveries.isEmpty())
        gate.complete(Unit)
        run()

        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(1, client.advertisings.size)
        assertEquals(1, client.discoveries.size)
        // Deliver before discovery's Task outcome, with no prior PeerFound to replay.
        found(E1)
        assertEquals(1, adapterEvents.filterIsInstance<BleEvent.PeerFound>().size)
        assertEquals(PEER_NAME, state.peers[E1]?.name)
        client.advertisings.single().task!!.succeed()
        client.discoveries.single().task!!.succeed()
        run()
        assertEquals(true, state.peers[E1]?.discovered)
    }

    @Test
    fun `stop cancels subscription wait and a later run uses its own readiness barrier`() {
        val oldGate = CompletableDeferred<Unit>()
        controllerSubscribeGate = oldGate
        val owner = controller.acquire()
        controller.start(owner, GROUP_ID)
        run()
        assertEquals(RunPhase.STARTING, state.phase)
        controller.stop(owner)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertFalse(coordinator.state.value.active)

        val newGate = CompletableDeferred<Unit>()
        controllerSubscribeGate = newGate
        controller.start(owner, GROUP_ID)
        run()
        oldGate.complete(Unit)
        run()
        assertEquals(RunPhase.STARTING, state.phase)
        assertTrue(client.discoveries.isEmpty())
        assertTrue(client.advertisings.isEmpty())
        newGate.complete(Unit)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(2L, state.runId)
        assertEquals(1, client.advertisings.size)
        assertEquals(1, client.discoveries.size)
        found(E1)
    }

    @Test
    fun `cancel before request dispatch makes no SDK request and fresh reconnect succeeds`() {
        val owner = startRunning()
        found(E1)
        controller.connect(owner, E1)
        controller.cancelAttempt(owner, E1)
        run()
        assertEquals(AttemptState.None, attempt(E1))
        assertTrue(client.requests.isEmpty())
        assertTrue(client.accepts.isEmpty())
        assertTrue(client.disconnects.isEmpty())
        assertNull(session(E1))

        val fresh = request(owner, E1)
        fresh.lifecycle!!.onConnectionInitiated(E1, info(PEER_NAME, incoming = false))
        fresh.lifecycle.onConnectionResult(E1, success())
        run()
        assertEquals(1, client.accepts.size)
        assertTrue(attempt(E1) is AttemptState.Connected)
        assertNotNull(session(E1))
    }

    @Test
    fun `cancel after request work starts but before adapter submission still revokes the exact token`() {
        val owner = startRunning()
        found(E1)
        val gate = CompletableDeferred<Unit>()
        requestSubmitGate = gate
        controller.connect(owner, E1)
        run()
        assertTrue(attempt(E1) is AttemptState.Requesting)
        assertTrue(client.requests.isEmpty())
        controller.cancelAttempt(owner, E1)
        run()
        gate.complete(Unit)
        run()
        assertEquals(AttemptState.None, attempt(E1))
        assertTrue(client.requests.isEmpty())
        assertTrue(client.accepts.isEmpty())
        assertNull(session(E1))
    }

    @Test
    fun `attempt cancel queued before Connected is collected cannot end the accepted link`() {
        val owner = startRunning()
        found(E1)
        val request = request(owner, E1)
        val accept = initiate(request.lifecycle!!, E1)
        accept.task!!.succeed()

        // Queue the user command first. SDK success arrives before that command runs, while the
        // controller still thinks it is awaiting a connection and has not collected Connected.
        controller.cancelAttempt(owner, E1)
        request.lifecycle.onConnectionResult(E1, success())
        assertTrue(attempt(E1) is AttemptState.AwaitingConnection)
        assertTrue(adapterEvents.none { it is BleEvent.Connected })
        run()

        assertEquals(1, timeline(1, "connect_cancelled", E1).size)
        assertTrue(attempt(E1) is AttemptState.Connected)
        assertEquals(PeerPhase.AUTHENTICATING, session(E1)?.phase)
        assertTrue(client.messagesTo(E1).single() is Hello)
        assertTrue(client.disconnects.isEmpty())
        assertTrue(adapterEvents.none { it is BleEvent.Disconnected })
    }

    @Test
    fun `timed out session cannot send Close or disconnect a replacement before queued events drain`() {
        val owner = startRunning()
        found(E1)
        val oldRequest = connectOutgoing(owner, E1)
        val oldConnection = adapterEvents.filterIsInstance<BleEvent.Connected>().single().connection
        val sendsBefore = client.sends.size
        val disconnectsBefore = client.disconnects.size
        var preempted = false

        beforeSend = { connection, data ->
            val message = (NearbyWire.decode(data) as? NearbyWire.Decoded.Message)?.message
            if (message is Close && connection === oldConnection) {
                assertEquals(NearbyWire.CLOSE_TIMEOUT, message.reason)
                beforeSend = null
                preempted = true
                // Inject ordered disconnect/reconnect callbacks after the watchdog decides to close but
                // before its send reaches the adapter. This isolates connection fencing while the
                // coordinator mutex still belongs to the old session; no callback reordering is required.
                oldRequest.lifecycle!!.onDisconnected(E1)
                val incoming = client.advertisings.last().lifecycle!!
                initiate(incoming, E1, incoming = true).task!!.succeed()
                incoming.onConnectionResult(E1, success())
                assertEquals(sendsBefore, client.sends.size)
                assertEquals(PeerPhase.AUTHENTICATING, session(E1)?.phase)
            }
        }

        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)

        assertTrue("watchdog was preempted before the adapter send", preempted)
        assertEquals("only B's Hello may reach the SDK", sendsBefore + 1, client.sends.size)
        assertTrue(client.messagesTo(E1).last() is Hello)
        assertEquals(disconnectsBefore, client.disconnects.size)
        val connections = adapterEvents.filterIsInstance<BleEvent.Connected>()
        assertEquals(2, connections.size)
        assertTrue(oldConnection !== connections.last().connection)
        assertEquals(
            listOf(oldConnection),
            adapterEvents.filterIsInstance<BleEvent.Disconnected>().map {
                it.connection
            }
        )
        assertEquals(PeerPhase.AUTHENTICATING, session(E1)?.phase)
        assertTrue((attempt(E1) as AttemptState.Connected).incoming)

        // Current-owner teardown still works: the capability fence must not suppress B's own Close.
        controller.stop(owner)
        run()
        assertTrue(client.messagesTo(E1).last() is Close)
        assertEquals(disconnectsBefore + 1, client.disconnects.size)
    }

    @Test
    fun `handshake timeout closes the session, ends the link once and leaves the peer retryable`() {
        val owner = startRunning()
        found(E1)
        val request = connectOutgoing(owner, E1)
        val disconnectedBefore = adapterEvents.count { it is BleEvent.Disconnected }

        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)

        val terminal = session(E1)
        assertNotNull(terminal)
        assertEquals(PeerPhase.INTERRUPTED, terminal!!.phase)
        assertEquals(NearbyWire.CLOSE_TIMEOUT, terminal.closeReason)
        assertTrue(client.messagesTo(E1).last() is Close)
        assertEquals(1, client.disconnects(E1).size)
        assertEquals(disconnectedBefore + 1, adapterEvents.count { it is BleEvent.Disconnected })
        assertEquals(AttemptState.None, attempt(E1))
        assertEquals(true, state.peers[E1]?.discovered)
        assertEquals(1, timeline(1, "disconnected", E1).size)

        // The platform's own disconnection for the link the adapter already ended adds nothing.
        request.lifecycle!!.onDisconnected(E1)
        run()
        assertEquals(disconnectedBefore + 1, adapterEvents.count { it is BleEvent.Disconnected })
        assertEquals(1, timeline(1, "disconnected", E1).size)
        assertEquals(AttemptState.None, attempt(E1))
        assertEquals(PeerPhase.INTERRUPTED, session(E1)?.phase)

        controller.connect(owner, E1)
        run()
        assertEquals(2, client.requests(E1).size)
        assertTrue(attempt(E1) is AttemptState.Requesting)
        assertTrue(timeline(1, "connect_ignored", E1).isEmpty())
    }

    @Test
    fun `a send the platform refuses as not connected ends the link and leaves the peer retryable`() {
        val owner = startRunning()
        found(E1)
        connectOutgoing(owner, E1)
        val hello = client.sends.last()
        assertEquals(E1, hello.endpointId)
        assertEquals(8005, ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)

        hello.task!!.fail(ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT)
        run()

        val terminal = session(E1)
        assertNotNull(terminal)
        assertEquals(PeerPhase.INTERRUPTED, terminal!!.phase)
        assertEquals(NearbyWire.CLOSE_PEER_DISCONNECTED, terminal.closeReason)
        assertEquals(1, client.disconnects(E1).size)
        val error = adapterEvents.filterIsInstance<BleEvent.Error>().single()
        assertEquals("send_payload", error.operation)
        assertEquals(E1, error.endpointId)
        assertEquals(RadioFailureKind.ENDPOINT, error.kind)
        assertEquals(listOf(E1), adapterEvents.filterIsInstance<BleEvent.Disconnected>().map { it.endpointId })
        assertEquals(AttemptState.None, attempt(E1))
        assertEquals(true, state.peers[E1]?.discovered)
        assertEquals(RunPhase.ACTIVE, state.phase)
        // The session is gone, so no Close frame follows the refused Hello.
        assertEquals(1, client.messagesTo(E1).size)

        controller.connect(owner, E1)
        run()
        assertEquals(2, client.requests(E1).size)
        assertTrue(attempt(E1) is AttemptState.Requesting)
    }

    @Test
    fun `callbacks of an earlier run cannot touch the replacement link of the next run`() {
        val owner = startRunning()
        found(E1)
        val requestA = connectOutgoing(owner, E1)
        val acceptA = client.accepts.single()
        assertEquals(1L, state.runId)

        controller.stop(owner)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertFalse(coordinator.state.value.active)
        assertEquals(1, client.disconnects(E1).size)
        assertEquals(2, client.stopAllEndpoints.size)

        startRun(owner)
        assertEquals(2L, state.runId)
        found(E1)
        val requestB = connectOutgoing(owner, E1)
        assertTrue(requestA.lifecycle !== requestB.lifecycle)
        assertEquals(2, client.accepts.size)
        val connectedB = attempt(E1)
        val eventsBefore = adapterEvents.size
        val sendsBefore = client.sends.size
        val disconnectsBefore = client.disconnects(E1).size

        requestA.lifecycle!!.onDisconnected(E1)
        requestA.lifecycle.onConnectionResult(E1, success())
        requestA.lifecycle.onConnectionInitiated(E1, info(PEER_NAME, incoming = false))
        acceptA.task!!.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        run()

        assertEquals(connectedB, attempt(E1))
        assertTrue(attempt(E1) is AttemptState.Connected)
        assertEquals(PeerPhase.AUTHENTICATING, session(E1)?.phase)
        assertTrue(coordinator.state.value.active)
        assertEquals(eventsBefore, adapterEvents.size)
        assertTrue(timeline(2, "disconnected").isEmpty())
        assertTrue(timeline(2, "connection_failed").isEmpty())
        assertTrue("An old callback must not reject the replacement endpoint", client.rejects(E1).isEmpty())
        assertEquals(2, client.accepts.size)
        assertEquals(sendsBefore, client.sends.size)
        assertEquals(disconnectsBefore, client.disconnects(E1).size)
    }

    @Test
    fun `callbacks of a cancelled attempt cannot touch the replacement attempt in the same run`() {
        val owner = startRunning()
        found(E1)
        val requestA = request(owner, E1)
        val acceptA = initiate(requestA.lifecycle!!, E1)
        assertFalse(acceptA.task!!.settled)

        controller.cancelAttempt(owner, E1)
        run()
        assertEquals(AttemptState.None, attempt(E1))
        assertEquals(true, state.peers[E1]?.discovered)
        assertEquals(1, client.disconnects(E1).size)
        // The abandoned link never connected, so its end is silent.
        assertTrue(adapterEvents.none { it is BleEvent.Disconnected })
        assertTrue(session(E1) == null)

        val requestB = connectOutgoing(owner, E1)
        assertTrue(requestA.lifecycle !== requestB.lifecycle)
        val connectedB = attempt(E1)
        val eventsBefore = adapterEvents.size

        acceptA.task.fail(ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN)
        requestA.lifecycle.onConnectionResult(E1, failure(ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED))
        run()

        assertEquals(connectedB, attempt(E1))
        assertEquals(PeerPhase.AUTHENTICATING, session(E1)?.phase)
        assertEquals(eventsBefore, adapterEvents.size)
        assertTrue(timeline(1, "connection_failed").isEmpty())
        assertTrue(timeline(1, "disconnected").isEmpty())
        assertEquals(1, client.disconnects(E1).size)
        assertEquals(2, client.requests(E1).size)
    }

    @Test
    fun `stop turns the radios off and reaches IDLE while store work holds the coordinator lock`() {
        val owner = startRunning()
        found(E1)
        connectOutgoing(owner, E1)
        val retryCallsBefore = store.retryCalls

        val gate = CompletableDeferred<Unit>()
        store.retryGate = gate
        touchStore()
        run()
        // The observer is inside retryDeferred with the coordinator lock held; the gate never completes on its own.
        assertEquals(retryCallsBefore, store.retryCalls)
        assertFalse(gate.isCompleted)

        controller.stop(owner)
        run()

        assertFalse(gate.isCompleted)
        assertTrue(client.stopDiscoveries.isNotEmpty())
        assertTrue(client.stopAdvertisings.isNotEmpty())
        assertFalse(coordinator.state.value.active)
        assertTrue(client.stopAllEndpoints.isNotEmpty())
        assertEquals(RunPhase.IDLE, state.phase)
        assertEquals(PeerPhase.CLOSED, session(E1)?.phase)
        assertTrue(client.messagesTo(E1).last() is Close)
        assertEquals(1, client.disconnects(E1).size)
        assertTrue(timeline(1, "coordinator_cleanup_late").isEmpty())
        assertEquals(1, timeline(1, "cleanup_complete").size)
        assertEquals(2, client.stopAllEndpoints.size)

        val callsBefore = client.calls.size
        gate.complete(Unit)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertFalse(coordinator.state.value.active)
        assertEquals(callsBefore, client.calls.size)
        assertEquals(retryCallsBefore, store.retryCalls)
    }

    @Test
    fun `stop followed by late platform acceptance of the start tasks leaves the radios off`() {
        val owner = controller.acquire()
        controller.start(owner, GROUP_ID)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        val advertising = client.advertisings.single()
        val discovery = client.discoveries.single()
        assertFalse(advertising.task!!.settled)
        assertFalse(discovery.task!!.settled)

        controller.stop(owner)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        assertEquals(1, client.stopAdvertisings.size)
        assertEquals(1, client.stopDiscoveries.size)
        assertEquals(1, client.stopAllEndpoints.size)

        val settledAt = client.nextSequence
        advertising.task.succeed()
        discovery.task.succeed()
        run()

        assertEquals(RunPhase.IDLE, state.phase)
        assertEquals(CapabilityState.Stopped, state.advertising)
        assertEquals(CapabilityState.Stopped, state.discovery)
        assertEquals(2, client.stopAdvertisings.size)
        assertEquals(2, client.stopDiscoveries.size)
        assertEquals(2, client.stopAllEndpoints.size)
        assertTrue(client.stopAdvertisings.last().sequence > advertising.sequence)
        assertTrue(client.stopDiscoveries.last().sequence > discovery.sequence)
        assertTrue(client.stopAdvertisings.last().sequence >= settledAt)
        assertTrue(client.stopDiscoveries.last().sequence >= settledAt)
        assertEquals(2, timeline(1, "ignored_stale_run").size)
        assertTrue(adapterEvents.isEmpty())

        discovery.discovery!!.onEndpointFound(E1, DiscoveredEndpointInfo(NearbySync.SERVICE_ID, PEER_NAME))
        run()
        assertTrue(adapterEvents.isEmpty())
        assertTrue(state.peers.isEmpty())
        assertEquals(RunPhase.IDLE, state.phase)
    }

    @Test
    fun `an incoming connection through the second run's advertising callback opens a session`() {
        val owner = startRunning()
        controller.stop(owner)
        run()
        assertEquals(RunPhase.IDLE, state.phase)

        startRun(owner)
        assertEquals(2L, state.runId)
        assertEquals(2, client.advertisings.size)
        val advertisingB = client.advertisings[1]
        assertTrue(advertisingB.lifecycle !== client.advertisings[0].lifecycle)

        val accept = initiate(advertisingB.lifecycle!!, E2, name = INCOMING_NAME, incoming = true)
        accept.task!!.succeed()
        advertisingB.lifecycle.onConnectionResult(E2, success())
        run()

        assertEquals(
            NearbyPeerState(E2, INCOMING_NAME, discovered = false, attempt = AttemptState.Connected(null, true)),
            state.peers[E2]
        )
        assertEquals(PeerPhase.AUTHENTICATING, session(E2)?.phase)
        assertEquals(GROUP_ID, session(E2)?.groupId)
        assertTrue(coordinator.state.value.active)
        assertEquals(1, client.messagesTo(E2).size)
        assertTrue(client.messagesTo(E2).single() is Hello)
        assertEquals(1, timeline(2, "connected", E2).size)
        val connected = adapterEvents.filterIsInstance<BleEvent.Connected>().single()
        assertEquals(E2, connected.endpointId)
        assertTrue(connected.isIncoming)
        assertEquals(INCOMING_NAME, connected.endpointName)
    }

    @Test
    fun `a replacement initiation on a connected endpoint retires the session once and opens a new one`() {
        val owner = startRunning()
        found(E1)
        connectOutgoing(owner, E1)
        val advertising = client.advertisings.single()
        val eventsBefore = adapterEvents.size
        val historyBefore = coordinatorHistory.size
        assertEquals(1, client.messagesTo(E1).count { it is Hello })

        val accept = initiate(advertising.lifecycle!!, E1, incoming = true, token = "token-2")
        accept.task!!.succeed()
        advertising.lifecycle.onConnectionResult(E1, success())
        run()

        val newEvents = adapterEvents.drop(eventsBefore)
        assertEquals(listOf("Disconnected", "Connected"), newEvents.map { it::class.simpleName })
        assertEquals(E1, (newEvents[0] as BleEvent.Disconnected).endpointId)
        val reconnected = newEvents[1] as BleEvent.Connected
        assertEquals(E1, reconnected.endpointId)
        assertTrue(reconnected.isIncoming)

        assertEquals(AttemptState.Connected(null, incoming = true), attempt(E1))
        assertEquals(true, state.peers[E1]?.discovered)
        assertEquals(1, timeline(1, "disconnected", E1).size)
        assertEquals(2, timeline(1, "connected", E1).size)

        assertEquals(setOf(E1), coordinator.state.value.peers.keys)
        assertEquals(PeerPhase.AUTHENTICATING, session(E1)?.phase)
        val retired =
            coordinatorHistory.drop(historyBefore).mapNotNull { it.peers[E1] }.filter {
                it.phase ==
                    PeerPhase.INTERRUPTED
            }
        assertEquals(1, retired.size)
        assertEquals(NearbyWire.CLOSE_PEER_DISCONNECTED, retired.single().closeReason)
        assertEquals(PeerPhase.AUTHENTICATING, coordinatorHistory.last().peers[E1]?.phase)
        assertEquals(2, client.messagesTo(E1).count { it is Hello })
        assertTrue(client.messagesTo(E1).none { it is Close })
        assertEquals(0, client.disconnects(E1).size)
    }

    @Test
    fun `an incoming peer the coordinator disconnected is refused via the same advertising callback until confirmed`() {
        startRunning()
        val lifecycle = client.advertisings.single().lifecycle!!
        val accept = initiate(lifecycle, E2, name = INCOMING_NAME, incoming = true)
        accept.task!!.succeed()
        lifecycle.onConnectionResult(E2, success())
        run()
        assertEquals(AttemptState.Connected(null, incoming = true), attempt(E2))
        assertEquals(PeerPhase.AUTHENTICATING, session(E2)?.phase)

        // The handshake times out: the coordinator disconnects the endpoint locally.
        advance(PeerSession.HANDSHAKE_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        assertEquals(PeerPhase.INTERRUPTED, session(E2)?.phase)
        assertEquals(1, client.disconnects(E2).size)
        assertNull(state.peers[E2])
        val eventsBefore = adapterEvents.size

        // The peer reconnects through the same callback before the platform confirmed the end: refused, and
        // neither the controller nor the coordinator sees anything.
        lifecycle.onConnectionInitiated(E2, info(INCOMING_NAME, incoming = true))
        run()
        assertEquals(1, client.rejects(E2).size)
        assertEquals(1, client.accepts.size)
        assertEquals(eventsBefore, adapterEvents.size)
        assertNull(state.peers[E2])
        assertEquals(PeerPhase.INTERRUPTED, session(E2)?.phase)

        // The platform confirms the earlier end; the reconnect is then served as a new incoming link.
        lifecycle.onDisconnected(E2)
        run()
        assertEquals(eventsBefore, adapterEvents.size)
        val again = initiate(lifecycle, E2, name = INCOMING_NAME, incoming = true)
        again.task!!.succeed()
        lifecycle.onConnectionResult(E2, success())
        run()
        assertEquals(AttemptState.Connected(null, incoming = true), attempt(E2))
        assertEquals(PeerPhase.AUTHENTICATING, session(E2)?.phase)
        assertEquals(2, client.messagesTo(E2).count { it is Hello })
        assertEquals(1, client.rejects(E2).size)
        assertEquals(1, client.disconnects(E2).size)
    }

    private companion object {
        const val GROUP_ID = "12345678-1234-1234-1234-123456789abc"
        const val E1 = "ep-1"
        const val E2 = "ep-2"
        const val PEER_NAME = "aabbccdd"
        const val INCOMING_NAME = "11223344"
        const val TOKEN = "token"
    }
}
