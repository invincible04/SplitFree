package com.splitfree.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.ui.theme.SplitFreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws [BrandMark] at 96dp on a real canvas and checks the tile, the ribbon, and the bare variant. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandMarkTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var contentView: View

    @Test
    fun `light tile shows the launcher background and the green ribbon`() {
        assertTile(dark = false)
    }

    @Test
    fun `dark tile shows the launcher background and the green ribbon`() {
        assertTile(dark = true)
    }

    @Test
    fun `bare mark draws only the ribbon on the surface`() {
        compose.setContent {
            contentView = LocalView.current
            SplitFreeTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    BrandMark(modifier = Modifier.padding(40.dp).testTag("brand_mark"), tile = false)
                }
            }
        }
        val node = compose.onNodeWithTag("brand_mark").assertIsDisplayed().fetchSemanticsNode()
        val bounds = node.boundsInWindow
        assertEquals("box is 96dp wide at mdpi", 96, bounds.width.toInt())
        val bitmap = compose.runOnIdle {
            val decor = contentView.rootView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val left = bounds.left.toInt()
        val top = bounds.top.toInt()
        val launcherBackground = RuntimeEnvironment.getApplication().getColor(R.color.ic_launcher_background)
        listOf(3 to 3, 92 to 92).forEach { (x, y) ->
            val px = bitmap.getPixel(left + x, top + y)
            assertTrue("corner ($x, $y) shows the screen, not a tile", px != launcherBackground && !isMarkGreen(px))
        }
        val centre = (44..52).flatMap { y -> (44..52).map { x -> bitmap.getPixel(left + x, top + y) } }
        val green = centre.count { isMarkGreen(it) }
        assertTrue("centre must be mostly ribbon green, was $green of ${centre.size}", green >= centre.size * 3 / 4)
    }

    private fun assertTile(dark: Boolean) {
        compose.setContent {
            contentView = LocalView.current
            SplitFreeTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    BrandMark(modifier = Modifier.padding(40.dp).testTag("brand_mark"))
                }
            }
        }
        val node = compose.onNodeWithTag("brand_mark").assertIsDisplayed().fetchSemanticsNode()
        val bounds = node.boundsInWindow
        assertEquals("tile is 96dp wide at mdpi", 96, bounds.width.toInt())
        assertEquals("tile is 96dp tall at mdpi", 96, bounds.height.toInt())

        val bitmap = compose.runOnIdle {
            val decor = contentView.rootView
            assertTrue("View must be attached and laid out", decor.isAttachedToWindow && decor.isLaidOut)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        val left = bounds.left.toInt()
        val top = bounds.top.toInt()
        fun pixel(x: Int, y: Int): Int = bitmap.getPixel(left + x, top + y)

        val tile = IntArray(96 * 96)
        bitmap.getPixels(tile, 0, 96, left, top, 96, 96)
        assertTrue("tile must not be a flat fill", tile.toSet().size > 16)

        val launcherBackground = RuntimeEnvironment.getApplication().getColor(R.color.ic_launcher_background)
        listOf(7 to 7, 88 to 88, 48 to 2, 48 to 93).forEach { (x, y) ->
            assertEquals("launcher background at ($x, $y)", hex(launcherBackground), hex(pixel(x, y)))
        }

        val centre = (44..52).flatMap { y -> (44..52).map { x -> pixel(x, y) } }
        val green = centre.count { isMarkGreen(it) }
        assertTrue("centre must be mostly ribbon green, was $green of ${centre.size}", green >= centre.size * 3 / 4)
    }

    /** The ribbon runs from pale mint through mid green to deep teal: green leads red and is at least blue. */
    private fun isMarkGreen(argb: Int): Boolean {
        val r = AndroidColor.red(argb)
        val g = AndroidColor.green(argb)
        val b = AndroidColor.blue(argb)
        return g > r + 8 && g >= b
    }

    private fun hex(argb: Int): String = "#%08X".format(argb)
}
