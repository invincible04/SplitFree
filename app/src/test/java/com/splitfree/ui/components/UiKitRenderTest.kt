package com.splitfree.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.splitfree.ui.theme.SplitFreeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders [UiKitGallery] with real (native) graphics and writes the result to
 * `build/outputs/ui-screenshots/` for visual review of the kit. The window is deliberately
 * tall so the whole gallery is captured without scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h3900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiKitRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var contentView: View
    private var renderedFontScale = 1f

    @Test
    fun `light gallery renders every component`() {
        render(dark = false)
        capture("ui-kit-light", expectedWidth = 390)
    }

    @Test
    fun `dark gallery renders every component`() {
        render(dark = true)
        capture("ui-kit-dark", expectedWidth = 390)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h6200dp-mdpi")
    fun `gallery survives 200 percent text without clipping its bottom dock`() {
        RuntimeEnvironment.setFontScale(2f)
        render(dark = false)
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }
        capture("ui-kit-font200", expectedWidth = 360)
    }

    private fun render(dark: Boolean) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            SplitFreeTheme(darkTheme = dark) { UiKitGallery() }
        }
        compose.onNodeWithTag("ui_kit_gallery").assertIsDisplayed()
        // The dock is the last element; if it is displayed, nothing above it fell off the window.
        compose.onNodeWithText("Saves on this phone first. No connection needed.").assertIsDisplayed()
    }

    private fun capture(name: String, expectedWidth: Int) {
        // Compose captureToImage waits for a window redraw that Robolectric does not schedule.
        val bitmap = compose.runOnIdle {
            val decor = contentView.rootView
            assertTrue("Screenshot view must be attached and laid out", decor.isAttachedToWindow && decor.isLaidOut)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        assertEquals("Capture must use the configured screen width", expectedWidth, bitmap.width)
        assertTrue("Capture must be tall enough to hold the gallery", bitmap.height >= 2000)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Capture must contain rendered content, not a blank bitmap", pixels.toSet().size > 16)
        val output = File("build/outputs/ui-screenshots/$name.png")
        val directory = requireNotNull(output.parentFile)
        assertTrue("Screenshot directory must exist", directory.isDirectory || directory.mkdirs())
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot PNG must be nonempty", output.length() > 0)
    }
}
