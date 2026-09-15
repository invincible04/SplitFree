package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.splitfree.sync.nearby.CapabilityState
import com.splitfree.sync.nearby.FakeNearbyRadio
import com.splitfree.sync.nearby.FakeReconciliationStore
import com.splitfree.sync.nearby.NearbySessionController
import com.splitfree.sync.nearby.NearbySessionCoordinator
import com.splitfree.sync.nearby.RadioOutcome
import com.splitfree.sync.nearby.RunPhase
import com.splitfree.sync.nearby.TestIdentity
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises queued lifecycle commands with the real controller/coordinator and a deterministic fake radio. */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbySyncViewModelLifecycleTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val viewModelStore = ViewModelStore()
    private val radio = FakeNearbyRadio()
    private val identity = TestIdentity(1)
    private val coordinator =
        NearbySessionCoordinator(
            transport = radio,
            identity = identity.contract,
            store = FakeReconciliationStore(identity.pub, mutableSetOf(identity.pub), identity.pub),
            appScope = scope,
            clock = { scheduler.currentTime }
        )
    private val controller = NearbySessionController(radio, coordinator, scope, { scheduler.currentTime }, { 0.0 })
    private val groupId = "12345678-1234-1234-1234-123456789abc"

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        try {
            viewModelStore.clear()
            scheduler.runCurrent()
        } finally {
            scope.cancel()
            try {
                scheduler.runCurrent()
            } finally {
                Dispatchers.resetMain()
                unmockkStatic(android.util.Log::class)
            }
        }
    }

    @Test
    fun `prerequisite loss invalidates queued startup idempotently and readiness can start a fresh run`() {
        val vm = NearbySyncViewModel(SavedStateHandle(mapOf("groupId" to groupId)), controller, coordinator)
        viewModelStore.put("nearby", vm)
        scheduler.runCurrent()

        // Do not drain the scheduler yet: readiness loss must cancel startup before it submits radio work.
        vm.onScreenStarted()
        vm.onPrerequisites(true)
        assertEquals(RunPhase.IDLE, controller.state.value.phase)

        vm.onPrerequisites(false)
        vm.onPrerequisites(false)
        scheduler.runCurrent()

        val stoppedState = controller.state.value
        assertEquals(RunPhase.IDLE, stoppedState.phase)
        assertFalse(coordinator.state.value.active)
        assertEquals(0, radio.advertisingTasks.size)
        assertEquals(0, radio.discoveryTasks.size)
        assertFalse(radio.advertisingOn)
        assertFalse(radio.discoveringOn)
        assertEquals(RunPhase.IDLE, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.enabled)
        assertEquals(1, controller.timeline.value.count { it.event == "stop_requested" })

        val stoppedTimeline = controller.timeline.value
        val stoppedCalls = radio.calls.toList()
        vm.onPrerequisites(false)
        vm.onPrerequisites(false)
        scheduler.runCurrent()

        assertEquals(stoppedState, controller.state.value)
        assertEquals(stoppedTimeline, controller.timeline.value)
        assertEquals(stoppedCalls, radio.calls)
        assertFalse(coordinator.state.value.active)
        assertEquals(0, radio.advertisingTasks.size)
        assertEquals(0, radio.discoveryTasks.size)

        vm.onPrerequisites(true)
        scheduler.runCurrent()

        assertEquals(RunPhase.ACTIVE, controller.state.value.phase)
        assertTrue(controller.state.value.runId > stoppedState.runId)
        assertEquals(groupId, controller.state.value.groupId)
        assertTrue(coordinator.state.value.active)
        assertEquals(1, radio.advertisingTasks.size)
        assertEquals(1, radio.discoveryTasks.size)
        assertTrue(radio.advertisingOn)
        assertTrue(radio.discoveringOn)

        radio.pendingAdvertising.complete(RadioOutcome.Success)
        radio.pendingDiscovery.complete(RadioOutcome.Success)
        scheduler.runCurrent()

        assertEquals(CapabilityState.Running, controller.state.value.advertising)
        assertEquals(CapabilityState.Running, controller.state.value.discovery)
        assertTrue(vm.uiState.value.searching)

        vm.onPrerequisites(true)
        scheduler.runCurrent()
        assertEquals(1, radio.advertisingTasks.size)
        assertEquals(1, radio.discoveryTasks.size)

        viewModelStore.clear()
        scheduler.runCurrent()
        assertEquals(RunPhase.IDLE, controller.state.value.phase)
        assertFalse(coordinator.state.value.active)
        assertFalse(radio.advertisingOn)
        assertFalse(radio.discoveringOn)
        assertEquals(1, controller.timeline.value.count { it.event == "owner_released" })
    }
}
