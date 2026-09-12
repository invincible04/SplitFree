package com.splitfree.ui.screens.debug

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.splitfree.R
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.util.DebugLog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var contentView: View

    @Before
    fun clearBuffer() = DebugLog.clear()

    @After
    fun clearBufferAfter() = DebugLog.clear()

    @Test
    fun `lists every level in monospace and counts them in the title`() {
        seedEntries()
        render()

        compose.onNodeWithText(text(R.string.debug_logs_title, 4)).assertIsDisplayed()
        compose.onNodeWithText("D/Sync: socket opened", substring = true).assertExists()
        compose.onNodeWithText("I/Sync: subscribed to 2 relays", substring = true).assertExists()
        compose.onNodeWithText("W/Relay: slow response", substring = true).assertExists()
        compose.onNodeWithText("E/Relay: handshake failed", substring = true).assertExists()
        compose.onNodeWithTag("debug_empty").assertDoesNotExist()
    }

    @Test
    fun `filter chips narrow the list by tag and toggle back to all`() {
        seedEntries()
        render()

        compose.onNodeWithTag("debug_filter_bar").assertDoesNotExist()
        compose.onNodeWithTag("debug_filter").performClick()
        compose.onNodeWithTag("debug_filter_bar").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.all)).assertIsSelected()

        compose.onNodeWithTag("debug_tag_Relay").performClick()
        compose.onNodeWithText(text(R.string.debug_logs_title, 2)).assertIsDisplayed()
        compose.onAllNodesWithText("Sync:", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("debug_tag_Relay").assertIsSelected()

        compose.onNodeWithTag("debug_tag_Relay").performClick()
        compose.onNodeWithText(text(R.string.debug_logs_title, 4)).assertIsDisplayed()
    }

    @Test
    fun `an empty buffer shows the empty state and a filter with no matches too`() {
        render()

        compose.onNodeWithText(text(R.string.debug_logs_title, 0)).assertIsDisplayed()
        compose.onNodeWithTag("debug_empty").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.debug_empty_title)).assertIsDisplayed()
        compose.onNodeWithTag("debug_log_list").assertDoesNotExist()
    }

    @Test
    fun `clearing empties the buffer and the poll picks it up`() {
        seedEntries()
        render()

        compose.onNodeWithTag("debug_clear").performClick()

        assertEquals(0, DebugLog.entries.size)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.debug_logs_title, 0)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("debug_empty").assertIsDisplayed()
    }

    @Test
    fun `back button invokes the callback`() {
        var backs = 0
        render(onBack = { backs++ })

        compose.onNodeWithText(text(R.string.debug_logs_title, 0)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `renders the log in light`() {
        seedEntries()
        render()
        compose.onNodeWithTag("debug_filter").performClick()
        compose.onNodeWithTag("debug_filter_bar").assertIsDisplayed()
        capture("debug-log-light")
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private fun seedEntries() {
        DebugLog.d("Sync", "socket opened")
        DebugLog.i("Sync", "subscribed to 2 relays")
        DebugLog.w("Relay", "slow response")
        DebugLog.e("Relay", "handshake failed")
    }

    private fun render(onBack: () -> Unit = {}) {
        compose.setContent {
            contentView = LocalView.current
            SplitFreeTheme { DebugLogScreen(onBack = onBack) }
        }
        compose.waitForIdle()
    }

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun capture(name: String, expectedWidth: Int = 390) {
        val bitmap = compose.runOnIdle {
            val decor = contentView.rootView
            assertTrue("Screenshot view must be attached and laid out", decor.isAttachedToWindow && decor.isLaidOut)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        assertEquals("Capture must use the configured screen width", expectedWidth, bitmap.width)
        assertTrue("Capture must have screen height", bitmap.height >= 600)
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
