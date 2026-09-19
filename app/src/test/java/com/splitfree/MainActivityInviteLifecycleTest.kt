package com.splitfree

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.usecase.group.JoinGroupCoordinator
import com.splitfree.domain.usecase.group.JoinGroupUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.PendingInviteViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production invite prompt with a real Activity ViewModelStore; Hilt and full app startup are not used. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MainActivityInviteLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val creator = "ab".repeat(32)
    private val group = Group(
        GroupIdentity.derive(creator, 1),
        "Pending invitation",
        createdBy = creator,
        createdAt = 1,
        members = listOf(creator),
        relays = RelayDefaults.DEFAULT_RELAYS.take(1)
    )
    private val link = InviteLinkCodec.encode(group, Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
    private val useCase = mockk<JoinGroupUseCase>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val coordinator = JoinGroupCoordinator(useCase, scope)
    private val renderer = Robolectric.buildActivity(MainActivity::class.java).get().apply {
        joinCoordinator = coordinator
    }
    private var ready by mutableStateOf(false)
    private var onboarding by mutableStateOf(true)
    private lateinit var invites: PendingInviteViewModel

    @After
    fun teardown() = scope.cancel()

    @Test
    fun `first-run prompt waits for ready identity and onboarding completion across recreation`() {
        installContent(compose.activity)
        compose.runOnIdle { assertTrue(invites.offer(link)) }
        compose.onNodeWithText("Join").assertDoesNotExist()
        val originalActivity = compose.activity
        val originalModel = invites
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::installContent)
        compose.runOnIdle {
            assertNotSame(originalActivity, compose.activity)
            assertSame(originalModel, invites)
            assertEquals(link, invites.pendingLink.value)
            ready = true
        }
        compose.onNodeWithText("Join").assertDoesNotExist()
        compose.runOnIdle { onboarding = false }
        compose.onNodeWithText("Join").assertIsDisplayed()
        coVerify(exactly = 0) { useCase(any()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(invites.pendingLink.value) }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::installContent)
        compose.onNodeWithText("Join").assertDoesNotExist()
    }

    @Test
    fun `join failure and recreation preserve invitation and retry navigates once on the new screen`() {
        ready = true
        onboarding = false
        val completion = CompletableDeferred<Group>()
        coEvery { useCase(link) } coAnswers { completion.await() }
        installContent(compose.activity)
        compose.runOnIdle { invites.offer(link) }
        compose.onNodeWithText("Join").performClick()
        compose.onNodeWithText("Join").assertDoesNotExist()
        compose.runOnIdle { assertEquals(link, invites.pendingLink.value) }
        val original = invites
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::installContent)
        compose.runOnIdle {
            assertSame(original, invites)
            completion.completeExceptionally(IllegalStateException("disk full"))
            renderer.reflectJoinState(coordinator.state.value, navigate = null)
        }
        compose.onNodeWithText("Join").assertIsDisplayed()
        coEvery { useCase(link) } returns group
        compose.onNodeWithText("Join").performClick()
        val routes = mutableListOf<String>()
        compose.runOnIdle {
            val joined = coordinator.state.value
            renderer.reflectJoinState(joined, navigate = null, onJoined = invites::joined)
            assertEquals(link, invites.pendingLink.value)
            renderer.reflectJoinState(joined, navigate = { routes += it }, onJoined = invites::joined)
            renderer.reflectJoinState(joined, navigate = { routes += it }, onJoined = invites::joined)
            assertEquals(listOf("group/${group.id}"), routes)
            assertNull(invites.pendingLink.value)
        }
        compose.onNodeWithText("Join").assertDoesNotExist()
        coVerify(exactly = 2) { useCase(link) }
    }

    private fun installContent(activity: ComponentActivity) {
        activity.setContent {
            invites = viewModel()
            val pending by invites.pendingLink.collectAsState()
            val state by coordinator.state.collectAsState()
            SplitFreeTheme {
                renderer.PendingInvitePrompt(
                    pending,
                    ready,
                    onboarding,
                    state is JoinGroupCoordinator.State.Joining,
                    onJoin = coordinator::join,
                    onDismiss = invites::dismiss
                )
            }
        }
    }
}
