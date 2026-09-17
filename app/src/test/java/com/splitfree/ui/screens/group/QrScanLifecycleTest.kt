package com.splitfree.ui.screens.group

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.splitfree.R
import com.splitfree.ui.util.QrScannerBackend
import com.splitfree.ui.viewmodels.QrScanPhase
import com.splitfree.ui.viewmodels.QrScanViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class QrScanLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController
    private lateinit var vm: QrScanViewModel
    private val preparation = CompletableDeferred<Unit>()
    private val result = CompletableDeferred<String?>()
    private var scans = 0
    private var attached by mutableStateOf(true)
    private val delivered = mutableListOf<String>()

    private fun render(deliver: (String) -> Unit = { delivered += it }) {
        val backend = object : QrScannerBackend {
            override suspend fun prepare() {
                preparation.await()
            }
            override suspend fun scan(): String? {
                scans++
                return result.await()
            }
        }
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, "home", enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
                composable("home") {
                    if (attached) {
                        vm = viewModel(factory = viewModelFactory { initializer { QrScanViewModel(backend) } })
                        QrScanLifecycle(vm, deliver)
                    }
                    Text("Home")
                }
                composable("other") { Text("Other") }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `navigating away during preparation prevents late camera launch`() {
        render()
        compose.runOnIdle { vm.start() }
        compose.runOnIdle {
            assertEquals(QrScanPhase.Preparing, vm.state.value.phase)
            nav.navigate("other")
        }
        compose.waitForIdle()
        compose.runOnIdle { preparation.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, scans)
            assertEquals(QrScanPhase.Idle, vm.state.value.phase)
        }
    }

    @Test
    fun `entry scoped scanner survives composition recreation with one result delivery`() {
        preparation.complete(Unit)
        render()
        compose.runOnIdle { vm.start() }
        compose.waitForIdle()
        val original = vm
        compose.runOnIdle { attached = false }
        compose.waitForIdle()
        compose.runOnIdle { result.complete("invite") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(delivered.isEmpty())
            attached = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(original, vm)
            assertEquals(1, scans)
            assertEquals(listOf("invite"), delivered)
        }
        compose.runOnIdle { attached = false }
        compose.waitForIdle()
        compose.runOnIdle { attached = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("invite"), delivered) }
    }

    @Test
    fun `real activity stop retains scan and defers callback until resume`() {
        preparation.complete(Unit)
        render()
        compose.runOnIdle { vm.start() }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        result.complete("invite")
        compose.activityRule.scenario.onActivity {
            assertTrue(delivered.isEmpty())
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, scans)
            assertEquals(listOf("invite"), delivered)
        }
    }

    @Test
    fun `result callback failure stays contained and is not delivered repeatedly`() {
        preparation.complete(Unit)
        var deliveries = 0
        render {
            deliveries++
            throw IllegalArgumentException("invalid input")
        }
        compose.runOnIdle {
            vm.start()
            result.complete("invite")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, deliveries)
            assertEquals(R.string.invalid_invite_link, vm.state.value.error)
        }
    }

    @Test
    fun `navigating away when error is present clears error`() {
        preparation.complete(Unit)
        render { throw IllegalArgumentException("invalid input") }
        compose.runOnIdle {
            vm.start()
            result.complete("invite")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(R.string.invalid_invite_link, vm.state.value.error)
            nav.navigate("other")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            org.junit.Assert.assertNull(vm.state.value.error)
        }
    }
}
