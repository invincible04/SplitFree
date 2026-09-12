package com.splitfree.ui.screens.group

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.splitfree.R
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class CreateGroupContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var name by mutableStateOf("")
    private var showRelays by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var isCreating by mutableStateOf(false)
    private var relays by mutableStateOf(CreateGroupRelays(relays = RelayDefaults.DEFAULT_RELAYS))
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    @Test
    fun `create is disabled until a name is entered and forwards edits`() {
        val names = mutableListOf<String>()
        render(onName = { names += it })

        compose.onNodeWithTag("create_group_submit").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("create_group_name").assertIsDisplayed().performTextReplacement("Weekend away")
        compose.runOnIdle {
            assertEquals(listOf("Weekend away"), names)
            name = "Weekend away"
        }

        compose.onNodeWithTag("create_group_submit").assertIsEnabled()
        compose.onNodeWithText(text(R.string.create_group_eyebrow).uppercase()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.create_group_headline)).assertIsDisplayed()
    }

    @Test
    fun `whitespace only names do not enable create`() {
        name = "   "
        render()

        compose.onNodeWithTag("create_group_submit").assertIsNotEnabled()
    }

    @Test
    fun `create invokes onCreate once and shows a loading ring while creating`() {
        var creates = 0
        name = "Trip"
        render(onCreate = { creates++ })

        compose.onNodeWithTag("create_group_submit").performClick()
        compose.runOnIdle {
            assertEquals(1, creates)
            isCreating = true
        }

        compose.onNodeWithTag("create_group_submit").assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(hasContentDescription(text(R.string.cd_loading))).assertIsDisplayed()
        compose.onNode(hasContentDescription(text(R.string.back))).assertIsNotEnabled()
    }

    @Test
    fun `relay section expands into the editor and reports its state`() {
        var toggles = 0
        render(onToggleRelays = {
            toggles++
            showRelays = !showRelays
        })

        compose.onNodeWithTag("create_group_relays_panel").assertDoesNotExist()
        compose.onNodeWithTag("create_group_relays_toggle").assertIsDisplayed()
            .assertTextContains(text(R.string.sync_relays))
            .assertTextContains(text(R.string.relay_default_configuration))
            .assert(hasStateDescription(text(R.string.cd_collapsed)))
            .performClick()

        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithTag("create_group_relays_toggle").assert(hasStateDescription(text(R.string.cd_expanded)))
        compose.onNodeWithTag("create_group_relays_panel").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.relay_section_hint)).assertIsDisplayed()
        compose.onNodeWithText(RelayDefaults.DEFAULT_RELAYS.first().removePrefix("wss://"), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `custom relay lists show a count instead of default configuration`() {
        relays = CreateGroupRelays(
            relays = RelayDefaults.DEFAULT_RELAYS + "wss://relay.example",
            statuses = mapOf("wss://relay.example" to RelayCheckStatus.ONLINE)
        )
        render()

        compose.onNodeWithTag("create_group_relays_toggle")
            .assertTextContains(plural(R.plurals.relays_count, RelayDefaults.DEFAULT_RELAYS.size + 1))
    }

    @Test
    fun `errors are announced inline under the form`() {
        name = "Trip"
        error = "Could not create group"
        render()

        compose.onNodeWithTag("create_group_error").assertIsDisplayed().assertTextContains("Could not create group")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `back button calls onBack`() {
        var backs = 0
        render(onBack = { backs++ })

        compose.onNode(hasContentDescription(text(R.string.back))).performClick()

        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the create screen`() {
        render()
        capture("create-light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark fixture captures the create screen`() {
        render(dark = true)
        capture("create-dark")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `360dp at 200 percent text keeps the create button reachable with relays open`() {
        RuntimeEnvironment.setFontScale(2f)
        name = "Weekend away"
        showRelays = true
        render()
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }

        compose.onNodeWithTag("create_group_submit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("create_group_relays_toggle").assertIsDisplayed()
        capture("create-font200", expectedWidth = 360)
    }

    private fun render(
        onName: (String) -> Unit = { name = it },
        onToggleRelays: () -> Unit = { showRelays = !showRelays },
        onCreate: () -> Unit = {},
        onBack: () -> Unit = {},
        dark: Boolean = false
    ) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            SplitFreeTheme(darkTheme = dark) {
                CreateGroupContent(
                    name = name,
                    onName = onName,
                    relays = relays,
                    showRelays = showRelays,
                    onToggleRelays = onToggleRelays,
                    error = error,
                    isCreating = isCreating,
                    onCreate = onCreate,
                    onBack = onBack
                )
            }
        }
    }

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun plural(resource: Int, count: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(resource, count, count)

    private fun capture(name: String, expectedWidth: Int = 390) {
        compose.onNodeWithTag("create_group_submit").assertIsDisplayed()
        // Compose captureToImage waits for a window redraw that Robolectric does not schedule.
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
