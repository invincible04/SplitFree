package com.splitfree.ui.screens.settings

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.splitfree.R
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.viewmodels.ExportState
import com.splitfree.ui.viewmodels.RevokeState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class SettingsContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(fixtureState())
    private var sheet by mutableStateOf<SettingsSheet?>(null)
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    // --- Hero ---------------------------------------------------------------------------------------------

    @Test
    fun `hero shows the name, its initial and the shortened public key`() {
        render()

        compose.onNodeWithTag("settings_hero_name").assertTextEquals("Priya")
        compose.onNodeWithTag("settings_hero_tile").assertTextEquals("P")
        compose.onNodeWithTag("settings_hero_npub").assertTextEquals("a1b2c3d4e5f6…7e8f90")
        compose.onNodeWithTag("settings_version").assertTextEquals("SplitFree v1.0.0")
    }

    @Test
    fun `footer links open the source and the privacy policy`() {
        var source = 0
        var privacy = 0
        render(SettingsActions(openSourceCode = { source++ }, openPrivacyPolicy = { privacy++ }))

        compose.onNodeWithTag("settings_source_code").assertTextEquals(text(R.string.settings_source_code))
        compose.onNodeWithTag("settings_source_code").performClick()
        compose.onNodeWithTag("settings_privacy_policy").assertTextEquals(text(R.string.settings_privacy_policy))
        compose.onNodeWithTag("settings_privacy_policy").performClick()
        assertEquals(1, source)
        assertEquals(1, privacy)
    }

    @Test
    fun `hero falls back to a person tile and generic copy without a name or identity`() {
        state = state.copy(displayName = "  ", npub = "")
        render()

        compose.onNodeWithTag("settings_hero_name").assertTextEquals(text(R.string.settings_your_profile))
        compose.onNodeWithTag("settings_hero_tile").assertContentDescriptionEquals(text(R.string.cd_person_icon))
        compose.onNodeWithContentDescription(text(R.string.cd_person_icon)).assertExists()
        compose.onNodeWithTag("settings_hero_npub").assertTextEquals(text(R.string.settings_no_identity))
    }

    @Test
    fun `short public keys are shown whole and long ones keep head and tail`() {
        assertEquals("abc", shortPublicKey("abc"))
        assertEquals("a1b2c3d4e5f6…7e8f90", shortPublicKey(PUBKEY))
        assertEquals("0123456789012345678", shortPublicKey("0123456789012345678"))
    }

    // --- Appearance ---------------------------------------------------------------------------------------

    @Test
    fun `appearance row reflects the theme mode and a choice applies at once`() {
        val chosen = mutableListOf<ThemeMode>()
        render(SettingsActions(setThemeMode = { chosen += it }))

        compose.onNodeWithTag("settings_appearance").assert(hasText(text(R.string.settings_theme_light))).performClick()

        compose.runOnIdle { assertEquals(SettingsSheet.Theme, sheet) }
        compose.onNodeWithTag("settings_theme_LIGHT").assertIsSelected()
        compose.onNodeWithTag("settings_theme_DARK").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ThemeMode.DARK), chosen)
            assertNull(sheet)
        }
        state = state.copy(themeMode = ThemeMode.DARK)
        compose.onNodeWithTag("settings_appearance").assert(hasText(text(R.string.settings_theme_dark)))
    }

    // --- Gift wrap ----------------------------------------------------------------------------------------

    @Test
    fun `gift wrap row is one switch that reports the flipped value`() {
        val toggles = mutableListOf<Boolean>()
        render(SettingsActions(setGiftWrap = { toggles += it }))

        compose.onNodeWithTag("settings_gift_wrap")
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick()

        compose.runOnIdle { assertEquals(listOf(false), toggles) }
        state = state.copy(giftWrapEnabled = false)
        compose.onNodeWithTag("settings_gift_wrap").assertIsOff()
    }

    // --- Profile sheet ------------------------------------------------------------------------------------

    @Test
    fun `edit opens the profile sheet, save commits the typed name and cancel discards`() {
        val names = mutableListOf<String>()
        render(SettingsActions(setDisplayName = { names += it }))

        compose.onNodeWithTag("settings_edit").performClick()
        compose.runOnIdle { assertEquals(SettingsSheet.Profile, sheet) }
        compose.onNodeWithText(text(R.string.settings_display_name_note)).assertIsDisplayed()
        compose.onNodeWithTag("settings_name_field").performTextClearance()
        compose.onNodeWithTag("settings_name_field").performTextInput("Priya S ")
        compose.runOnIdle { assertEquals("Typing must not commit", emptyList<String>(), names) }
        compose.onNodeWithTag("settings_name_save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Priya S"), names)
            assertNull(sheet)
        }

        sheet = SettingsSheet.Profile
        compose.onNodeWithTag("settings_name_field").performTextInput("X")
        compose.onNodeWithTag("settings_name_cancel").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Priya S"), names)
            assertNull(sheet)
        }
    }

    // --- Recovery, phrase and private key -----------------------------------------------------------------

    @Test
    fun `recovery leads to the phrase sheet which stays masked until reveal`() {
        render(
            SettingsActions(
                revealSeed = { state = state.copy(seedPhrase = WORDS) },
                hideSeed = { state = state.copy(seedPhrase = emptyList()) }
            )
        )

        compose.onNodeWithTag("settings_recovery").performClick()
        compose.runOnIdle { assertEquals(SettingsSheet.Recovery, sheet) }
        compose.onNodeWithText(text(R.string.settings_recovery_intro)).assertIsDisplayed()
        compose.onNodeWithTag("settings_recovery_phrase").performClick()

        compose.runOnIdle { assertEquals(SettingsSheet.Phrase, sheet) }
        compose.onNodeWithText(text(R.string.key_backup_warning)).assertIsDisplayed()
        compose.onNodeWithTag("settings_phrase_masked")
            .assertExists()
            .assertContentDescriptionEquals(text(R.string.settings_phrase_hidden))
        compose.onNodeWithTag("settings_phrase_words").assertDoesNotExist()
        compose.onNodeWithText("1 · abandon").assertDoesNotExist()

        compose.onNodeWithTag("settings_phrase_reveal").performClick()

        compose.onNodeWithTag("settings_phrase_words").assertExists()
        compose.onNodeWithTag("settings_phrase_masked").assertDoesNotExist()
        compose.onNodeWithText("1 · abandon").assertExists()
        compose.onNodeWithText("24 · zoo").assertExists()
        compose.onNodeWithTag("settings_phrase_copy").assertExists()

        compose.onNodeWithTag("settings_phrase_hide").performClick()
        compose.onNodeWithTag("settings_phrase_masked").assertExists()
        compose.onNodeWithTag("settings_phrase_words").assertDoesNotExist()
    }

    @Test
    fun `copying the phrase asks for confirmation first`() {
        var copies = 0
        state = state.copy(seedPhrase = WORDS)
        render(SettingsActions(copySeed = { copies++ }))
        sheet = SettingsSheet.Phrase

        compose.onNodeWithTag("settings_phrase_copy").performClick()
        compose.onNodeWithText(text(R.string.copy_seed_warning)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, copies) }

        compose.onNodeWithTag("settings_copy_confirm").performClick()
        compose.runOnIdle { assertEquals(1, copies) }
        compose.onNodeWithText(text(R.string.copy_seed_warning)).assertDoesNotExist()
    }

    @Test
    fun `private key sheet masks the key until reveal and then shows it in full`() {
        var copies = 0
        render(
            SettingsActions(
                revealKey = { state = state.copy(nsec = NSEC) },
                hideKey = { state = state.copy(nsec = "") },
                copyKey = { copies++ }
            )
        )
        sheet = SettingsSheet.PrivateKey

        compose.onNodeWithTag("settings_key_masked")
            .assertExists()
            .assertContentDescriptionEquals(text(R.string.settings_private_key_hidden))
        compose.onNodeWithTag("settings_key_value").assertDoesNotExist()

        compose.onNodeWithTag("settings_key_reveal").performClick()
        compose.onNodeWithTag("settings_key_value").assertTextEquals(NSEC)

        compose.onNodeWithTag("settings_key_copy").performClick()
        compose.onNodeWithText(text(R.string.copy_key_warning)).assertIsDisplayed()
        compose.onNodeWithTag("settings_copy_confirm").performClick()
        compose.runOnIdle { assertEquals(1, copies) }

        compose.onNodeWithTag("settings_key_hide").performClick()
        compose.onNodeWithTag("settings_key_masked").assertExists()
    }

    @Test
    fun `closing a secret sheet hides the secret and drops FLAG_SECURE`() {
        var hides = 0
        state = state.copy(seedPhrase = WORDS)
        render(
            SettingsActions(hideSeed = {
                hides++
                state = state.copy(seedPhrase = emptyList())
            })
        )
        sheet = SettingsSheet.Phrase
        compose.runOnIdle { assertTrue("Revealed secret must secure the window", windowIsSecure()) }

        compose.onNode(
            hasContentDescription(text(R.string.cd_close)) and hasAnyAncestor(hasTestTag("settings_sheet_phrase"))
        )
            .performClick()

        compose.runOnIdle {
            assertEquals(1, hides)
            assertNull(sheet)
            assertFalse("Hidden secret must release the window", windowIsSecure())
        }
    }

    @Test
    fun `FLAG_SECURE follows the revealed state without any sheet`() {
        render()
        compose.runOnIdle { assertFalse(windowIsSecure()) }

        state = state.copy(nsec = NSEC)
        compose.runOnIdle { assertTrue(windowIsSecure()) }

        state = state.copy(nsec = "")
        compose.runOnIdle { assertFalse(windowIsSecure()) }
    }

    // --- Export -------------------------------------------------------------------------------------------

    @Test
    fun `export sheet launches the export and renders progress, failure and success`() {
        var exports = 0
        var clears = 0
        render(SettingsActions(exportBackup = { exports++ }, clearExportState = { clears++ }))

        compose.onNodeWithTag("settings_export").performClick()
        compose.runOnIdle { assertEquals(SettingsSheet.Export, sheet) }
        compose.onNodeWithText(text(R.string.export_hint)).assertIsDisplayed()
        compose.onNodeWithTag("settings_export_button").performClick()
        compose.runOnIdle { assertEquals(1, exports) }

        state = state.copy(exportState = ExportState.InProgress)
        compose.onNodeWithTag("settings_export_button").assertIsNotEnabled()

        state = state.copy(exportState = ExportState.Error("Disk full"))
        compose.onNodeWithTag("settings_export_error").assertIsDisplayed()
        compose.onNodeWithText("Disk full", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings_export_button").performClick()
        compose.runOnIdle { assertEquals("Failure must be retryable", 2, exports) }

        state = state.copy(exportState = ExportState.Done)
        compose.onNodeWithTag("settings_export_done").assertIsDisplayed()
        compose.onNodeWithTag("settings_export_button").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle {
            assertEquals(1, clears)
            assertNull(sheet)
        }
    }

    @Test
    fun `recovery sheet also reaches the export`() {
        render()
        sheet = SettingsSheet.Recovery

        compose.onNodeWithTag("settings_recovery_export").performClick()
        compose.runOnIdle { assertEquals(SettingsSheet.Export, sheet) }

        sheet = SettingsSheet.Recovery
        compose.onNodeWithTag("settings_recovery_key").performClick()
        compose.runOnIdle { assertEquals(SettingsSheet.PrivateKey, sheet) }
    }

    // --- Diagnostics --------------------------------------------------------------------------------------

    @Test
    fun `diagnostics row summarises the outbox and the sheet copies the report`() {
        var copies = 0
        render(SettingsActions(copyDiagnostics = { copies++ }))

        compose.onNodeWithText("3 events waiting to sync (1 stuck)").assertIsDisplayed()
        compose.onNodeWithTag("settings_diagnostics").performClick()

        compose.runOnIdle { assertEquals(SettingsSheet.Diagnostics, sheet) }
        compose.onNodeWithText(text(R.string.settings_pending_events)).assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_stuck_events)).assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNode(
            hasText("1.0.0") and hasAnyAncestor(hasTestTag("settings_sheet_diagnostics"))
        ).assertIsDisplayed()
        compose.onNodeWithTag("settings_copy_report").performClick()
        compose.runOnIdle { assertEquals(1, copies) }

        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle { assertNull(sheet) }
    }

    @Test
    fun `an idle outbox shows the generic diagnostics subtitle`() {
        state = state.copy(pendingOutbox = 0, stuckOutbox = 0)
        render()

        compose.onNodeWithText(text(R.string.settings_diagnostics_subtitle)).assertIsDisplayed()
        compose.onNodeWithText("waiting to sync", substring = true).assertDoesNotExist()
    }

    // --- Debug logs ---------------------------------------------------------------------------------------

    @Test
    fun `debug logs row appears only in debug builds`() {
        var opened = 0
        render(SettingsActions(openDebugLog = { opened++ }))

        compose.onNodeWithTag("settings_debug_logs").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, opened) }

        state = state.copy(isDebugBuild = false)
        compose.onNodeWithTag("settings_debug_logs").assertDoesNotExist()
        compose.onNodeWithTag("settings_revoke").assertExists()
    }

    // --- Revoke -------------------------------------------------------------------------------------------

    @Test
    fun `revoke sheet shows the warning and confirming invokes revoke`() {
        var revokes = 0
        render(SettingsActions(revoke = { revokes++ }))

        compose.onNodeWithTag("settings_revoke").performClick()

        compose.runOnIdle { assertEquals(SettingsSheet.Revoke, sheet) }
        compose.onNodeWithText(text(R.string.revoke_key_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_revoke_consequences)).assertIsDisplayed()
        compose.onNodeWithTag("settings_revoke_confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, revokes)
            assertEquals("The sheet stays open to show the outcome", SettingsSheet.Revoke, sheet)
        }
    }

    @Test
    fun `revoke sheet renders progress, the error branch and success`() {
        var clears = 0
        render(SettingsActions(clearRevokeState = { clears++ }))
        sheet = SettingsSheet.Revoke

        state = state.copy(revokeState = RevokeState.InProgress)
        compose.onNodeWithTag("settings_revoke_confirm").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.cancel)).assertIsNotEnabled()

        state = state.copy(revokeState = RevokeState.Error("Relay refused the rotation"))
        compose.onNodeWithTag("settings_revoke_error").assertIsDisplayed()
        compose.onNodeWithText("Relay refused the rotation").assertIsDisplayed()
        compose.onNodeWithTag("settings_revoke_confirm").assertExists()

        state = state.copy(revokeState = RevokeState.Done(NEW_PUBKEY))
        compose.onNodeWithTag("settings_revoke_done").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.new_pubkey, NEW_PUBKEY.take(12)), substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings_revoke_confirm").assertDoesNotExist()

        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle {
            assertEquals(1, clears)
            assertNull(sheet)
        }
    }

    @Test
    fun `the sheet saver round-trips every sheet and nothing`() {
        val sheets =
            listOf(
                SettingsSheet.Profile,
                SettingsSheet.Theme,
                SettingsSheet.Recovery,
                SettingsSheet.Phrase,
                SettingsSheet.PrivateKey,
                SettingsSheet.Export,
                SettingsSheet.Diagnostics,
                SettingsSheet.Revoke,
                null
            )
        sheets.forEach { value ->
            val saved = with(SettingsSheetSaver) { NoopSaverScope.save(value) }
            assertEquals(value, SettingsSheetSaver.restore(requireNotNull(saved)))
        }
    }

    // --- Screenshots --------------------------------------------------------------------------------------

    @Test
    fun `renders settings in light`() {
        render()
        capture("settings-light")
    }

    @Test
    fun `renders settings in dark`() {
        render(dark = true)
        capture("settings-dark")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h844dp-mdpi")
    fun `renders settings at 200 percent text on a 360dp phone`() {
        RuntimeEnvironment.setFontScale(2f)
        render()
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }
        compose.onNodeWithTag("settings_edit").assertIsDisplayed()
        capture("settings-font200", expectedWidth = 360)
    }

    @Test
    fun `renders the recovery sheet`() {
        render()
        sheet = SettingsSheet.Recovery
        compose.onNodeWithTag("settings_recovery_phrase").assertIsDisplayed()
        capture("settings-sheet-recovery-light", overlay = compose.onNodeWithTag("settings_sheet_recovery"))
    }

    @Test
    fun `renders the masked phrase sheet`() {
        render()
        sheet = SettingsSheet.Phrase
        compose.onNodeWithTag("settings_phrase_reveal").assertIsDisplayed()
        capture("settings-sheet-phrase-masked-light", overlay = compose.onNodeWithTag("settings_sheet_phrase"))
    }

    @Test
    fun `renders the revoke sheet`() {
        render()
        sheet = SettingsSheet.Revoke
        compose.onNodeWithTag("settings_revoke_confirm").assertIsDisplayed()
        capture("settings-sheet-revoke-light", overlay = compose.onNodeWithTag("settings_sheet_revoke"))
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private fun render(actions: SettingsActions = SettingsActions(), dark: Boolean = false) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            val snackbarHostState = remember { SnackbarHostState() }
            SplitFreeTheme(darkTheme = dark) {
                SettingsContent(
                    state = state,
                    actions = actions,
                    sheet = sheet,
                    onSheet = { sheet = it },
                    snackbarHostState = snackbarHostState
                )
            }
        }
        compose.onNodeWithText(text(R.string.settings)).assertIsDisplayed()
    }

    private fun windowIsSecure(): Boolean {
        val activity = requireNotNull(contentView.context as? Activity) { "Compose host must be an Activity" }
        return activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    /**
     * Draws the main window and, for sheets, the sheet's own window on top: a modal sheet lives in a second
     * window whose view is reachable only through one of its semantics nodes.
     */
    private fun capture(name: String, expectedWidth: Int = 390, overlay: SemanticsNodeInteraction? = null) {
        val overlayView = overlay?.let {
            (requireNotNull(it.fetchSemanticsNode().root) as ViewRootForTest).view.rootView
        }
        val bitmap = compose.runOnIdle {
            val decor = contentView.rootView
            assertTrue("Screenshot view must be attached and laid out", decor.isAttachedToWindow && decor.isLaidOut)
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                decor.draw(canvas)
                overlayView?.draw(canvas)
            }
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

    private object NoopSaverScope : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private companion object {
        const val PUBKEY = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        const val NSEC = "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0"
        const val NEW_PUBKEY = "c0ffee0000000000000000000000000000000000000000000000000000000001"
        val WORDS =
            listOf(
                "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
                "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
                "acoustic", "acquire", "across", "act", "action", "actor", "actress", "zoo"
            )

        fun fixtureState() = SettingsUiState(
            npub = PUBKEY,
            displayName = "Priya",
            pendingOutbox = 3,
            stuckOutbox = 1,
            giftWrapEnabled = true,
            themeMode = ThemeMode.LIGHT,
            appVersion = "1.0.0",
            isDebugBuild = true
        )
    }
}
