package com.splitfree.ui.screens.nearby

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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.splitfree.R
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.NearbySyncUiState
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
class NearbySyncContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(NearbySyncUiState())
    private var permissions by mutableStateOf(NearbyPermissionState())
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    // --- States -------------------------------------------------------------------------------------------

    @Test
    fun `idle shows the resting radar, the idle headline and a scan button that starts scanning`() {
        var scans = 0
        render(NearbyActions(startScan = { scans++ }))

        compose.onNodeWithTag("nearby_headline").assertIsDisplayed()
            .assertTextEquals(text(R.string.nearby_headline_idle))
        compose.onNodeWithText(text(R.string.nearby_explanation)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nearby_footer_note)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_orbit_idle").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cd_bluetooth_icon)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_progress").assertDoesNotExist()
        compose.onNodeWithTag("nearby_status_hint").assertDoesNotExist()
        compose.onNodeWithTag("nearby_permission_warning").assertDoesNotExist()

        compose.onNodeWithTag("nearby_primary").assertIsDisplayed().assertIsEnabled()
            .assertTextContains(text(R.string.scan_for_nearby))
            .performClick()

        compose.runOnIdle { assertEquals(1, scans) }
    }

    @Test
    fun `scanning swaps in the pulsing radar, the scanning headline, the status hint and a stop button`() {
        var stops = 0
        state = NearbySyncUiState(scanning = true, status = UiMessage.Res(R.string.nearby_scanning))
        render(NearbyActions(stopScan = { stops++ }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_scanning))
        compose.onNodeWithTag("nearby_orbit_scanning").assertIsDisplayed()
        card("nearby_status_hint", text(R.string.nearby_scanning)).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithTag("nearby_status_warning").assertDoesNotExist()

        compose.onNodeWithTag("nearby_primary").assertIsDisplayed()
            .assertTextContains(text(R.string.stop_scanning))
            .performClick()

        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun `a found peer gets a card whose sync button connects to that endpoint`() {
        val connects = mutableListOf<String>()
        state =
            NearbySyncUiState(
                scanning = true,
                peers = listOf(NearbyPeer("ep-1", "a1b2c3d4")),
                status = UiMessage.Res(R.string.nearby_scanning)
            )
        render(NearbyActions(connectToPeer = { connects += it }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(plural(R.plurals.nearby_headline_found, 1))
        // The "ask the other person to open Nearby sync" hint is moot once someone has been found.
        compose.onNodeWithTag("nearby_status_hint").assertDoesNotExist()
        compose.onNodeWithTag("nearby_peer_ep-1").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.peer_name, "a1b2c3d4")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tap_sync)).assertIsDisplayed()
        compose.onNodeWithContentDescription("a1b2c3d4").assertIsDisplayed()

        compose.onNodeWithTag("nearby_sync_ep-1").assertIsDisplayed().assertIsEnabled()
            .assertTextContains(text(R.string.sync))
            .performClick()

        compose.runOnIdle { assertEquals(listOf("ep-1"), connects) }
    }

    @Test
    fun `several peers are counted in the headline`() {
        state =
            NearbySyncUiState(
                scanning = true,
                peers = listOf(NearbyPeer("ep-1", "a1b2c3d4"), NearbyPeer("ep-2", "e5f6a7b8"))
            )
        render()

        compose.onNodeWithTag("nearby_headline").assertTextEquals(plural(R.plurals.nearby_headline_found, 2))
        compose.onNodeWithTag("nearby_peer_ep-1").assertIsDisplayed()
        compose.onNodeWithTag("nearby_peer_ep-2").assertIsDisplayed()
    }

    @Test
    fun `syncing shows the progress line, the syncing headline and disables the peer button`() {
        state =
            NearbySyncUiState(
                scanning = true,
                peers = listOf(NearbyPeer("ep-1", "a1b2c3d4")),
                syncing = true,
                status = UiMessage.Res(R.string.nearby_connecting)
            )
        render()

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_syncing))
        compose.onNodeWithTag("nearby_progress").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cd_sync_icon)).assertIsDisplayed()
        card("nearby_status_hint", text(R.string.nearby_connecting)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_sync_ep-1").assertIsNotEnabled()
    }

    @Test
    fun `failure statuses render as warnings and the disconnect line does not claim completion`() {
        state = NearbySyncUiState(status = UiMessage.Res(R.string.nearby_peer_auth_failed))
        render()

        card("nearby_status_warning", text(R.string.nearby_peer_auth_failed)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_status_hint").assertDoesNotExist()

        state = NearbySyncUiState(status = UiMessage.Res(R.string.nearby_ble_error, "discovery", "denied"))
        card("nearby_status_warning", text(R.string.nearby_ble_error, "discovery", "denied")).assertIsDisplayed()

        state = NearbySyncUiState(status = UiMessage.Res(R.string.nearby_sync_complete))
        card("nearby_status_hint", text(R.string.nearby_sync_complete)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_status_warning").assertDoesNotExist()
        assertTrue(
            "Disconnect copy must not promise a completed sync",
            !text(R.string.nearby_sync_complete).contains("complete", ignoreCase = true)
        )
    }

    // --- Permissions --------------------------------------------------------------------------------------

    @Test
    fun `missing permissions relabel the button, explain in a warning and still route through startScan`() {
        var scans = 0
        permissions = NearbyPermissionState(granted = false, bluetoothEnabled = false)
        render(NearbyActions(startScan = { scans++ }))

        card("nearby_permission_warning", text(R.string.permissions_hint)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_primary").assertIsEnabled()
            .assertTextContains(text(R.string.permissions_required))
            .performClick()

        compose.runOnIdle { assertEquals(1, scans) }
    }

    @Test
    fun `bluetooth off asks to turn it on`() {
        permissions = NearbyPermissionState(granted = true, bluetoothEnabled = false)
        render()

        compose.onNodeWithTag("nearby_permission_warning").assertDoesNotExist()
        compose.onNodeWithTag("nearby_primary").assertIsEnabled().assertTextContains(text(R.string.enable_bluetooth))
    }

    @Test
    fun `no bluetooth adapter disables the button and says so`() {
        permissions = NearbyPermissionState(granted = true, bluetoothEnabled = false, bluetoothAvailable = false)
        render()

        card("nearby_permission_warning", text(R.string.nearby_bluetooth_unavailable_hint)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_primary").assertIsNotEnabled()
            .assertTextContains(text(R.string.bluetooth_unavailable))
    }

    @Test
    fun `back button calls back`() {
        var backs = 0
        render(NearbyActions(back = { backs++ }))

        compose.onNodeWithTag("nearby_back").assertContentDescriptionEquals(text(R.string.back)).performClick()

        compose.runOnIdle { assertEquals(1, backs) }
    }

    // --- Screenshots --------------------------------------------------------------------------------------

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the idle screen`() {
        render()
        capture("nearby-idle-light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark fixture captures the idle screen`() {
        render(dark = true)
        capture("nearby-idle-dark")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the scanning screen mid-pulse`() {
        // Infinite transitions are cancelled under the auto-advancing clock; drive it by hand so the rings
        // are caught part-way through a pulse rather than at their start value.
        compose.mainClock.autoAdvance = false
        state = NearbySyncUiState(scanning = true, status = UiMessage.Res(R.string.nearby_scanning))
        render()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nearby_orbit_scanning").assertIsDisplayed()
        capture("nearby-scanning-light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures a found peer`() {
        state =
            NearbySyncUiState(
                scanning = true,
                peers = listOf(NearbyPeer("ep-1", "a1b2c3d4")),
                status = UiMessage.Res(R.string.nearby_scanning)
            )
        render()
        capture("nearby-peer-light")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `360dp at 200 percent text keeps the scan button reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        state = NearbySyncUiState(peers = listOf(NearbyPeer("ep-1", "a1b2c3d4")))
        render()
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }

        compose.onNodeWithTag("nearby_primary").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("nearby_sync_ep-1").assertIsDisplayed()
        capture("nearby-idle-font200", expectedWidth = 360)
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private fun render(actions: NearbyActions = NearbyActions(), dark: Boolean = false) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            SplitFreeTheme(darkTheme = dark) {
                NearbySyncContent(state = state, permissions = permissions, actions = actions)
            }
        }
    }

    /** The notice card tagged [tag] whose body says [text] (cards do not merge their text into the tag node). */
    private fun card(tag: String, text: String): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text)))

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun plural(resource: Int, count: Int): String =
        RuntimeEnvironment.getApplication().resources.getQuantityString(resource, count, count)

    private fun capture(name: String, expectedWidth: Int = 390) {
        compose.onNodeWithTag("nearby_primary").assertIsDisplayed()
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
