package com.splitfree.domain.usecase.group

import com.splitfree.domain.model.group.Group
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coordinator state tests with a controlled scope and mocked join; no Activity recreation is exercised.
 * Terminal outcomes remain available until acknowledged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinGroupCoordinatorTest {
    private val joinGroup = mockk<JoinGroupUseCase>()
    private val group = Group("g1", "Trip", "", "cc".repeat(32), 1, listOf("aa".repeat(32)), emptyList())

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() = unmockkStatic(android.util.Log::class)

    @Test
    fun `join reports Joining, then Joined, and stays Joined until acknowledged`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CompletableDeferred<Group>()
        coEvery { joinGroup("link") } coAnswers { gate.await() }
        val coordinator = JoinGroupCoordinator(joinGroup, appScope)

        coordinator.join("link")
        advanceUntilIdle()
        assertEquals(JoinGroupCoordinator.State.Joining("link"), coordinator.state.value)

        // Completion updates state without an active UI collector.
        gate.complete(group)
        advanceUntilIdle()
        assertEquals(JoinGroupCoordinator.State.Joined(group), coordinator.state.value)

        // Acknowledgement clears the retained result.
        coordinator.acknowledge()
        assertEquals(JoinGroupCoordinator.State.Idle, coordinator.state.value)
    }

    @Test
    fun `a failure is reported and a second join is ignored while one is running`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CompletableDeferred<Group>()
        coEvery { joinGroup("link") } coAnswers { gate.await() }
        val coordinator = JoinGroupCoordinator(joinGroup, appScope)

        coordinator.join("link")
        coordinator.join("other")
        advanceUntilIdle()
        coVerify(exactly = 1) { joinGroup(any()) }

        gate.completeExceptionally(IllegalStateException("no relays"))
        advanceUntilIdle()
        val state = coordinator.state.value
        assertTrue(state is JoinGroupCoordinator.State.Failed)
        assertEquals("no relays", (state as JoinGroupCoordinator.State.Failed).message)

        // Acknowledgement clears the failure so another join can start.
        coordinator.acknowledge()
        assertEquals(JoinGroupCoordinator.State.Idle, coordinator.state.value)
    }

    @Test
    fun `cancellation clears the running state and explicit retry succeeds`() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        coEvery { joinGroup("link") } throws CancellationException("interrupted")
        val coordinator = JoinGroupCoordinator(joinGroup, appScope)
        coordinator.join("link")
        advanceUntilIdle()
        assertTrue(coordinator.state.value is JoinGroupCoordinator.State.Failed)
        coordinator.acknowledge()
        coEvery { joinGroup("link") } returns group
        coordinator.join("link")
        advanceUntilIdle()
        assertEquals(JoinGroupCoordinator.State.Joined(group), coordinator.state.value)
        coVerify(exactly = 2) { joinGroup("link") }
        appScope.cancel()
    }

    @Test
    fun `cancellation before launch starts cannot strand the joining state`() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        appScope.cancel()
        val coordinator = JoinGroupCoordinator(joinGroup, appScope)
        coordinator.join("link")
        advanceUntilIdle()
        assertTrue(coordinator.state.value is JoinGroupCoordinator.State.Failed)
        coVerify(exactly = 0) { joinGroup(any()) }
    }
}
