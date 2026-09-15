package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.splitfree.R
import com.splitfree.sync.nearby.AttemptState
import com.splitfree.sync.nearby.CapabilityState
import com.splitfree.sync.nearby.NearbyOwner
import com.splitfree.sync.nearby.NearbyPeerState
import com.splitfree.sync.nearby.NearbyRunState
import com.splitfree.sync.nearby.NearbySessionController
import com.splitfree.sync.nearby.NearbySessionCoordinator
import com.splitfree.sync.nearby.NearbySessionsState
import com.splitfree.sync.nearby.PeerPhase
import com.splitfree.sync.nearby.PeerProgress
import com.splitfree.sync.nearby.RadioFailureKind
import com.splitfree.sync.nearby.RadioOutcome
import com.splitfree.sync.nearby.RunFailure
import com.splitfree.sync.nearby.RunPhase
import com.splitfree.sync.nearby.TransferStats
import com.splitfree.sync.nearby.TransportFault
import com.splitfree.ui.util.UiMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Checks controller-call forwarding and projection of supplied states, not execution of controller commands. */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySyncViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val owner = NearbyOwner(7)
    private val runState = MutableStateFlow(NearbyRunState())
    private val sessions = MutableStateFlow(NearbySessionsState())
    private val controller = mockk<NearbySessionController>(relaxed = true)
    private val coordinator = mockk<NearbySessionCoordinator>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        every { controller.acquire() } returns owner
        every { controller.state } returns runState
        every { coordinator.state } returns sessions
    }

    @After
    fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(handle: SavedStateHandle = SavedStateHandle(mapOf("groupId" to "g1"))) =
        NearbySyncViewModel(handle, controller, coordinator).also { store.put("nearby", it) }

    /** Supplies lifecycle readiness; a start is forwarded only if the saved intent and group id also allow it. */
    private fun startedModel(handle: SavedStateHandle = SavedStateHandle(mapOf("groupId" to "g1"))) =
        model(handle).also {
            it.onScreenStarted()
            it.onPrerequisites(true)
        }

    // --- Ownership and lifecycle -------------------------------------------------------------------------

    @Test
    fun `acquires ownership in init and releases it when cleared`() = runTest(dispatcher) {
        model()
        verify(exactly = 1) { controller.acquire() }
        verify(exactly = 0) { controller.release(any()) }

        store.clear()
        verify(exactly = 1) { controller.release(owner) }
    }

    @Test
    fun `enabled by default so start and prerequisites together start the run exactly once`() = runTest(dispatcher) {
        val vm = model()
        assertTrue(vm.uiState.value.enabled)
        vm.onScreenStarted()
        verify(exactly = 0) { controller.start(any(), any()) }

        vm.onPrerequisites(true)
        verify(exactly = 1) { controller.start(owner, "g1") }

        vm.onPrerequisites(true)
        verify(exactly = 1) { controller.start(any(), any()) }
    }

    @Test
    fun `prerequisites arriving before the screen starts still start the run once`() = runTest(dispatcher) {
        val vm = model()
        vm.onPrerequisites(true)
        verify(exactly = 0) { controller.start(any(), any()) }
        vm.onScreenStarted()
        verify(exactly = 1) { controller.start(owner, "g1") }
    }

    @Test
    fun `losing a prerequisite stops an existing run`() = runTest(dispatcher) {
        val vm = startedModel()
        runState.value = NearbyRunState(runId = 1, phase = RunPhase.ACTIVE, groupId = "g1")

        vm.onPrerequisites(false)
        verify(exactly = 1) { controller.stop(owner) }
    }

    @Test
    fun `losing a prerequisite stops a queued start while the published run is idle`() = runTest(dispatcher) {
        val vm = startedModel()
        assertEquals(RunPhase.IDLE, runState.value.phase)
        verify(exactly = 1) { controller.start(owner, "g1") }

        vm.onPrerequisites(false)
        verify(exactly = 1) { controller.stop(owner) }

        vm.onPrerequisites(false)
        runCurrent()
        vm.onPrerequisites(false)
        verify(exactly = 1) { controller.stop(owner) }
        assertTrue(vm.uiState.value.enabled)
    }

    @Test
    fun `initial missing prerequisites do not start or stop a run`() = runTest(dispatcher) {
        val vm = model()
        vm.onScreenStarted()
        vm.onPrerequisites(false)
        vm.onPrerequisites(false)
        verify(exactly = 0) { controller.start(any(), any()) }
        verify(exactly = 0) { controller.stop(any()) }
    }

    @Test
    fun `stop persists the intent and stops the controller`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("groupId" to "g1"))
        val vm = startedModel(handle)

        vm.stop()
        runCurrent()

        verify(exactly = 1) { controller.stop(owner) }
        assertEquals(false, handle.get<Boolean>(NearbySyncViewModel.KEY_ENABLED))
        assertFalse(vm.uiState.value.enabled)
    }

    @Test
    fun `screen stop stops the controller but keeps the intent`() = runTest(dispatcher) {
        val vm = startedModel()

        vm.onScreenStopped()
        runCurrent()

        verify(exactly = 1) { controller.stop(owner) }
        assertTrue(vm.uiState.value.enabled)
    }

    @Test
    fun `after an explicit stop a later screen start does not start the run`() = runTest(dispatcher) {
        val vm = startedModel()
        vm.stop()
        vm.onScreenStopped()

        vm.onScreenStarted()
        verify(exactly = 1) { controller.start(any(), any()) }
    }

    @Test
    fun `a restored intent of false is honoured on the first start`() = runTest(dispatcher) {
        startedModel(SavedStateHandle(mapOf("groupId" to "g1", NearbySyncViewModel.KEY_ENABLED to false)))
        verify(exactly = 0) { controller.start(any(), any()) }
    }

    @Test
    fun `start re-enables and starts the run`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("groupId" to "g1"))
        val vm = startedModel(handle)
        vm.stop()

        vm.start()
        runCurrent()

        verify(exactly = 2) { controller.start(owner, "g1") }
        assertEquals(true, handle.get<Boolean>(NearbySyncViewModel.KEY_ENABLED))
        assertTrue(vm.uiState.value.enabled)
    }

    @Test
    fun `start without prerequisites only records the intent`() = runTest(dispatcher) {
        val vm = model()
        vm.onScreenStarted()
        vm.stop()

        vm.start()
        runCurrent()

        verify(exactly = 0) { controller.start(any(), any()) }
        assertTrue(vm.uiState.value.enabled)
    }

    @Test
    fun `pause connect cancel and leave forward to the controller with the owner`() = runTest(dispatcher) {
        val vm = model()

        vm.setPaused(true)
        vm.setPaused(false)
        vm.connect("ep-1")
        vm.cancelAttempt("ep-1")
        vm.leave()

        verify(exactly = 1) { controller.setForegroundPaused(owner, true) }
        verify(exactly = 1) { controller.setForegroundPaused(owner, false) }
        verify(exactly = 1) { controller.connect(owner, "ep-1") }
        verify(exactly = 1) { controller.cancelAttempt(owner, "ep-1") }
        verify(exactly = 1) { controller.stop(owner) }
    }

    // --- Projection: headline and rows -------------------------------------------------------------------

    @Test
    fun `idle run projects the idle headline and no rows`() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        val state = vm.uiState.value
        assertEquals(Headline.IDLE, state.headline)
        assertTrue(state.rows.isEmpty())
        assertNull(state.notice)
        assertFalse(state.progressVisible)
    }

    @Test
    fun `starting and waiting for cleanup project the starting headline`() = runTest(dispatcher) {
        val vm = model()
        runState.value = NearbyRunState(phase = RunPhase.STARTING)
        runCurrent()
        assertEquals(Headline.STARTING, vm.uiState.value.headline)

        runState.value = NearbyRunState(phase = RunPhase.WAITING_FOR_CLEANUP)
        runCurrent()
        assertEquals(Headline.STARTING, vm.uiState.value.headline)
    }

    @Test
    fun `active discovery with nobody found projects searching and the searching hint`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active()
        runCurrent()

        val state = vm.uiState.value
        assertEquals(Headline.SEARCHING, state.headline)
        assertTrue(state.searching)
        assertEquals(UiMessage.Res(R.string.nearby_searching_hint), state.notice?.message)
        assertEquals(NoticeAction.NONE, state.notice?.action)
        assertFalse(state.notice!!.warning)
    }

    @Test
    fun `a long search swaps in the long-search hint`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active().copy(searchingLong = true)
        runCurrent()
        assertEquals(UiMessage.Res(R.string.nearby_search_long), vm.uiState.value.notice?.message)
    }

    @Test
    fun `a discovered peer projects a sync row and the found headline without any hint`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4"))
        runCurrent()

        val state = vm.uiState.value
        assertEquals(Headline.FOUND, state.headline)
        assertEquals(1, state.peerCount)
        assertNull(state.notice)
        val row = state.rows.single()
        assertEquals("ep-1", row.endpointId)
        assertEquals(UiMessage.Raw("a1b2c3d4"), row.displayName)
        assertEquals(RowAction.SYNC, row.action)
        assertNull(row.status)
        assertNull(row.verifiedPubkey)
        assertTrue(row.discovered)
        assertFalse(row.recent)
    }

    @Test
    fun `a discovered peer is not connectable unless the run is active`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4")).copy(phase = RunPhase.STOPPING)
        runCurrent()
        assertEquals(RowAction.NONE, vm.uiState.value.rows.single().action)
    }

    @Test
    fun `requesting and awaiting attempts project connecting rows and the connecting headline`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Requesting(1)))
        runCurrent()
        var row = vm.uiState.value.rows.single()
        assertEquals(RowAction.CONNECTING, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_connecting), row.status)
        assertEquals(Headline.CONNECTING, vm.uiState.value.headline)

        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.AwaitingConnection(1)))
        runCurrent()
        row = vm.uiState.value.rows.single()
        assertEquals(RowAction.CONNECTING, row.action)
        assertEquals(Headline.CONNECTING, vm.uiState.value.headline)
    }

    @Test
    fun `a connected peer without protocol progress is busy and still connecting`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)))
        runCurrent()
        val row = vm.uiState.value.rows.single()
        assertEquals(RowAction.BUSY, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_connecting), row.status)
        assertEquals(Headline.CONNECTING, vm.uiState.value.headline)
        assertFalse(vm.uiState.value.progressVisible)
    }

    @Test
    fun `a connected peer in an active protocol phase is busy and syncing`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)))
        sessions.value =
            sessions(progress("ep-1", PeerPhase.TRANSFERRING, stats = TransferStats(sent = 2, applied = 5)))
        runCurrent()

        val state = vm.uiState.value
        val row = state.rows.single()
        assertEquals(RowAction.BUSY, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_transferring, 5, 2), row.status)
        assertEquals(Headline.SYNCING, state.headline)
        assertTrue(state.progressVisible)
    }

    @Test
    fun `a connected peer with a result is done and shows it without offering sync`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)))
        sessions.value =
            sessions(progress("ep-1", PeerPhase.UP_TO_DATE, stats = TransferStats(sent = 1, applied = 4)))
        runCurrent()

        val row = vm.uiState.value.rows.single()
        assertEquals(RowAction.DONE, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_up_to_date, 4, 1), row.status)
        assertEquals(Headline.FOUND, vm.uiState.value.headline)
        assertFalse(vm.uiState.value.progressVisible)

        sessions.value = sessions(progress("ep-1", PeerPhase.WAITING_DEPENDENCY, stats = TransferStats(deferred = 2)))
        runCurrent()
        assertEquals(RowAction.DONE, vm.uiState.value.rows.single().action)

        sessions.value = sessions(progress("ep-1", PeerPhase.INCOMPLETE, stats = TransferStats(rejected = 1)))
        runCurrent()
        assertEquals(RowAction.DONE, vm.uiState.value.rows.single().action)

        sessions.value = sessions(progress("ep-1", PeerPhase.AUTH_FAILED))
        runCurrent()
        assertEquals(RowAction.DONE, vm.uiState.value.rows.single().action)
        assertEquals(UiMessage.Res(R.string.nearby_peer_auth_failed), vm.uiState.value.rows.single().status)
    }

    @Test
    fun `a failed attempt on a discovered peer offers retry with the reason`() = runTest(dispatcher) {
        val vm = model()
        val failure = RadioOutcome.Failure(RadioFailureKind.ENDPOINT, 8012, "rejected")
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Failed(1, failure)))
        runCurrent()
        var row = vm.uiState.value.rows.single()
        assertEquals(RowAction.RETRY, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_connection_failed, "rejected"), row.status)
        assertEquals(Headline.FOUND, vm.uiState.value.headline)

        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Failed(1, null, timedOut = true)))
        runCurrent()
        row = vm.uiState.value.rows.single()
        assertEquals(RowAction.RETRY, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_attempt_timed_out), row.status)

        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Failed(1, null)))
        runCurrent()
        assertEquals(UiMessage.Res(R.string.nearby_connection_failed_unknown), vm.uiState.value.rows.single().status)
    }

    @Test
    fun `a failed attempt on an undiscovered peer offers nothing`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Failed(1, null), discovered = false))
        runCurrent()
        assertEquals(RowAction.NONE, vm.uiState.value.rows.single().action)
    }

    @Test
    fun `an incoming connection never discovered gets the generic name`() = runTest(dispatcher) {
        val vm = model()
        runState.value =
            active(peer("ep-9", null, AttemptState.Connected(null, incoming = true), discovered = false))
        sessions.value = sessions(progress("ep-9", PeerPhase.AUTHENTICATING))
        runCurrent()

        val row = vm.uiState.value.rows.single()
        assertEquals(UiMessage.Res(R.string.nearby_phone), row.displayName)
        assertFalse(row.discovered)
        assertEquals(RowAction.BUSY, row.action)
        assertEquals(UiMessage.Res(R.string.nearby_authenticating), row.status)
        assertEquals(Headline.SYNCING, vm.uiState.value.headline)
    }

    @Test
    fun `identity is verified only once the coordinator reports a pubkey`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)))
        sessions.value = sessions(progress("ep-1", PeerPhase.AUTHENTICATING, pubkey = null))
        runCurrent()
        assertNull(vm.uiState.value.rows.single().verifiedPubkey)

        sessions.value = sessions(progress("ep-1", PeerPhase.OPENING_GROUP, pubkey = "a1b2c3d4e5f6a7b8"))
        runCurrent()
        assertEquals("a1b2c3d4e5f6a7b8", vm.uiState.value.rows.single().verifiedPubkey)
    }

    @Test
    fun `a verified identity is busy while the group is checked and stays verified when the group refuses it`() =
        runTest(dispatcher) {
            val vm = model()
            runState.value = active(peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)))

            // Identity verified, group authorization still pending: verified but busy, never done.
            sessions.value = sessions(progress("ep-1", PeerPhase.OPENING_GROUP, pubkey = "a1b2c3d4e5f6a7b8"))
            runCurrent()
            var row = vm.uiState.value.rows.single()
            assertEquals("a1b2c3d4e5f6a7b8", row.verifiedPubkey)
            assertEquals(RowAction.BUSY, row.action)
            assertEquals(UiMessage.Res(R.string.nearby_opening_group), row.status)
            assertEquals(Headline.SYNCING, vm.uiState.value.headline)
            assertTrue(vm.uiState.value.progressVisible)

            // Group authorization refused: the terminal result is shown, and the verified identity is kept because
            // the key was authenticated even though membership was not granted.
            sessions.value =
                sessions(
                    progress("ep-1", PeerPhase.UNAUTHORIZED, pubkey = "a1b2c3d4e5f6a7b8", closeReason = "unauthorized")
                )
            runCurrent()
            row = vm.uiState.value.rows.single()
            assertEquals(RowAction.DONE, row.action)
            assertEquals("a1b2c3d4e5f6a7b8", row.verifiedPubkey)
            assertEquals(UiMessage.Res(R.string.nearby_unauthorized), row.status)
            assertEquals(Headline.FOUND, vm.uiState.value.headline)
            assertFalse(vm.uiState.value.progressVisible)
        }

    @Test
    fun `one peer up to date never hides another transferring`() = runTest(dispatcher) {
        val vm = model()
        runState.value =
            active(
                peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)),
                peer("ep-2", "e5f6a7b8", AttemptState.Connected(2, incoming = false))
            )
        sessions.value =
            sessions(progress("ep-1", PeerPhase.UP_TO_DATE), progress("ep-2", PeerPhase.TRANSFERRING))
        runCurrent()

        val state = vm.uiState.value
        assertEquals(Headline.SYNCING, state.headline)
        assertTrue(state.progressVisible)
        assertEquals(listOf(RowAction.DONE, RowAction.BUSY), state.rows.map { it.action })
    }

    @Test
    fun `connecting outranks found and syncing outranks connecting`() = runTest(dispatcher) {
        val vm = model()
        runState.value =
            active(peer("ep-1", "a1b2c3d4"), peer("ep-2", "e5f6a7b8", AttemptState.Requesting(2)))
        runCurrent()
        assertEquals(Headline.CONNECTING, vm.uiState.value.headline)

        runState.value =
            active(
                peer("ep-1", "a1b2c3d4", AttemptState.Connected(1, incoming = false)),
                peer("ep-2", "e5f6a7b8", AttemptState.Requesting(2))
            )
        sessions.value = sessions(progress("ep-1", PeerPhase.COMPARING))
        runCurrent()
        assertEquals(Headline.SYNCING, vm.uiState.value.headline)
    }

    @Test
    fun `a retained result on a discovered peer keeps the sync button and shows the result`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4"))
        sessions.value =
            sessions(
                progress(
                    "ep-1",
                    PeerPhase.INTERRUPTED,
                    stats = TransferStats(applied = 3),
                    closeReason = "peer_disconnected"
                )
            )
        runCurrent()

        val row = vm.uiState.value.rows.single()
        assertEquals(RowAction.SYNC, row.action)
        assertEquals(UiMessage.Plural(R.plurals.nearby_interrupted, 3, 3), row.status)
        assertFalse(row.recent)
    }

    @Test
    fun `terminal results for endpoints absent from the run appear as recent rows without actions`() =
        runTest(dispatcher) {
            val vm = model()
            runState.value = active(peer("ep-2", "e5f6a7b8"))
            sessions.value =
                sessions(
                    progress("ep-1", PeerPhase.CLOSED, pubkey = "a1b2c3d4e5f6a7b8", closeReason = "stopped"),
                    progress("ep-3", PeerPhase.UP_TO_DATE)
                )
            runCurrent()

            val state = vm.uiState.value
            assertEquals(listOf("ep-2", "ep-1"), state.rows.map { it.endpointId })
            val recent = state.rows.last()
            assertTrue(recent.recent)
            assertEquals(RowAction.NONE, recent.action)
            assertEquals(UiMessage.Res(R.string.nearby_phone), recent.displayName)
            assertEquals("a1b2c3d4e5f6a7b8", recent.verifiedPubkey)
            assertEquals(UiMessage.Res(R.string.nearby_sync_complete), recent.status)
            assertEquals(1, state.peerCount)
            assertEquals(Headline.FOUND, state.headline)
        }

    @Test
    fun `recent results survive an idle run and belong only to this group`() = runTest(dispatcher) {
        val vm = model()
        sessions.value = sessions(progress("ep-1", PeerPhase.CLOSED))
        runCurrent()
        assertEquals(1, vm.uiState.value.rows.size)
        assertEquals(Headline.IDLE, vm.uiState.value.headline)

        sessions.value = sessions(progress("ep-1", PeerPhase.CLOSED)).copy(groupId = "other")
        runCurrent()
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    @Test
    fun `peers cleared by the controller shrink the rows`() = runTest(dispatcher) {
        val vm = model()
        runState.value = active(peer("ep-1", "a1b2c3d4"), peer("ep-2", "e5f6a7b8"))
        runCurrent()
        assertEquals(2, vm.uiState.value.rows.size)

        runState.value = active(peer("ep-2", "e5f6a7b8"))
        runCurrent()
        assertEquals(listOf("ep-2"), vm.uiState.value.rows.map { it.endpointId })

        runState.value = NearbyRunState()
        runCurrent()
        assertTrue(vm.uiState.value.rows.isEmpty())
        assertEquals(Headline.IDLE, vm.uiState.value.headline)
    }

    // --- Projection: run-level notice --------------------------------------------------------------------

    @Test
    fun `a failed run projects the failed headline and a retry notice for transport and coordinator failures`() =
        runTest(dispatcher) {
            val vm = model()
            runState.value =
                NearbyRunState(
                    phase = RunPhase.FAILED,
                    failure = RunFailure.Transport(TransportFault("lifecycle", "client disconnected", 1))
                )
            runCurrent()
            var state = vm.uiState.value
            assertEquals(Headline.FAILED, state.headline)
            assertEquals(UiMessage.Res(R.string.nearby_notice_transport, "client disconnected"), state.notice?.message)
            assertEquals(NoticeAction.RETRY, state.notice?.action)
            assertTrue(state.notice!!.warning)

            runState.value = NearbyRunState(phase = RunPhase.FAILED, failure = RunFailure.Coordinator("no identity"))
            runCurrent()
            state = vm.uiState.value
            assertEquals(UiMessage.Res(R.string.nearby_notice_coordinator, "no identity"), state.notice?.message)
            assertEquals(NoticeAction.RETRY, state.notice?.action)
        }

    @Test
    fun `no capability maps the failure kind onto the recovery action`() = runTest(dispatcher) {
        val vm = model()
        fun failed(kind: RadioFailureKind) = NearbyRunState(
            phase = RunPhase.FAILED,
            advertising = CapabilityState.Failed(RadioOutcome.Failure(kind, null, "denied")),
            discovery = CapabilityState.Failed(RadioOutcome.Failure(kind, null, "denied")),
            failure = RunFailure.NoCapability
        )

        runState.value = failed(RadioFailureKind.PERMISSION)
        runCurrent()
        assertEquals(NoticeAction.GRANT_PERMISSION, vm.uiState.value.notice?.action)
        assertEquals(UiMessage.Res(R.string.nearby_notice_permission), vm.uiState.value.notice?.message)

        runState.value = failed(RadioFailureKind.LOCATION_SETTING)
        runCurrent()
        assertEquals(NoticeAction.OPEN_LOCATION_SETTINGS, vm.uiState.value.notice?.action)

        runState.value = failed(RadioFailureKind.SERVICE)
        runCurrent()
        assertEquals(NoticeAction.RETRY, vm.uiState.value.notice?.action)
        assertEquals(UiMessage.Res(R.string.nearby_notice_service), vm.uiState.value.notice?.message)

        runState.value = failed(RadioFailureKind.RADIO)
        runCurrent()
        assertEquals(UiMessage.Res(R.string.nearby_notice_radio, "denied"), vm.uiState.value.notice?.message)
        assertEquals(NoticeAction.RETRY, vm.uiState.value.notice?.action)
    }

    @Test
    fun `a capability failure during an active run outranks the search hint and keeps the run visible`() =
        runTest(dispatcher) {
            val vm = model()
            runState.value =
                active().copy(
                    discovery =
                    CapabilityState.Failed(RadioOutcome.Failure(RadioFailureKind.LOCATION_SETTING, 8025, "off"))
                )
            runCurrent()

            val state = vm.uiState.value
            assertEquals(RunPhase.ACTIVE, state.phase)
            assertEquals(Headline.IDLE, state.headline)
            assertFalse(state.searching)
            assertEquals(NoticeAction.OPEN_LOCATION_SETTINGS, state.notice?.action)
            assertTrue(state.notice!!.warning)
        }

    @Test
    fun `a restarting capability projects the partial-state hint`() = runTest(dispatcher) {
        val vm = model()
        val failure = RadioOutcome.Failure(RadioFailureKind.RADIO, 8007, "bluetooth")
        runState.value = active().copy(discovery = CapabilityState.Retrying(1, failure))
        runCurrent()
        assertEquals(Headline.SEARCHING, vm.uiState.value.headline)
        assertEquals(UiMessage.Res(R.string.nearby_notice_search_restarting), vm.uiState.value.notice?.message)
        assertFalse(vm.uiState.value.notice!!.warning)

        runState.value = active().copy(advertising = CapabilityState.Retrying(1, failure))
        runCurrent()
        assertEquals(UiMessage.Res(R.string.nearby_notice_advertise_restarting), vm.uiState.value.notice?.message)
    }

    @Test
    fun `an active run without a group id in the handle never starts`() = runTest(dispatcher) {
        val vm = model(SavedStateHandle())
        vm.onScreenStarted()
        vm.onPrerequisites(true)
        verify(exactly = 0) { controller.start(any(), any()) }
        assertNotNull(vm.uiState.value)
    }

    // --- Fixtures ----------------------------------------------------------------------------------------

    private fun active(vararg peers: NearbyPeerState) = NearbyRunState(
        runId = 1,
        phase = RunPhase.ACTIVE,
        groupId = "g1",
        advertising = CapabilityState.Running,
        discovery = CapabilityState.Running,
        peers = peers.associateBy { it.endpointId }
    )

    private fun peer(
        endpointId: String,
        name: String?,
        attempt: AttemptState = AttemptState.None,
        discovered: Boolean = true
    ) = NearbyPeerState(endpointId, name, discovered, attempt)

    private fun sessions(vararg peers: PeerProgress) =
        NearbySessionsState(active = true, groupId = "g1", peers = peers.associateBy { it.endpointId })

    private fun progress(
        endpointId: String,
        phase: PeerPhase,
        pubkey: String? = null,
        stats: TransferStats = TransferStats(),
        closeReason: String? = null
    ) = PeerProgress(endpointId, pubkey, phase, "g1", stats, closeReason)
}
