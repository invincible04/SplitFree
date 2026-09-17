package com.splitfree.ui.theme

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigation transitions follow the reading direction: forward enters from the trailing edge and back from
 * the leading edge, so under RTL every slide mirrors.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MotionDirectionTest {
    private val durationScale = object : MotionDurationScale {
        override var scaleFactor: Float = 1f
    }

    @get:Rule
    val compose = createComposeRule(effectContext = durationScale)

    @Test
    fun `forward enters from the trailing edge in LTR`() = assertSlide(LayoutDirection.Ltr)

    @Test
    fun `forward enters from the trailing edge in RTL`() = assertSlide(LayoutDirection.Rtl)

    @Test
    fun `back enters instantly in LTR`() = assertInstantPop(LayoutDirection.Ltr)

    @Test
    fun `back enters instantly in RTL`() = assertInstantPop(LayoutDirection.Rtl)

    private fun assertSlide(direction: LayoutDirection) {
        var visible by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.padding(100.dp)) {
                AnimatedVisibility(
                    visible,
                    enter = SfMotion.forwardEnter(direction),
                    exit = SfMotion.forwardExit(direction)
                ) { Box(Modifier.size(180.dp).testTag("destination")) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        advanceVisibilityChange { visible = true }
        val entering = compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x
        compose.mainClock.advanceTimeBy(SfMotion.Base.toLong())
        val resting = compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x
        val fromRight = (direction == LayoutDirection.Ltr)
        assertTrue(
            "Enter must slide in from the ${if (fromRight) "right" else "left"}",
            if (fromRight) entering > resting else entering < resting
        )

        advanceVisibilityChange { visible = false }
        val leaving = compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x
        assertTrue(
            "Exit must travel towards the opposite edge",
            if (fromRight) leaving < resting else leaving > resting
        )
        compose.mainClock.advanceTimeBy(SfMotion.Base.toLong())
        compose.onNodeWithTag("destination").assertDoesNotExist()
    }

    private fun assertInstantPop(direction: LayoutDirection) {
        var visible by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.padding(100.dp)) {
                AnimatedVisibility(
                    visible,
                    enter = SfMotion.popEnter(direction),
                    exit = SfMotion.popExit(direction)
                ) { Box(Modifier.size(180.dp).testTag("destination")) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        advanceVisibilityChange { visible = true }
        val x = compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x
        assertEquals("Instant enter must place destination immediately at rest", 100f, x, 1f)

        advanceVisibilityChange { visible = false }
        compose.onNodeWithTag("destination").assertDoesNotExist()
    }

    @Test
    fun `a zero duration scale completes enter and exit without a tween delay`() {
        durationScale.scaleFactor = 0f
        var visible by mutableStateOf(false)
        compose.setContent {
            AnimatedVisibility(
                visible,
                enter = SfMotion.forwardEnter(LayoutDirection.Rtl),
                exit = SfMotion.popExit(LayoutDirection.Rtl)
            ) {
                Box(Modifier.size(90.dp).testTag("destination"))
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        advanceVisibilityChange { visible = true }
        assertEquals(0f, compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x, 0f)
        advanceVisibilityChange { visible = false }
        compose.onNodeWithTag("destination").assertDoesNotExist()
    }

    private fun advanceVisibilityChange(change: () -> Unit) {
        compose.runOnIdle {
            change()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        // Android measure/layout must run after recomposition before the transition can start.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
    }
}
