package com.splitfree.ui.screens.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.DialogWindowProvider
import com.splitfree.R
import com.splitfree.ui.theme.SplitFreeTheme
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The secret sheets under large text and short windows: Hide / Copy keep their touch targets, the body
 * scrolls instead, the revealed key has no copy path besides the guarded button and the sheet's own window
 * is secure. Native renders, not device-window tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSecretSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `private key footer is visible at normal text`() {
        assertPrivateKeyControls(1f, "settings-sheet-key-normal")
    }

    @Test
    fun `private key footer is visible at 200 percent on a 640dp tall portrait`() {
        assertPrivateKeyControls(2f, "settings-sheet-key-font200")
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun `private key footer stays visible in large text landscape`() {
        assertPrivateKeyControls(2f, "settings-sheet-key-landscape-font200")
    }

    private fun assertPrivateKeyControls(scale: Float, screenshot: String) {
        RuntimeEnvironment.setFontScale(scale)
        var copies = 0
        var hides = 0
        var actualScale = 0f
        compose.setContent {
            actualScale = LocalDensity.current.fontScale
            SplitFreeTheme(darkTheme = false) {
                SettingsSheetHost(
                    SettingsSheet.PrivateKey,
                    SettingsUiState(nsec = FAKE_KEY),
                    SettingsActions(copyKey = { copies++ }, hideKey = { hides++ }),
                    {}
                )
            }
        }
        compose.runOnIdle { assertEquals("Compose must use the requested font scale", scale, actualScale, 0f) }

        assertFullControl("settings_key_hide")
        assertFullControl("settings_key_copy")
        capture("settings_sheet_key", screenshot)
        compose.onNodeWithTag("settings_key_value").performScrollTo().assertIsDisplayed()
        assertFullControl("settings_key_copy")
        assertSecureWindow("settings_sheet_key")

        compose.onNodeWithTag("settings_key_copy").performClick()
        compose.runOnIdle { assertEquals("Copy must wait for confirmation", 0, copies) }
        assertSecureWindow("settings_copy_confirm")
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, copies) }
        compose.onNodeWithTag("settings_key_copy").performClick()
        compose.onNodeWithTag("settings_copy_confirm").performClick()
        compose.runOnIdle { assertEquals(1, copies) }
        compose.onNodeWithTag("settings_key_hide").performClick()
        compose.runOnIdle { assertEquals(1, hides) }
    }

    @Test
    fun `revealed key must not expose selection semantics outside guarded copy`() {
        val toolbar = mockk<TextToolbar>(relaxed = true)
        var copies = 0
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SplitFreeTheme {
                    SettingsSheetHost(
                        SettingsSheet.PrivateKey,
                        SettingsUiState(nsec = FAKE_KEY),
                        SettingsActions(copyKey = { copies++ }),
                        {}
                    )
                }
            }
        }

        val key = compose.onNodeWithTag("settings_key_value")
        val semantics = key.fetchSemanticsNode().config
        assertFalse("Revealed key must not offer SetSelection", semantics.contains(SemanticsActions.SetSelection))
        assertFalse("Revealed key must not offer CopyText", semantics.contains(SemanticsActions.CopyText))
        key.performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, copies) }
        verify(exactly = 0) { toolbar.showMenu(any(), any(), any(), any(), any()) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun `recovery phrase body scrolls to the last word while hide and copy stay fixed`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            SplitFreeTheme {
                SettingsSheetHost(
                    SettingsSheet.Phrase,
                    SettingsUiState(seedPhrase = List(24) { "word$it" }),
                    SettingsActions(),
                    {}
                )
            }
        }

        assertFullControl("settings_phrase_copy")
        assertSecureWindow("settings_sheet_phrase")
        compose.onNodeWithText(text(R.string.settings_phrase_word, 24, "word23")).performScrollTo().assertIsDisplayed()
        assertFullControl("settings_phrase_hide")
        capture("settings_sheet_phrase", "settings-sheet-phrase-landscape-font200")
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun `recovery hub scrolls to its last row at 200 percent`() {
        RuntimeEnvironment.setFontScale(2f)
        var next: SettingsSheet? = null
        compose.setContent {
            SplitFreeTheme {
                SettingsSheetHost(SettingsSheet.Recovery, SettingsUiState(), SettingsActions(), { next = it })
            }
        }

        compose.onNodeWithTag("settings_recovery_export").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SettingsSheet.Export, next) }
    }

    /** A footer action is fully on screen, at least 48dp tall and not inside any scrolling container. */
    private fun assertFullControl(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Action must retain a full touch target", node.boundsInRoot.height >= 48f)
        assertEquals("Action must not be clipped", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
        var ancestor = node.parent
        while (ancestor != null) {
            assertFalse(
                "Footer must stay outside the scrolling body",
                ancestor.config.contains(SemanticsActions.ScrollBy)
            )
            ancestor = ancestor.parent
        }
    }

    /** The dialog window hosting [tag] carries `FLAG_SECURE`. */
    private fun assertSecureWindow(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        var view: View? = (requireNotNull(node.root) as ViewRootForTest).view
        var window: Window? = null
        while (view != null) {
            if (view is DialogWindowProvider) window = view.window
            view = view.parent as? View
        }
        assertTrue(
            "Secret dialog must own a secure window",
            requireNotNull(window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        )
    }

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    /** Draws the sheet window (reachable only through one of its semantics nodes) to a PNG. */
    private fun capture(tag: String, name: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val view = (requireNotNull(node.root) as ViewRootForTest).view.rootView
        val bitmap = compose.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File("build/outputs/ui-screenshots/$name.png")
        val directory = requireNotNull(output.parentFile)
        assertTrue("Screenshot directory must exist", directory.isDirectory || directory.mkdirs())
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot PNG must be nonempty", output.length() > 0)
        bitmap.recycle()
    }

    private companion object {
        /** Neutral fabricated key with the length of a real one; no user data. */
        val FAKE_KEY = "a".repeat(64)
    }
}
