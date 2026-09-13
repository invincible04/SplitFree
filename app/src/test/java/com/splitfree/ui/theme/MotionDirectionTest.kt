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
    fun `forward enters from the trailing edge in LTR`() = assertSlide(LayoutDirection.Ltr, pop = false)

    @Test
    fun `forward enters from the trailing edge in RTL`() = assertSlide(LayoutDirection.Rtl, pop = false)

    @Test
    fun `back enters from the leading edge in LTR`() = assertSlide(LayoutDirection.Ltr, pop = true)

    @Test
    fun `back enters from the leading edge in RTL`() = assertSlide(LayoutDirection.Rtl, pop = true)

    private fun assertSlide(direction: LayoutDirection, pop: Boolean) {
        var visible by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.padding(100.dp)) {
                AnimatedVisibility(
                    visible,
                    enter = if (pop) SfMotion.popEnter(direction) else SfMotion.forwardEnter(direction),
                    exit = if (pop) SfMotion.popExit(direction) else SfMotion.forwardExit(direction)
                ) { Box(Modifier.size(180.dp).testTag("destination")) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        advanceVisibilityChange { visible = true }
        val entering = compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x
        compose.mainClock.advanceTimeBy(SfMotion.Base.toLong())
        val resting = compose.onNodeWithTag("destination").fetchSemanticsNode().positionInRoot.x
        // Forward comes from the trailing edge, back from the leading edge; RTL swaps which side that is.
        val fromRight = (direction == LayoutDirection.Ltr) != pop
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
