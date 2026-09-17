package com.splitfree.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SfMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PopMotionTest {
    private val durationScale = object : MotionDurationScale {
        override var scaleFactor = 1f
    }

    @get:Rule
    val compose = createComposeRule(effectContext = durationScale)

    private var visible by mutableStateOf(false)

    @Test
    fun `LTR back enters and exits instantly without slide or fade`() = assertPop(LayoutDirection.Ltr)

    @Test
    fun `RTL back enters and exits instantly without slide or fade`() = assertPop(LayoutDirection.Rtl)

    private fun assertPop(direction: LayoutDirection) {
        render(direction)
        changeVisibility(true)
        val resting = position()
        assertEquals("Enter must be at rest immediately", 100f, resting, 1f)

        changeVisibility(false)
        compose.onNodeWithTag(DESTINATION).assertDoesNotExist()
    }

    @Test
    fun `zero duration scale finishes back enter and exit without delay`() {
        durationScale.scaleFactor = 0f
        render(LayoutDirection.Rtl)
        changeVisibility(true)
        val resting = position()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(resting, position(), 0f)
        changeVisibility(false)
        compose.onNodeWithTag(DESTINATION).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `underlying destination is opaque as soon as back starts`() {
        render(LayoutDirection.Ltr)
        changeVisibility(true)
        val node = compose.onNodeWithTag(DESTINATION).fetchSemanticsNode()
        val view = (requireNotNull(node.root) as ViewRootForTest).view
        val bitmap = compose.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val center = node.boundsInRoot.center
        assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(center.x.toInt(), center.y.toInt()))
        bitmap.recycle()
    }

    private fun render(direction: LayoutDirection) {
        compose.setContent {
            Box(Modifier.background(Color.Red).padding(100.dp)) {
                AnimatedVisibility(
                    visible,
                    enter = SfMotion.popEnter(direction),
                    exit = SfMotion.popExit(direction)
                ) { Box(Modifier.size(180.dp).background(Color.Green).testTag(DESTINATION)) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun changeVisibility(value: Boolean) {
        compose.runOnIdle {
            visible = value
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
    }

    private fun position() = compose.onNodeWithTag(DESTINATION).fetchSemanticsNode().positionInRoot.x

    private companion object {
        const val DESTINATION = "destination"
    }
}
