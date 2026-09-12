package com.splitfree.ui.screens.onboarding

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.splitfree.R
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.ImportStatus
import java.io.File
import org.junit.Assert.assertEquals
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
class OnboardingContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(OnboardingUiState())
    private var step by mutableStateOf(OnboardingStep.Welcome)
    private var sheet by mutableStateOf<OnboardingSheet?>(null)
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    // --- Welcome ------------------------------------------------------------------------------------------

    @Test
    fun `welcome shows the pitch, three trust pills and both entry points`() {
        render()

        compose.onNodeWithText(text(R.string.onboarding_eyebrow).uppercase()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_subtitle)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.chip_encrypted)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.chip_decentralized)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.chip_free)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_get_started").assertIsDisplayed().assertIsEnabled()
            .assertTextContains(text(R.string.get_started))
        compose.onNodeWithTag("onboarding_restore_link").assertIsDisplayed()
            .assertTextContains(text(R.string.existing_key))
        compose.onNodeWithTag("onboarding_art").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_secret_input").assertDoesNotExist()
    }

    @Test
    fun `get started opens the name sheet and continue hands the typed name to generateIdentity`() {
        val names = mutableListOf<String>()
        render(OnboardingActions(generateIdentity = { names += it }))

        compose.onNodeWithTag("onboarding_sheet_name").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_get_started").performClick()

        compose.runOnIdle { assertEquals(OnboardingSheet.Name, sheet) }
        inSheet(hasText(text(R.string.onboarding_name_sheet_title))).assertIsDisplayed()
        inSheet(hasText(text(R.string.onboarding_name_note))).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_name_input").assertIsDisplayed().performTextReplacement("Priya")
        compose.onNodeWithTag("onboarding_name_continue").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(listOf("Priya"), names) }
    }

    @Test
    fun `continue without a name still generates an identity with an empty name`() {
        val names = mutableListOf<String>()
        sheet = OnboardingSheet.Name
        render(OnboardingActions(generateIdentity = { names += it }))

        compose.onNodeWithTag("onboarding_name_continue").performClick()

        compose.runOnIdle { assertEquals(listOf(""), names) }
    }

    @Test
    fun `closing the name sheet clears it`() {
        sheet = OnboardingSheet.Name
        render()

        inSheet(hasContentDescription(text(R.string.cd_close))).performClick()

        compose.runOnIdle { assertNull(sheet) }
    }

    @Test
    fun `restore link moves to the key pane`() {
        render()

        compose.onNodeWithTag("onboarding_restore_link").performClick()

        compose.runOnIdle { assertEquals(OnboardingStep.RestoreKey, step) }
        compose.onNodeWithTag("onboarding_secret_input").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_get_started").assertDoesNotExist()
    }

    // --- Restore: key -------------------------------------------------------------------------------------

    @Test
    fun `key pane shows the secret field and imports the typed secret`() {
        val imports = mutableListOf<String>()
        step = OnboardingStep.RestoreKey
        render(OnboardingActions(importKey = { imports += it }))

        compose.onNodeWithText(text(R.string.onboarding_restore_eyebrow).uppercase()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_restore_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_restore_private_hint)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_secret_input").assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("onboarding_secret_toggle").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_import_key").assertIsDisplayed().assertIsNotEnabled()

        compose.onNodeWithTag("onboarding_secret_input").performTextReplacement("nsec1examplekey")
        compose.onNodeWithTag("onboarding_import_key").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf("nsec1examplekey"), imports) }
    }

    @Test
    fun `key pane announces the import error inline and clears it when the secret changes`() {
        var clears = 0
        step = OnboardingStep.RestoreKey
        state = OnboardingUiState(error = UiMessage.Res(R.string.invalid_key_input))
        render(OnboardingActions(clearError = { clears++ }))

        compose.onNodeWithText(text(R.string.invalid_key_input)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_secret_input")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("onboarding_secret_error", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText(text(R.string.key_supporting_text)).assertDoesNotExist()

        compose.onNodeWithTag("onboarding_secret_input").performTextReplacement("x")

        compose.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun `back on the key pane returns to welcome and drops the error`() {
        var clears = 0
        step = OnboardingStep.RestoreKey
        render(OnboardingActions(clearError = { clears++ }))

        compose.onNodeWithTag("onboarding_restore_back").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(OnboardingStep.Welcome, step)
            assertEquals(1, clears)
            step = OnboardingStep.RestoreKey
        }

        compose.onNodeWithTag("onboarding_restore_cancel").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(OnboardingStep.Welcome, step)
            assertEquals(2, clears)
        }
    }

    // --- Restore: backup ----------------------------------------------------------------------------------

    @Test
    fun `backup pane offers the file picker and both exits`() {
        var picks = 0
        var completes = 0
        step = OnboardingStep.RestoreBackup
        state = OnboardingUiState(keyImported = true)
        render(OnboardingActions(pickBackup = { picks++ }, complete = { completes++ }))

        compose.onNodeWithText(text(R.string.key_imported)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_backup_hint)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_status").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_pick_backup").assertIsDisplayed().performClick()
        compose.onNodeWithTag("onboarding_continue").assertIsDisplayed().performClick()
        compose.onNodeWithTag("onboarding_skip").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(1, picks)
            assertEquals(2, completes)
        }
    }

    @Test
    fun `backup pane holds the exits while importing`() {
        step = OnboardingStep.RestoreBackup
        state = OnboardingUiState(keyImported = true, importing = true)
        render()

        compose.onNodeWithTag("onboarding_importing").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.importing_backup)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_pick_backup").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_continue").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_skip").assertIsNotEnabled()
    }

    @Test
    fun `backup pane reports a restored count and a failure with its reason`() {
        step = OnboardingStep.RestoreBackup
        state = OnboardingUiState(keyImported = true, importStatus = ImportStatus.Restored(12))
        render()

        card("onboarding_status", plural(R.plurals.backup_restored_events, 12)).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

        state = state.copy(importStatus = ImportStatus.Failed("Backup file is too large"))
        card("onboarding_status", text(R.string.backup_import_failed_reason, "Backup file is too large"))
            .assertIsDisplayed()

        state = state.copy(importStatus = ImportStatus.Failed(null))
        card("onboarding_status", text(R.string.backup_import_failed)).assertIsDisplayed()
    }

    // --- Screenshots --------------------------------------------------------------------------------------

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the welcome pane`() {
        render()
        capture("onboarding-light", anchor = "onboarding_get_started")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark fixture captures the welcome pane`() {
        render(dark = true)
        capture("onboarding-dark", anchor = "onboarding_get_started")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `360dp at 200 percent text keeps get started reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        render()
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }

        compose.onNodeWithTag("onboarding_get_started").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("onboarding_restore_link").assertIsDisplayed()
        capture("onboarding-font200", anchor = "onboarding_get_started", expectedWidth = 360)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the key pane with an error`() {
        step = OnboardingStep.RestoreKey
        state = OnboardingUiState(error = UiMessage.Res(R.string.invalid_key_input))
        render()
        compose.onNodeWithTag("onboarding_secret_input").performTextReplacement("abandon ability able about")
        capture("onboarding-restore-light", anchor = "onboarding_import_key")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the backup pane after a restore`() {
        step = OnboardingStep.RestoreBackup
        state = OnboardingUiState(keyImported = true, importStatus = ImportStatus.Restored(48))
        render()
        capture("onboarding-backup-light", anchor = "onboarding_continue")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the name sheet`() {
        sheet = OnboardingSheet.Name
        render()
        compose.onNodeWithTag("onboarding_name_input").performTextReplacement("Priya")
        capture(
            "onboarding-sheet-name-light",
            anchor = "onboarding_get_started",
            overlay = compose.onNodeWithTag("onboarding_sheet_name")
        )
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private fun render(actions: OnboardingActions = OnboardingActions(), dark: Boolean = false) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            SplitFreeTheme(darkTheme = dark) {
                OnboardingContent(
                    state = state,
                    step = step,
                    onStep = { step = it },
                    sheet = sheet,
                    onSheet = { sheet = it },
                    actions = actions
                )
            }
        }
    }

    /** A node inside the name sheet; the sheet lives in its own window so plain text lookups also see it. */
    private fun inSheet(matcher: SemanticsMatcher): SemanticsNodeInteraction =
        compose.onNode(matcher and hasAnyAncestor(hasTestTag("onboarding_sheet_name")))

    /** The notice card tagged [tag] whose body says [text] (cards do not merge their text into the tag node). */
    private fun card(tag: String, text: String): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text)))

    private fun hasContentDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(value))

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun plural(resource: Int, count: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(resource, count, count)

    /**
     * Draws the main window and, for the sheet, its own window on top: a modal sheet lives in a second window
     * whose view is reachable only through one of its semantics nodes.
     */
    private fun capture(
        name: String,
        anchor: String,
        expectedWidth: Int = 390,
        overlay: SemanticsNodeInteraction? = null
    ) {
        compose.onNodeWithTag(anchor).assertIsDisplayed()
        val overlayView = overlay?.let {
            (requireNotNull(it.fetchSemanticsNode().root) as ViewRootForTest).view.rootView
        }
        // Compose captureToImage waits for a window redraw that Robolectric does not schedule.
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
}
