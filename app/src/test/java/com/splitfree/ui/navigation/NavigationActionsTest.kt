package com.splitfree.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.splitfree.R
import com.splitfree.ui.screens.expense.AddExpenseContent
import com.splitfree.ui.screens.expense.ExpenseEditorActions
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.AddExpenseUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NavigationActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val entries = mutableMapOf<String, NavBackStackEntry>()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `two forward callbacks from the same outgoing entry push only one destination`() {
        render()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val root = entries.getValue(ROOT)
            repeat(2) { nav.navigateFrom(root) { navigate(DETAIL) } }
            assertEquals(DETAIL, nav.currentDestination?.route)
            assertSame(root, nav.previousBackStackEntry)
        }
    }

    @Test
    fun `repeated toolbar back during pop cannot skip the previous destination`() {
        render()
        navigate(DETAIL)
        navigate(EDITOR)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val editor = entries.getValue(EDITOR)
            repeat(3) { nav.navigateFrom(editor) { popBackStack() } }
            assertSame(entries.getValue(DETAIL), nav.currentBackStackEntry)
            assertSame(entries.getValue(ROOT), nav.previousBackStackEntry)
        }
    }

    @Test
    fun `return action is accepted during enter instead of waiting for resumed`() {
        render()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            nav.navigate(DETAIL)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeUntil(timeoutMillis = SfMotion.Fast.toLong()) {
            compose.runOnIdle { DETAIL in entries }
        }
        compose.runOnIdle {
            val detail = entries.getValue(DETAIL)
            assertFalse(detail.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            nav.navigateFrom(detail) { popBackStack() }
            assertEquals(ROOT, nav.currentDestination?.route)
        }
    }

    @Test
    fun `replaced entry with the same route cannot pop its replacement`() {
        render()
        navigate(DETAIL)
        lateinit var previous: NavBackStackEntry
        compose.runOnIdle {
            previous = entries.getValue(DETAIL)
            nav.popBackStack()
        }
        compose.waitForIdle()
        navigate(DETAIL)
        compose.runOnIdle {
            val replacement = nav.currentBackStackEntry
            nav.navigateFrom(previous) { popBackStack() }
            assertSame(replacement, nav.currentBackStackEntry)
        }
    }

    @Test
    fun `save result and pop execute once for the owning editor`() {
        render()
        navigate(DETAIL)
        navigate(EDITOR)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val editor = entries.getValue(EDITOR)
            repeat(2) {
                nav.navigateFrom(editor) {
                    previousBackStackEntry?.savedStateHandle?.set("expenseSaved", true)
                    popBackStack()
                }
            }
            assertSame(entries.getValue(DETAIL), nav.currentBackStackEntry)
            assertEquals(true, entries.getValue(DETAIL).savedStateHandle.get<Boolean>("expenseSaved"))
            assertNull(entries.getValue(ROOT).savedStateHandle.get<Boolean>("expenseSaved"))
        }
    }

    @Test
    fun `async completion still navigates while the owning activity is stopped`() {
        render()
        navigate(DETAIL)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            val detail = entries.getValue(DETAIL)
            assertEquals(Lifecycle.State.CREATED, detail.lifecycle.currentState)
            nav.navigateFrom(detail) { navigate(EDITOR) }
            assertEquals(EDITOR, nav.currentDestination?.route)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
    }

    @Test
    fun `toolbar and system back still require explicit dirty draft discard`() {
        render(dirtyEditor = true)
        navigate(DETAIL)
        navigate(EDITOR)
        compose.onNodeWithTag("expense_back").performClick()
        compose.onNodeWithText(text(R.string.expense_discard_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(EDITOR, nav.currentDestination?.route) }
        compose.onNodeWithText(text(R.string.expense_keep_editing)).performClick()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.expense_discard_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(EDITOR, nav.currentDestination?.route) }
        compose.onNodeWithText(text(R.string.expense_discard)).performClick()
        compose.runOnIdle { assertEquals(DETAIL, nav.currentDestination?.route) }
    }

    private fun navigate(route: String) {
        compose.runOnIdle { nav.navigate(route) }
        compose.waitForIdle()
    }

    private fun render(dirtyEditor: Boolean = false) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            backDispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SplitFreeTheme(darkTheme = false) {
                    nav = rememberNavController()
                    NavHost(
                        navController = nav,
                        startDestination = ROOT,
                        enterTransition = { SfMotion.forwardEnter(LayoutDirection.Ltr) },
                        exitTransition = { SfMotion.forwardExit(LayoutDirection.Ltr) },
                        popEnterTransition = { SfMotion.popEnter(LayoutDirection.Ltr) },
                        popExitTransition = { SfMotion.popExit(LayoutDirection.Ltr) }
                    ) {
                        listOf(ROOT, DETAIL, EDITOR).forEach { route ->
                            composable(route) { entry ->
                                entries[route] = entry
                                if (route == EDITOR && dirtyEditor) {
                                    AddExpenseContent(
                                        AddExpenseUiState(loading = false, editable = true, dirty = true),
                                        ExpenseEditorActions(back = { nav.navigateFrom(entry) { popBackStack() } })
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().testTag(route))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private companion object {
        const val ROOT = "root"
        const val DETAIL = "detail"
        const val EDITOR = "editor"
    }
}
