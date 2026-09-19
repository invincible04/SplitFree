package com.splitfree

import android.app.Application
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.usecase.group.JoinGroupCoordinator
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Completed joins remain pending until a navigation callback exists. Exercises outcome handling
 * directly; Activity recreation, Compose and NavController initialization are not exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MainActivityJoinOutcomeTest {
    private val group = Group(
        "g-1",
        "Trip",
        createdBy = "aa".repeat(32),
        createdAt = 1,
        members = listOf("aa".repeat(32)),
        relays = emptyList()
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val coordinator = JoinGroupCoordinator(
        mockk<JoinGroupUseCase> { coEvery { this@mockk.invoke("link") } returns group },
        scope
    )

    /** Skips onCreate/Hilt injection and supplies the coordinator directly. */
    private val activity = Robolectric.buildActivity(MainActivity::class.java).get().apply {
        joinCoordinator = coordinator
    }

    @After
    fun teardown() = scope.cancel()

    @Test
    fun `a joined outcome is left in the coordinator until a NavController exists`() {
        coordinator.join("link")
        val joined = JoinGroupCoordinator.State.Joined(group)
        assertEquals(joined, coordinator.state.value)

        activity.reflectJoinState(joined, navigate = null)

        assertEquals(joined, coordinator.state.value)
    }

    @Test
    fun `once a NavController exists the same outcome navigates and is acknowledged exactly once`() {
        coordinator.join("link")
        val joined = JoinGroupCoordinator.State.Joined(group)
        val routes = mutableListOf<String>()

        activity.reflectJoinState(joined, navigate = null)
        activity.reflectJoinState(joined, navigate = { routes += it })

        assertEquals(listOf(Screen.GroupDetail.withId("g-1")), routes)
        assertEquals(JoinGroupCoordinator.State.Idle, coordinator.state.value)
    }

    @Test
    fun `a failed outcome needs no NavController and is acknowledged at once`() {
        coordinator.join("link")
        val failed = JoinGroupCoordinator.State.Failed("no relay")
        val useCase = mockk<JoinGroupUseCase> {
            coEvery { this@mockk.invoke("bad") } throws
                IllegalStateException("no relay")
        }
        val failing = JoinGroupCoordinator(useCase, scope).also { it.join("bad") }
        assertEquals(failed, failing.state.value)
        activity.joinCoordinator = failing

        activity.reflectJoinState(failed, navigate = null)

        assertEquals(JoinGroupCoordinator.State.Idle, failing.state.value)
    }
}
