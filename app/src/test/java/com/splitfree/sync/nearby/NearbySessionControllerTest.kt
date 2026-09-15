package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import com.splitfree.data.ble.NearbyPeer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Controller lifecycle and retry contracts over [FakeNearbyRadio], normally with a real coordinator and scripted
 * store.
 *
 * - Deferred outcomes, scheduler-driven time and injected jitter make chosen interleavings reproducible; these tests
 *   do not exercise the SDK, physical radios or arbitrary thread scheduling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySessionControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val radio = FakeNearbyRadio()
    private val identity = TestIdentity(1)
    private val store = GatedStore(FakeReconciliationStore(identity.pub, mutableSetOf(identity.pub), identity.pub))
    private val coordinator =
        NearbySessionCoordinator(
            transport = radio,
            identity = identity.contract,
            store = store,
            appScope = scope,
            clock = { scheduler.currentTime }
        )
    private val controller = NearbySessionController(radio, coordinator, scope, { scheduler.currentTime }, { 0.0 })
    private val groupId = "12345678-1234-1234-1234-123456789abc"
    private val otherGroupId = "87654321-4321-4321-4321-cba987654321"

    /** Delegating store whose maintenance can be held back, which keeps coordinator activation in flight. */
    private class GatedStore(private val inner: ReconciliationStore) : ReconciliationStore by inner {
        var pruneGate: CompletableDeferred<Unit>? = null

        override suspend fun prune() {
            pruneGate?.await()
            inner.prune()
        }
    }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        scope.cancel()
        unmockkStatic(android.util.Log::class)
    }

    // ------------------------------------------------------------------ helpers

    private val state: NearbyRunState get() = controller.state.value

    private fun events(): List<String> = controller.timeline.value.map { it.event }

    private fun run() = scheduler.runCurrent()

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun attempt(endpointId: String): AttemptState? = state.peers[endpointId]?.attempt

    /** Acquires an owner and starts a run; both platform start tasks are left unsettled. */
    private fun startActive(): NearbyOwner {
        val owner = controller.acquire()
        controller.start(owner, groupId)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        return owner
    }

    /** Starts a run and settles both start tasks with success. */
    private fun startRunning(): NearbyOwner {
        val owner = startActive()
        radio.pendingAdvertising.complete(RadioOutcome.Success)
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(CapabilityState.Running, state.advertising)
        assertEquals(CapabilityState.Running, state.discovery)
        return owner
    }

    private fun found(endpointId: String, name: String = "name-$endpointId") {
        radio.emit(BleEvent.PeerFound(NearbyPeer(endpointId, name)))
        run()
    }

    private fun connected(endpointId: String, incoming: Boolean = false, name: String? = "name-$endpointId") {
        radio.emit(BleEvent.Connected(NearbyConnection(endpointId), incoming, "token".toByteArray(), name))
        run()
    }

    private fun permission(code: Int = 8034) = RadioOutcome.Failure(RadioFailureKind.PERMISSION, code, "permission")

    private fun radioFailure(code: Int = 8007) = RadioOutcome.Failure(RadioFailureKind.RADIO, code, "bluetooth")

    private fun assertOrdered(vararg calls: FakeNearbyRadio.Call) {
        val sequence = calls.map { it.sequence }
        assertEquals("expected call order $sequence", sequence.sorted(), sequence)
    }

    // ------------------------------------------------------------------ startup

    @Test
    fun `start submits advertising and discovery once, only after the coordinator is active`() {
        store.pruneGate = CompletableDeferred()
        var activeAtAdvertising: Boolean? = null
        var activeAtDiscovery: Boolean? = null
        radio.onStartAdvertising = { activeAtAdvertising = coordinator.state.value.active }
        radio.onStartDiscovery = { activeAtDiscovery = coordinator.state.value.active }

        val owner = controller.acquire()
        controller.start(owner, groupId)
        run()
        assertEquals(RunPhase.STARTING, state.phase)
        assertEquals(1L, state.runId)
        assertEquals(groupId, state.groupId)
        assertEquals(CapabilityState.Stopped, state.advertising)
        assertEquals(0, radio.advertisingTasks.size)
        assertEquals(0, radio.discoveryTasks.size)

        store.pruneGate!!.complete(Unit)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertTrue(coordinator.state.value.active)
        assertEquals(1, radio.advertisingTasks.size)
        assertEquals(1, radio.discoveryTasks.size)
        assertEquals(true, activeAtAdvertising)
        assertEquals(true, activeAtDiscovery)
        assertEquals(CapabilityState.Starting, state.advertising)
        assertEquals(CapabilityState.Starting, state.discovery)
        assertEquals(
            listOf("owner_acquired", "run_start", "coordinator_ready", "advertising_submitted", "discovery_submitted"),
            events()
        )
    }

    @Test
    fun `capabilities stay Starting until their tasks settle and become Running on success`() {
        startActive()
        assertEquals(CapabilityState.Starting, state.advertising)
        assertEquals(CapabilityState.Starting, state.discovery)
        advance(60_000)
        assertEquals(CapabilityState.Starting, state.advertising)
        assertEquals(CapabilityState.Starting, state.discovery)

        radio.pendingAdvertising.complete(RadioOutcome.Success)
        run()
        assertEquals(CapabilityState.Running, state.advertising)
        assertEquals(CapabilityState.Starting, state.discovery)

        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(CapabilityState.Running, state.discovery)
        assertEquals(1, radio.advertisingTasks.size)
        assertEquals(1, radio.discoveryTasks.size)
    }

    @Test
    fun `start with the same group while active is a no-op`() {
        val owner = startRunning()
        controller.start(owner, groupId)
        run()
        assertEquals(1L, state.runId)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(1, radio.advertisingTasks.size)
        assertEquals(1, events().count { it == "run_start" })
    }

    @Test
    fun `start with the same group while active resubmits only the failed capability with a fresh retry budget`() {
        val owner = startActive()
        radio.pendingAdvertising.complete(permission())
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertTrue(state.advertising is CapabilityState.Failed)

        controller.start(owner, groupId)
        run()

        assertEquals(1L, state.runId)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(2, radio.advertisingTasks.size)
        assertEquals(1, radio.discoveryTasks.size)
        assertEquals(CapabilityState.Starting, state.advertising)
        assertEquals(CapabilityState.Running, state.discovery)
        // The resubmission is a first attempt again: a transient failure schedules the 2 s retry, not Failed.
        radio.pendingAdvertising.complete(radioFailure())
        run()
        assertEquals(CapabilityState.Retrying(1, radioFailure()), state.advertising)
        advance(2_000L)
        assertEquals(3, radio.advertisingTasks.size)
        radio.pendingAdvertising.complete(RadioOutcome.Success)
        run()
        assertEquals(CapabilityState.Running, state.advertising)
    }

    @Test
    fun `start with another group while active stops the run and begins the new group`() {
        val owner = startRunning()
        controller.start(owner, otherGroupId)
        run()
        assertEquals(2L, state.runId)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(otherGroupId, state.groupId)
        assertEquals(otherGroupId, coordinator.state.value.groupId)
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertEquals(2, radio.advertisingTasks.size)
    }

    @Test
    fun `coordinator activation failure ends the run as FAILED without touching the radios`() {
        val failing = mockk<NearbySessionCoordinator>()
        coEvery { failing.activate(any()) } throws IllegalStateException("no identity")
        coEvery { failing.deactivate() } returns Unit
        every { failing.state } returns MutableStateFlow(NearbySessionsState())
        val other = NearbySessionController(radio, failing, scope, { scheduler.currentTime }, { 0.0 })
        val owner = other.acquire()
        other.start(owner, groupId)
        run()
        assertEquals(RunPhase.FAILED, other.state.value.phase)
        assertEquals(RunFailure.Coordinator("no identity"), other.state.value.failure)
        assertEquals(0, radio.advertisingTasks.size)
        assertEquals(0, radio.discoveryTasks.size)
        assertTrue(radio.calls.isEmpty())
        assertTrue(other.timeline.value.map { it.event }.contains("coordinator_failed"))

        other.start(owner, groupId)
        run()
        assertEquals(2L, other.state.value.runId)
    }

    // ------------------------------------------------------------------ capability outcomes

    @Test
    fun `permission failure is terminal for the capability and is never resubmitted`() {
        startActive()
        val failure = permission()
        radio.pendingAdvertising.complete(failure)
        run()
        assertEquals(CapabilityState.Failed(failure), state.advertising)
        advance(120_000)
        assertEquals(1, radio.advertisingTasks.size)
        assertEquals(CapabilityState.Failed(failure), state.advertising)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(1, events().count { it == "advertising_failed" })
    }

    @Test
    fun `radio failure retries at 2s 5s and 10s and fails once the retry schedule is exhausted`() {
        startActive()
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        val failure = radioFailure()

        radio.pendingAdvertising.complete(failure)
        run()
        assertEquals(CapabilityState.Retrying(1, failure), state.advertising)
        advance(1_999)
        assertEquals(1, radio.advertisingTasks.size)
        advance(1)
        assertEquals(2, radio.advertisingTasks.size)
        assertEquals(CapabilityState.Starting, state.advertising)

        radio.pendingAdvertising.complete(failure)
        run()
        assertEquals(CapabilityState.Retrying(2, failure), state.advertising)
        advance(4_999)
        assertEquals(2, radio.advertisingTasks.size)
        advance(1)
        assertEquals(3, radio.advertisingTasks.size)

        radio.pendingAdvertising.complete(failure)
        run()
        assertEquals(CapabilityState.Retrying(3, failure), state.advertising)
        advance(9_999)
        assertEquals(3, radio.advertisingTasks.size)
        advance(1)
        assertEquals(4, radio.advertisingTasks.size)

        radio.pendingAdvertising.complete(failure)
        run()
        assertEquals(CapabilityState.Failed(failure), state.advertising)
        advance(120_000)
        assertEquals(4, radio.advertisingTasks.size)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(3, events().count { it == "advertising_retry" })
        assertEquals(1, events().count { it == "advertising_failed" })
    }

    @Test
    fun `retry jitter follows the injected random source`() {
        val jittered = NearbySessionController(radio, coordinator, scope, { scheduler.currentTime }, { 1.0 })
        val owner = jittered.acquire()
        jittered.start(owner, groupId)
        run()
        radio.pendingDiscovery.complete(radioFailure())
        run()
        assertTrue(jittered.state.value.discovery is CapabilityState.Retrying)
        advance(2_499)
        assertEquals(1, radio.discoveryTasks.size)
        advance(1)
        assertEquals(2, radio.discoveryTasks.size)
    }

    @Test
    fun `cancelled start task is treated as a transient failure and retried`() {
        startActive()
        radio.pendingDiscovery.complete(RadioOutcome.Cancelled)
        run()
        val retrying = state.discovery
        assertTrue(retrying is CapabilityState.Retrying && retrying.attempt == 1)
        advance(2_000)
        assertEquals(2, radio.discoveryTasks.size)
    }

    @Test
    fun `already active failure counts as Running`() {
        startActive()
        radio.pendingAdvertising.complete(RadioOutcome.Failure(RadioFailureKind.ALREADY_ACTIVE, 8001, "already"))
        run()
        assertEquals(CapabilityState.Running, state.advertising)
        assertEquals(1, radio.advertisingTasks.size)
    }

    @Test
    fun `both capabilities failed ends the run with NoCapability and stops radios in order`() {
        val owner = startActive()
        radio.pendingAdvertising.complete(permission(8034))
        radio.pendingDiscovery.complete(permission(8036))
        run()
        assertEquals(RunPhase.FAILED, state.phase)
        assertEquals(RunFailure.NoCapability, state.failure)
        assertTrue(state.advertising is CapabilityState.Failed)
        assertTrue(state.discovery is CapabilityState.Failed)
        assertTrue(state.peers.isEmpty())
        assertFalse(coordinator.state.value.active)
        assertOrdered(
            radio.stopDiscoveryCalls.first(),
            radio.stopAdvertisingCalls.first(),
            radio.stopAllEndpointsCalls.first()
        )
        // The final owner-controlled stop repeats every call once the run's submissions have settled.
        assertEquals(2, radio.stopDiscoveryCalls.size)
        assertEquals(2, radio.stopAdvertisingCalls.size)
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertFalse(radio.advertisingOn || radio.discoveringOn)
        assertTrue(events().contains("run_failed"))

        controller.start(owner, groupId)
        run()
        assertEquals(2L, state.runId)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertNull(state.failure)
        assertEquals(CapabilityState.Starting, state.advertising)
    }

    @Test
    fun `one Running and one Failed capability keeps the run ACTIVE`() {
        startActive()
        radio.pendingAdvertising.complete(permission())
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertTrue(state.advertising is CapabilityState.Failed)
        assertEquals(CapabilityState.Running, state.discovery)
        assertNull(state.failure)
        assertTrue(coordinator.state.value.active)
        assertTrue(radio.stopAllEndpointsCalls.isEmpty())
    }

    // ------------------------------------------------------------------ stop and restart

    @Test
    fun `a start submission that reaches the platform after stop is undone by the final stop before IDLE`() {
        // The submission coroutines have passed the retirement check but are held before the platform call.
        val gate = CompletableDeferred<Unit>()
        radio.preSubmitGate = gate
        val owner = controller.acquire()
        controller.start(owner, groupId)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertTrue(radio.advertisingTasks.isEmpty())

        controller.stop(owner)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        assertEquals(1, radio.stopAdvertisingCalls.size)
        assertFalse(radio.advertisingOn)

        // The delayed submissions now reach the platform and turn the radios on after the first stop.
        gate.complete(Unit)
        run()
        assertEquals(1, radio.advertisingTasks.size)
        assertTrue(radio.advertisingOn && radio.discoveringOn)
        assertEquals(RunPhase.STOPPING, state.phase)

        // Once they settle, the final owner-controlled stop turns them off again before the boundary.
        radio.pendingAdvertising.complete(RadioOutcome.Success)
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertFalse(radio.advertisingOn || radio.discoveringOn)
        assertEquals(2, radio.stopAdvertisingCalls.size)
        assertEquals(2, radio.stopDiscoveryCalls.size)
        assertEquals(CapabilityState.Stopped, state.advertising)
    }

    @Test
    fun `a submission already past the retirement check is still undone by the final stop`() {
        // The fake turns the radio on at submission; the outcome settles only after stop has run its first stops.
        val owner = startActive()
        assertTrue(radio.advertisingOn && radio.discoveringOn)
        controller.stop(owner)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        assertFalse(radio.advertisingOn || radio.discoveringOn)

        // This fake changes radio flags at submission, not completion; assert the final stop calls separately.
        radio.pendingAdvertising.complete(RadioOutcome.Success)
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertEquals(2, radio.stopAdvertisingCalls.size)
        assertEquals(2, radio.stopDiscoveryCalls.size)
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertTrue(radio.stopAdvertisingCalls.last().sequence > radio.stopAllEndpointsCalls.first().sequence)
        assertFalse(radio.advertisingOn || radio.discoveringOn)
    }

    @Test
    fun `a connected peer whose link ends locally is connectable again while a pending attempt keeps its outcome`() {
        val owner = startRunning()
        found("ep1")
        connected("ep1")
        assertTrue(attempt("ep1") is AttemptState.Connected)
        // The transport announces the end of the link, whichever side requested it.
        val oldConnection = radio.connection("ep1")
        radio.emit(BleEvent.Disconnected(oldConnection))
        run()
        assertEquals(AttemptState.None, attempt("ep1"))
        controller.connect(owner, "ep1")
        run()
        assertEquals(1, radio.requestCount("ep1"))

        // A timed-out attempt: the disconnect it issues must not erase the timeout the row reports.
        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        advance(NearbySessionController.ATTEMPT_TIMEOUT_MS)
        val failed = attempt("ep1") as AttemptState.Failed
        assertTrue(failed.timedOut)
        assertEquals("ep1", radio.disconnectCalls.single().endpointId)
        radio.emit(BleEvent.Disconnected(oldConnection))
        run()
        assertEquals(failed, attempt("ep1"))
    }

    @Test
    fun `stop during pending startup never reports Running and records the late outcome as stale`() {
        val owner = startActive()
        controller.stop(owner)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        assertFalse(coordinator.state.value.active)
        assertEquals(1, radio.stopAdvertisingCalls.size)
        assertEquals(1, radio.stopDiscoveryCalls.size)
        assertEquals(1, radio.stopAllEndpointsCalls.size)

        radio.pendingAdvertising.complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        assertNotEquals(CapabilityState.Running, state.advertising)
        assertTrue(controller.timeline.value.any { it.event == "ignored_stale_run" && it.runId == 1L })

        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertEquals(CapabilityState.Stopped, state.advertising)
        assertEquals(CapabilityState.Stopped, state.discovery)
        assertNull(state.groupId)
        assertEquals(2, events().count { it == "ignored_stale_run" })
        // Once every submission has settled the radios are stopped a final time.
        assertEquals(2, radio.stopAdvertisingCalls.size)
        assertEquals(2, radio.stopDiscoveryCalls.size)
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertFalse(radio.advertisingOn || radio.discoveringOn)
        assertEquals(1, events().count { it == "cleanup_complete" })
    }

    @Test
    fun `start queued behind an unfinished stop waits for cleanup and then begins a new run`() {
        val owner = startActive()
        controller.stop(owner)
        controller.start(owner, groupId)
        run()
        assertEquals(RunPhase.WAITING_FOR_CLEANUP, state.phase)
        assertEquals(1L, state.runId)
        assertEquals(1, radio.advertisingTasks.size)

        radio.advertisingTasks[0].complete(RadioOutcome.Success)
        radio.discoveryTasks[0].complete(RadioOutcome.Success)
        run()
        assertEquals(2L, state.runId)
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(groupId, state.groupId)
        assertTrue(coordinator.state.value.active)
        assertEquals(CapabilityState.Starting, state.advertising)
        assertEquals(CapabilityState.Starting, state.discovery)
        assertEquals(2, radio.advertisingTasks.size)
        assertEquals(2, radio.discoveryTasks.size)
        assertEquals(2, controller.timeline.value.count { it.event == "ignored_stale_run" && it.runId == 1L })
        assertEquals(2, events().count { it == "run_start" })

        radio.pendingAdvertising.complete(RadioOutcome.Success)
        run()
        assertEquals(CapabilityState.Running, state.advertising)
    }

    @Test
    fun `stop while waiting for cleanup drops the remembered start`() {
        val owner = startActive()
        controller.stop(owner)
        controller.start(owner, groupId)
        run()
        assertEquals(RunPhase.WAITING_FOR_CLEANUP, state.phase)
        controller.stop(owner)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        radio.advertisingTasks[0].complete(RadioOutcome.Success)
        radio.discoveryTasks[0].complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertEquals(1L, state.runId)
        assertEquals(1, radio.advertisingTasks.size)
    }

    @Test
    fun `stop turns search and discoverability off first, closes sessions over live endpoints, then closes them`() {
        val owner = startRunning()
        found("ep1")
        connected("ep1")
        val helloFrames = radio.sendPayloadCalls.size
        assertTrue("the coordinator opens a session on connection", helloFrames > 0)
        assertEquals(PeerPhase.AUTHENTICATING, coordinator.state.value.peers["ep1"]?.phase)

        controller.stop(owner)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertTrue("a close frame is sent during deactivate", radio.sendPayloadCalls.size > helloFrames)
        assertEquals("ep1", radio.sendPayloadCalls.last().endpointId)
        assertOrdered(
            radio.stopDiscoveryCalls.first(),
            radio.stopAdvertisingCalls.first(),
            radio.sendPayloadCalls.last(),
            radio.disconnectCalls.single(),
            radio.stopAllEndpointsCalls.first(),
            radio.stopDiscoveryCalls.last(),
            radio.stopAdvertisingCalls.last(),
            radio.stopAllEndpointsCalls.last()
        )
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertFalse(coordinator.state.value.active)
        assertTrue(state.peers.isEmpty())
        assertEquals(CapabilityState.Stopped, state.advertising)
        assertEquals(CapabilityState.Stopped, state.discovery)
        assertFalse(state.searchingLong)
        assertNull(state.failure)
        assertEquals(listOf("stop_requested", "cleanup_complete"), events().takeLast(2))
    }

    @Test
    fun `a late request outcome after stop is recorded as stale and changes nothing`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        controller.stop(owner)
        run()
        assertEquals(RunPhase.STOPPING, state.phase)
        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertTrue(state.peers.isEmpty())
        assertTrue(controller.timeline.value.any { it.event == "ignored_stale_run" && it.endpointId == "ep1" })
    }

    // ------------------------------------------------------------------ ownership

    @Test
    fun `a stale owner cannot release or start over a newer owner`() {
        val old = controller.acquire()
        run()
        val current = controller.acquire()
        controller.start(current, groupId)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        radio.pendingAdvertising.complete(RadioOutcome.Success)
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()

        controller.release(old)
        controller.start(old, otherGroupId)
        controller.stop(old)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        assertEquals(groupId, state.groupId)
        assertEquals(CapabilityState.Running, state.advertising)
        assertTrue(coordinator.state.value.active)
        assertEquals(3, events().count { it == "ignored_stale_owner" })

        controller.stop(current)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
    }

    @Test
    fun `acquire while a run exists stops that run`() {
        val old = startRunning()
        controller.acquire()
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertFalse(coordinator.state.value.active)
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertTrue(events().contains("owner_acquired"))

        controller.start(old, groupId)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertTrue(events().contains("ignored_stale_owner"))
    }

    @Test
    fun `release by the current owner stops the run and clears ownership`() {
        val owner = startRunning()
        controller.release(owner)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertFalse(coordinator.state.value.active)
        assertTrue(events().contains("owner_released"))
        controller.start(owner, groupId)
        run()
        assertEquals(RunPhase.IDLE, state.phase)
        assertTrue(events().contains("ignored_stale_owner"))
    }

    // ------------------------------------------------------------------ attempts

    @Test
    fun `connect moves through Requesting and AwaitingConnection to Connected and submits one request`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        controller.connect(owner, "ep1")
        run()
        assertEquals(AttemptState.Requesting(1), attempt("ep1"))
        assertEquals(1, radio.requestCount("ep1"))

        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        assertEquals(AttemptState.AwaitingConnection(1), attempt("ep1"))
        controller.connect(owner, "ep1")
        run()
        assertEquals(1, radio.requestCount("ep1"))

        connected("ep1")
        assertEquals(AttemptState.Connected(1, incoming = false), attempt("ep1"))
        assertTrue(state.peers.getValue("ep1").discovered)
        controller.connect(owner, "ep1")
        run()
        assertEquals(1, radio.requestCount("ep1"))
        assertEquals(
            listOf(
                "connect_requested",
                "connect_ignored",
                "connect_submitted",
                "connect_ignored",
                "connected",
                "connect_ignored"
            ),
            events().filter { it.startsWith("connect") }
        )
    }

    @Test
    fun `request failure marks the attempt Failed and a new connect submits a new attempt`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        val failure = RadioOutcome.Failure(RadioFailureKind.ENDPOINT, 8012, "unknown endpoint")
        radio.pendingRequest("ep1").complete(failure)
        run()
        assertEquals(AttemptState.Failed(1, failure), attempt("ep1"))
        assertTrue(state.peers.getValue("ep1").discovered)

        controller.connect(owner, "ep1")
        run()
        assertEquals(AttemptState.Requesting(2), attempt("ep1"))
        assertEquals(2, radio.requestCount("ep1"))
        assertTrue(controller.timeline.value.any { it.event == "connect_failed" && it.statusCode == 8012 })
    }

    @Test
    fun `connection failed event marks the attempt Failed with the reported kind and code`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        radio.emit(BleEvent.ConnectionFailed("ep1", RadioFailureKind.ENDPOINT, 8004, "rejected"))
        run()
        val expected = AttemptState.Failed(1, RadioOutcome.Failure(RadioFailureKind.ENDPOINT, 8004, "rejected"))
        assertEquals(expected, attempt("ep1"))
        assertTrue(controller.timeline.value.any { it.event == "connection_failed" && it.statusCode == 8004 })
    }

    @Test
    fun `connection failed for an undiscovered endpoint removes the row`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        radio.emit(BleEvent.PeerLost("ep1"))
        run()
        assertEquals(false, state.peers["ep1"]?.discovered)
        radio.emit(BleEvent.ConnectionFailed("ep1", RadioFailureKind.ENDPOINT, 8004, "rejected"))
        run()
        assertNull(state.peers["ep1"])
    }

    @Test
    fun `attempt times out after the unpaused timeout and disconnects the endpoint`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        advance(NearbySessionController.ATTEMPT_TIMEOUT_MS - 1)
        assertEquals(AttemptState.AwaitingConnection(1), attempt("ep1"))
        assertTrue(radio.disconnectCalls.isEmpty())
        advance(1)
        assertEquals(AttemptState.Failed(1, null, timedOut = true), attempt("ep1"))
        assertEquals("ep1", radio.disconnectCalls.single().endpointId)
        assertTrue(events().contains("connect_timeout"))
        assertTrue(state.peers.getValue("ep1").discovered)
    }

    @Test
    fun `foreground pause suspends the attempt timeout for exactly the paused duration`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        advance(10_000)
        controller.setForegroundPaused(owner, true)
        run()
        advance(20_000)
        assertEquals(AttemptState.AwaitingConnection(1), attempt("ep1"))
        controller.setForegroundPaused(owner, false)
        run()
        advance(19_999)
        assertEquals(AttemptState.AwaitingConnection(1), attempt("ep1"))
        advance(1)
        assertEquals(AttemptState.Failed(1, null, timedOut = true), attempt("ep1"))
        assertEquals(50_000L, scheduler.currentTime)
    }

    @Test
    fun `a connection before the timeout cancels the timer`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        radio.pendingRequest("ep1").complete(RadioOutcome.Success)
        run()
        connected("ep1")
        advance(NearbySessionController.ATTEMPT_TIMEOUT_MS)
        assertEquals(AttemptState.Connected(1, incoming = false), attempt("ep1"))
        assertFalse(events().contains("connect_timeout"))
    }

    @Test
    fun `cancel before request coroutine runs revokes submission and allows a fresh attempt`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        controller.cancelAttempt(owner, "ep1")
        run()
        assertEquals(AttemptState.None, attempt("ep1"))
        assertEquals(0, radio.requestCount("ep1"))
        assertTrue(radio.disconnectCalls.isEmpty())
        advance(NearbySessionController.ATTEMPT_TIMEOUT_MS)
        assertFalse(events().contains("connect_timeout"))

        controller.connect(owner, "ep1")
        run()
        assertEquals(1, radio.requestCount("ep1"))
        radio.pendingRequest.complete(RadioOutcome.Success)
        run()
        assertEquals(AttemptState.AwaitingConnection(2), attempt("ep1"))
    }

    @Test
    fun `cancelAttempt disconnects, clears the attempt and allows a new connect`() {
        val owner = startRunning()
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        controller.cancelAttempt(owner, "ep1")
        run()
        assertEquals(AttemptState.None, attempt("ep1"))
        assertEquals("ep1", radio.disconnectCalls.single().endpointId)
        assertTrue(events().contains("connect_cancelled"))

        controller.connect(owner, "ep1")
        run()
        assertEquals(AttemptState.Requesting(2), attempt("ep1"))
        assertEquals(2, radio.requestCount("ep1"))
        radio.requestTasks[0].second.complete(RadioOutcome.Success)
        run()
        assertEquals("the cancelled request's late outcome is ignored", AttemptState.Requesting(2), attempt("ep1"))

        controller.cancelAttempt(owner, "ep1")
        controller.cancelAttempt(owner, "ep1")
        run()
        assertEquals(AttemptState.None, attempt("ep1"))
        assertEquals(2, radio.disconnectCalls.size)
    }

    @Test
    fun `incoming connection for an unknown endpoint creates a row that Disconnected removes`() {
        startRunning()
        connected("in1", incoming = true, name = "guest")
        val row = state.peers.getValue("in1")
        assertEquals("guest", row.name)
        assertFalse(row.discovered)
        assertEquals(AttemptState.Connected(null, incoming = true), row.attempt)

        radio.emit(BleEvent.Disconnected(radio.connection("in1")))
        run()
        assertNull(state.peers["in1"])
        assertTrue(events().contains("disconnected"))
    }

    @Test
    fun `PeerLost keeps a connected row but removes an idle one`() {
        startRunning()
        found("a")
        found("b")
        connected("a")
        radio.emit(BleEvent.PeerLost("a"))
        radio.emit(BleEvent.PeerLost("b"))
        run()
        val a = state.peers.getValue("a")
        assertFalse(a.discovered)
        assertEquals(AttemptState.Connected(null, incoming = false), a.attempt)
        assertNull(state.peers["b"])

        radio.emit(BleEvent.Disconnected(radio.connection("a")))
        run()
        assertNull(state.peers["a"])
    }

    @Test
    fun `PeerLost removes a row whose attempt failed and PeerFound keeps an existing attempt`() {
        val owner = startRunning()
        found("a")
        controller.connect(owner, "a")
        run()
        radio.pendingRequest("a").complete(RadioOutcome.Failure(RadioFailureKind.ENDPOINT, 8012, "unknown"))
        run()
        assertTrue(attempt("a") is AttemptState.Failed)
        found("a", name = "renamed")
        assertTrue(attempt("a") is AttemptState.Failed)
        assertEquals("renamed", state.peers.getValue("a").name)
        radio.emit(BleEvent.PeerLost("a"))
        run()
        assertNull(state.peers["a"])
    }

    @Test
    fun `connection failure for one peer leaves another peer's connection intact`() {
        val owner = startRunning()
        found("a")
        found("b")
        controller.connect(owner, "a")
        run()
        radio.pendingRequest("a").complete(RadioOutcome.Success)
        run()
        connected("b")
        radio.emit(BleEvent.ConnectionFailed("a", RadioFailureKind.ENDPOINT, 8004, "rejected"))
        run()
        assertTrue(attempt("a") is AttemptState.Failed)
        assertEquals(AttemptState.Connected(null, incoming = false), attempt("b"))
        assertEquals(2, state.peers.size)
    }

    // ------------------------------------------------------------------ faults

    @Test
    fun `transport fault fails the run, deactivates the coordinator and clears the fault`() {
        startRunning()
        found("ep1")
        val fault = TransportFault("event_overflow", "PeerFound", 1)
        radio.fault.value = fault
        run()
        assertEquals(RunPhase.FAILED, state.phase)
        assertEquals(RunFailure.Transport(fault), state.failure)
        assertTrue(state.peers.isEmpty())
        assertEquals(CapabilityState.Stopped, state.advertising)
        assertFalse(coordinator.state.value.active)
        assertEquals(2, radio.stopAllEndpointsCalls.size)
        assertNull(radio.fault.value)
        assertTrue(events().contains("transport_fault"))
    }

    // ------------------------------------------------------------------ long search

    @Test
    fun `searchingLong is set after the long search window only while discovery runs without peers`() {
        startActive()
        advance(NearbySessionController.LONG_SEARCH_MS)
        assertFalse(state.searchingLong)

        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        advance(NearbySessionController.LONG_SEARCH_MS - 1)
        assertFalse(state.searchingLong)
        advance(1)
        assertTrue(state.searchingLong)

        found("ep1")
        assertFalse(state.searchingLong)
        advance(NearbySessionController.LONG_SEARCH_MS)
        assertFalse(state.searchingLong)

        radio.emit(BleEvent.PeerLost("ep1"))
        run()
        advance(NearbySessionController.LONG_SEARCH_MS - 1)
        assertFalse(state.searchingLong)
        advance(1)
        assertTrue(state.searchingLong)
    }

    @Test
    fun `searchingLong is cleared when the run stops`() {
        val owner = startRunning()
        advance(NearbySessionController.LONG_SEARCH_MS)
        assertTrue(state.searchingLong)
        controller.stop(owner)
        run()
        assertFalse(state.searchingLong)
    }

    // ------------------------------------------------------------------ refused connects

    @Test
    fun `connect is ignored outside ACTIVE, for undiscovered rows and for connected rows`() {
        val owner = controller.acquire()
        controller.connect(owner, "ep1")
        run()
        assertEquals(1, events().count { it == "connect_ignored" })

        controller.start(owner, groupId)
        run()
        radio.pendingAdvertising.complete(RadioOutcome.Success)
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        run()
        assertEquals(RunPhase.ACTIVE, state.phase)
        controller.connect(owner, "unknown")
        run()
        assertEquals(2, events().count { it == "connect_ignored" })

        connected("in1", incoming = true)
        controller.connect(owner, "in1")
        run()
        assertEquals(3, events().count { it == "connect_ignored" })

        found("ep2")
        connected("ep2")
        controller.connect(owner, "ep2")
        run()
        assertEquals(4, events().count { it == "connect_ignored" })
        assertTrue(radio.requestTasks.isEmpty())
        assertEquals(AttemptState.Connected(null, incoming = false), attempt("ep2"))
    }

    @Test
    fun `cancelAttempt without a live attempt is ignored`() {
        val owner = startRunning()
        found("ep1")
        controller.cancelAttempt(owner, "ep1")
        controller.cancelAttempt(owner, "missing")
        run()
        assertTrue(radio.disconnectCalls.isEmpty())
        assertEquals(AttemptState.None, attempt("ep1"))
    }

    // ------------------------------------------------------------------ timeline

    // Discovery names must not populate event/endpoint fields; exception detail redaction is not covered here.
    @Test
    fun `timeline is capped and never contains a peer name`() {
        startRunning()
        repeat(150) { index ->
            radio.emit(BleEvent.PeerFound(NearbyPeer("ep$index", "Secret Name $index")))
            radio.emit(BleEvent.PeerLost("ep$index"))
        }
        run()
        val timeline = controller.timeline.value
        assertEquals(NearbySessionController.TIMELINE_CAPACITY, timeline.size)
        assertEquals("peer_lost", timeline.last().event)
        assertEquals("ep149", timeline.last().endpointId)
        assertTrue(timeline.none { it.event.contains("Secret") || it.endpointId?.contains("Secret") == true })
        assertTrue(timeline.all { it.runId == 1L })
    }

    @Test
    fun `timeline entries carry the clock time and attempt ids`() {
        val owner = startRunning()
        advance(1_234)
        found("ep1")
        controller.connect(owner, "ep1")
        run()
        val entry = controller.timeline.value.last { it.event == "connect_requested" }
        assertEquals(1_234L, entry.atMs)
        assertEquals("ep1", entry.endpointId)
        assertEquals(1L, entry.attemptId)
        assertEquals(1L, entry.runId)
    }
}
