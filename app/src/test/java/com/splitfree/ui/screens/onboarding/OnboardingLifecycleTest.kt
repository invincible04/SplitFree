package com.splitfree.ui.screens.onboarding

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.IdentityState
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.usecase.group.IdentitySwitchCoordinator
import com.splitfree.ui.navigation.Screen
import com.splitfree.ui.navigation.navigateFrom
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.OnboardingCompletion
import com.splitfree.ui.viewmodels.OnboardingViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OnboardingLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val identity = mockk<IdentityContract>(relaxed = true)
    private val identitySwitch = mockk<IdentitySwitchCoordinator>(relaxed = true)
    private val generation = CompletableDeferred<Unit>()
    private val navigations = mutableListOf<Pair<NavHostController, Int>>()
    private var attached by mutableStateOf(true)
    private var callbackRevision by mutableIntStateOf(0)
    private var navigateOnComplete = true
    private var viewModelsCreated = 0
    private lateinit var nav: NavHostController
    private lateinit var vm: OnboardingViewModel

    @Before
    fun setup() {
        every { identity.identityState() } returns IdentityState.ABSENT
        coEvery { identitySwitch.generateKeyPair() } coAnswers {
            generation.await()
            "ab".repeat(32)
        }
    }

    @Test
    fun `activity recreation holds completion until the new composition navigates its own graph exactly once`() {
        render()
        startCreation()
        val originalActivity = compose.activity
        val originalNav = nav
        val originalVm = vm

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            assertNotSame(originalActivity, activity)
            generation.complete(Unit)
            assertEquals(OnboardingCompletion.Pending, originalVm.completion.value)
            assertTrue(navigations.isEmpty())
            // ComponentActivity has no app onCreate content; reinstall the route after the deliberate gap.
            installContent(activity)
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertNotSame(originalNav, nav)
            assertSame(originalVm, vm)
            assertEquals(1, viewModelsCreated)
            assertEquals(Screen.Onboarding.route, originalNav.currentDestination?.route)
            assertNavigatedOnce()
        }

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::installContent)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Screen.GroupsList.route, nav.currentDestination?.route)
            assertEquals(1, navigations.size)
            assertEquals(OnboardingCompletion.Acknowledged, originalVm.completion.value)
        }
        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }
    }

    @Test
    fun `completion while started or stopped remains pending until the navigation entry resumes`() {
        render()
        startCreation()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.onActivity {
            generation.complete(Unit)
            assertEquals(OnboardingCompletion.Pending, vm.completion.value)
            assertEquals(Screen.Onboarding.route, nav.currentDestination?.route)
            assertTrue(navigations.isEmpty())
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity {
            assertEquals(OnboardingCompletion.Pending, vm.completion.value)
            assertTrue(navigations.isEmpty())
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle { assertNavigatedOnce() }
    }

    @Test
    fun `detached composition retains completion and acknowledgement prevents delivery on reattachment`() {
        navigateOnComplete = false
        render()
        startCreation()
        val original = vm
        compose.runOnIdle { attached = false }
        compose.waitForIdle()
        compose.runOnIdle { generation.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(OnboardingCompletion.Pending, original.completion.value)
            assertTrue(navigations.isEmpty())
            attached = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(original, vm)
            assertEquals(1, navigations.size)
            assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
            attached = false
        }
        compose.waitForIdle()
        compose.runOnIdle { attached = true }
        compose.waitForIdle()
        compose.runOnIdle {
            vm.createIdentity("")
            vm.retryIdentity()
            assertEquals(1, navigations.size)
            assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
        }
        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }
    }

    @Test
    fun `a covered navigation entry keeps completion pending until it becomes current again`() {
        render()
        startCreation()
        compose.runOnIdle { nav.navigate(Screen.Settings.route) }
        compose.waitForIdle()
        compose.runOnIdle { generation.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Screen.Settings.route, nav.currentDestination?.route)
            assertEquals(OnboardingCompletion.Pending, vm.completion.value)
            assertTrue(navigations.isEmpty())
            nav.popBackStack()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNavigatedOnce() }
    }

    @Test
    fun `completion uses the latest callback after ordinary recomposition`() {
        render()
        startCreation()
        compose.runOnIdle { callbackRevision = 1 }
        compose.waitForIdle()
        compose.runOnIdle { generation.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(nav to 1), navigations)
            assertEquals(Screen.GroupsList.route, nav.currentDestination?.route)
            assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
        }
    }

    @Test
    fun `retry recovery across recreation completes on the new graph`() {
        every { identity.identityState() } returns IdentityState.UNAVAILABLE
        val recovery = CompletableDeferred<Unit>()
        coEvery { identitySwitch.resumeIfNeeded() } coAnswers { recovery.await() }
        render()
        every { identity.identityState() } returns IdentityState.READY
        compose.onNodeWithTag("onboarding_storage_retry").performClick()
        compose.waitForIdle()
        val originalVm = vm
        val originalNav = nav
        assertEquals(OnboardingCompletion.Running, vm.completion.value)

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::installContent)
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(originalVm, vm)
            assertNotSame(originalNav, nav)
            assertTrue(navigations.isEmpty())
            recovery.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNavigatedOnce() }
        coVerify(exactly = 1) { identitySwitch.resumeIfNeeded() }
        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
    }

    @Test
    fun `restored key keeps optional backup step through recreation until continue is tapped`() {
        render()
        compose.runOnIdle { vm.restoreKey("valid words") }
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding_restore_backup").assertIsDisplayed()
        val original = vm

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::installContent)
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding_restore_backup").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(original, vm)
            assertTrue(navigations.isEmpty())
            assertEquals(OnboardingCompletion.Idle, vm.completion.value)
            assertFalse(vm.importing.value)
        }
        compose.onNodeWithTag("onboarding_continue").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(nav to 0), navigations)
            assertEquals(Screen.GroupsList.route, nav.currentDestination?.route)
        }
        coVerify(exactly = 1) { identitySwitch.importKey("valid words") }
        coVerify(exactly = 0) { identitySwitch.generateKeyPair() }
    }

    private fun render() {
        compose.activityRule.scenario.onActivity(::installContent)
        compose.waitForIdle()
    }

    private fun installContent(activity: ComponentActivity) {
        activity.setContent {
            val controller = rememberNavController()
            nav = controller
            SplitFreeTheme {
                NavHost(
                    controller,
                    Screen.Onboarding.route,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }
                ) {
                    composable(Screen.Onboarding.route) { entry ->
                        if (attached) {
                            vm = viewModel(factory = viewModelFactory { initializer { createViewModel(activity) } })
                            val revision = callbackRevision
                            OnboardingScreen(
                                onComplete = {
                                    navigations += controller to revision
                                    if (navigateOnComplete) {
                                        controller.navigateFrom(entry) {
                                            navigate(Screen.GroupsList.route) {
                                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                                            }
                                        }
                                    }
                                },
                                viewModel = vm
                            )
                        }
                    }
                    composable(Screen.GroupsList.route) { Text("Groups") }
                    composable(Screen.Settings.route) { Text("Settings") }
                }
            }
        }
    }

    private fun createViewModel(context: Context): OnboardingViewModel {
        viewModelsCreated++
        return OnboardingViewModel(
            identity,
            mockk<SettingsContract>(relaxed = true),
            mockk<ImportGroupUseCase>(),
            context.applicationContext,
            Dispatchers.Main.immediate,
            identitySwitch
        )
    }

    private fun startCreation() {
        compose.onNodeWithTag("onboarding_get_started").performClick()
        compose.onNodeWithTag("onboarding_name_continue").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(OnboardingCompletion.Running, vm.completion.value)
            assertTrue(vm.identityBusy.value)
            assertTrue(navigations.isEmpty())
        }
        coVerify(exactly = 1) { identitySwitch.generateKeyPair() }
    }

    private fun assertNavigatedOnce() {
        assertEquals(listOf(nav to 0), navigations)
        assertEquals(Screen.GroupsList.route, nav.currentDestination?.route)
        assertEquals(OnboardingCompletion.Acknowledged, vm.completion.value)
    }
}
