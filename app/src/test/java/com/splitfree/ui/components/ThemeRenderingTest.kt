package com.splitfree.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.DarkColorScheme
import com.splitfree.ui.theme.DarkSplitFreeColors
import com.splitfree.ui.theme.LightSplitFreeColors
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.theme.splitFree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel checks exercise the component wiring as well as the palette's numeric contrast tests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var contentView: View

    @Test
    fun `dark main and loading buttons draw mint while create and destructive actions stay distinct`() {
        render(dark = true)
        val bitmap = capture()
        assertFill(bitmap, "primary", DarkSplitFreeColors.action)
        assertFill(bitmap, "loading", DarkSplitFreeColors.action)
        assertFill(bitmap, "create", DarkSplitFreeColors.lime)
        assertFill(bitmap, "delete", DarkColorScheme.error)
        compose.onNodeWithTag("loading").assertIsNotEnabled()
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
        assertFill(
            bitmap,
            "disabled",
            DarkSplitFreeColors.action.copy(alpha = 0.35f).compositeOver(DarkColorScheme.surface)
        )
    }

    @Test
    fun `light buttons keep their original ink and citron fills`() {
        render(dark = false)
        val bitmap = capture()
        assertFill(bitmap, "primary", LightSplitFreeColors.action)
        assertFill(bitmap, "loading", LightSplitFreeColors.action)
        assertFill(bitmap, "create", LightSplitFreeColors.lime)
    }

    @Test
    fun `dark hero draws a fade without changing its size or leaking outside its corners`() {
        render(dark = true)
        val bounds = compose.onNodeWithTag("hero").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertEquals(350f, bounds.width, 0f)
        assertEquals(190f, bounds.height, 0f)
        val bitmap = capture()
        val left = Color(bitmap.getPixel(bounds.left.toInt() + 20, bounds.top.toInt() + 100))
        val right = Color(bitmap.getPixel(bounds.right.toInt() - 20, bounds.top.toInt() + 100))
        assertTrue("Emerald fill must fade darker towards the right", left.luminance() > right.luminance() + 0.01f)
        assertPixel(bitmap, bounds.left.toInt(), bounds.top.toInt(), DarkColorScheme.surface)
    }

    @Test
    fun `dark hero highlight has no hard circle edge`() {
        render(dark = true)
        val bounds = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInWindow
        val bitmap = capture()
        // These pixels straddled the previous decoration's left edge at this height.
        val before = bitmap.getPixel(bounds.left.toInt() + 283, bounds.top.toInt() + 40)
        val after = bitmap.getPixel(bounds.left.toInt() + 287, bounds.top.toInt() + 40)
        for (shift in listOf(0, 8, 16)) {
            val delta = kotlin.math.abs((before shr shift and 255) - (after shr shift and 255))
            assertTrue("Glow must fade smoothly, channel delta $delta", delta <= 3)
        }
    }

    @Test
    fun `light hero retains its flat fill`() {
        render(dark = false)
        val bounds = compose.onNodeWithTag("hero").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val bitmap = capture()
        for (x in listOf(20, 175, 330)) {
            assertPixel(bitmap, bounds.left.toInt() + x, bounds.top.toInt() + 100, LightSplitFreeColors.hero)
        }
    }

    private fun render(dark: Boolean) {
        compose.setContent {
            contentView = LocalView.current
            SplitFreeTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(20.dp)) {
                        Surface(
                            modifier = Modifier.size(350.dp, 190.dp).testTag("hero"),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.splitFree.hero
                        ) {
                            Box { SfHeroBackdrop() }
                        }
                        SfPrimaryButton("Save", {}, modifier = Modifier.testTag("primary"))
                        SfPrimaryButton("Saving", {}, loading = true, modifier = Modifier.testTag("loading"))
                        SfPrimaryButton("Save", {}, enabled = false, modifier = Modifier.testTag("disabled"))
                        SfAccentButton("Create", {}, modifier = Modifier.testTag("create"))
                        SfPrimaryButton(
                            "Delete",
                            {},
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.testTag("delete")
                        )
                    }
                }
            }
        }
    }

    private fun capture(): Bitmap = compose.runOnIdle {
        val decor = contentView.rootView
        Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
    }

    private fun assertFill(bitmap: Bitmap, tag: String, expected: Color) {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertEquals("Button height is unchanged", 56f, bounds.height, 0f)
        assertPixel(bitmap, bounds.left.toInt() + 24, bounds.center.y.toInt(), expected)
    }

    private fun assertPixel(bitmap: Bitmap, x: Int, y: Int, expected: Color) {
        val actual = bitmap.getPixel(x, y)
        // Alpha compositing can round one channel by one byte on the native renderer.
        for (shift in listOf(0, 8, 16, 24)) {
            val difference = kotlin.math.abs((actual shr shift and 255) - (expected.toArgb() shr shift and 255))
            assertTrue("Pixel ($x, $y) must match $expected, channel difference $difference", difference <= 1)
        }
    }
}
