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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.splitfree.R
import com.splitfree.sync.nearby.AttemptState
import com.splitfree.sync.nearby.CapabilityState
import com.splitfree.sync.nearby.PeerPhase
import com.splitfree.sync.nearby.PeerProgress
import com.splitfree.sync.nearby.RadioFailureKind
import com.splitfree.sync.nearby.RadioOutcome
import com.splitfree.sync.nearby.RunFailure
import com.splitfree.sync.nearby.RunPhase
import com.splitfree.sync.nearby.TransferStats
import com.splitfree.sync.nearby.TransportFault
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.Headline
import com.splitfree.ui.viewmodels.NearbyNotice
import com.splitfree.ui.viewmodels.NearbyPeerRow
import com.splitfree.ui.viewmodels.NearbySyncUiState
import com.splitfree.ui.viewmodels.NoticeAction
import com.splitfree.ui.viewmodels.RowAction
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

/** Renders supplied UI states and checks callbacks; these fixtures do not drive the route lifecycle or radios. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w390dp-h844dp-mdpi")
class NearbySyncContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(NearbySyncUiState())
    private var permissions by mutableStateOf(NearbyPermissionState())
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    // --- Run states ---------------------------------------------------------------------------------------

    @Test
    fun `stopped by the user shows the resting radar, the idle headline and a start button`() {
        var starts = 0
        state = NearbySyncUiState(enabled = false)
        render(NearbyActions(start = { starts++ }))

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
            .assertTextContains(text(R.string.nearby_start))
            .performClick()

        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun `searching shows the pulsing radar, the searching headline, the hint and a stop button`() {
        var stops = 0
        state = searching()
        render(NearbyActions(stop = { stops++ }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_searching))
        compose.onNodeWithTag("nearby_orbit_scanning").assertIsDisplayed()
        card("nearby_status_hint", text(R.string.nearby_searching_hint)).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithTag("nearby_status_warning").assertDoesNotExist()
        compose.onNodeWithTag("nearby_notice_action").assertDoesNotExist()

        compose.onNodeWithTag("nearby_primary").assertIsDisplayed()
            .assertTextContains(text(R.string.nearby_stop))
            .performClick()

        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun `starting and stopping label the dock accordingly`() {
        state = NearbySyncUiState(phase = RunPhase.STARTING, headline = Headline.STARTING)
        render()
        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_starting))
        compose.onNodeWithTag("nearby_primary").assertIsEnabled().assertTextContains(text(R.string.nearby_stop))

        state = NearbySyncUiState(phase = RunPhase.STOPPING, headline = Headline.IDLE)
        compose.onNodeWithTag("nearby_primary").assertIsNotEnabled().assertTextContains(text(R.string.nearby_stopping))
    }

    @Test
    fun `a long search shows the long-search hint`() {
        state =
            searching().copy(searchingLong = true, notice = NearbyNotice(UiMessage.Res(R.string.nearby_search_long)))
        render()

        card("nearby_status_hint", text(R.string.nearby_search_long)).assertIsDisplayed()
    }

    @Test
    fun `a failed run shows the failed headline, a warning and a try-again button that starts`() {
        var starts = 0
        state =
            NearbySyncUiState(
                phase = RunPhase.FAILED,
                runFailure = RunFailure.Transport(TransportFault("lifecycle", "client disconnected", 1)),
                headline = Headline.FAILED,
                notice =
                NearbyNotice(
                    UiMessage.Res(R.string.nearby_notice_transport, "client disconnected"),
                    NoticeAction.RETRY,
                    warning = true
                )
            )
        render(NearbyActions(start = { starts++ }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_failed))
        card("nearby_status_warning", text(R.string.nearby_notice_transport, "client disconnected")).assertIsDisplayed()
        // The dock already offers the retry; the notice does not repeat it.
        compose.onNodeWithTag("nearby_notice_action").assertDoesNotExist()
        compose.onNodeWithTag("nearby_orbit_idle").assertIsDisplayed()

        compose.onNodeWithTag("nearby_primary").assertIsEnabled()
            .assertTextContains(text(R.string.nearby_try_again))
            .performClick()

        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun `a location-setting failure offers the location settings action`() {
        var opens = 0
        state =
            searching().copy(
                discovery =
                CapabilityState.Failed(RadioOutcome.Failure(RadioFailureKind.LOCATION_SETTING, 8025, "off")),
                headline = Headline.IDLE,
                notice =
                NearbyNotice(
                    UiMessage.Res(R.string.nearby_notice_location),
                    NoticeAction.OPEN_LOCATION_SETTINGS,
                    warning = true
                )
            )
        render(NearbyActions(openLocationSettings = { opens++ }))

        card("nearby_status_warning", text(R.string.nearby_notice_location)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_notice_action").assertIsDisplayed()
            .assertTextContains(text(R.string.nearby_notice_location_action))
            .performClick()

        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun `a service failure during an active run offers a retry that starts`() {
        var starts = 0
        state =
            searching().copy(
                notice = NearbyNotice(UiMessage.Res(R.string.nearby_notice_service), NoticeAction.RETRY, warning = true)
            )
        render(NearbyActions(start = { starts++ }))

        compose.onNodeWithTag("nearby_notice_action").assertTextContains(text(R.string.nearby_try_again)).performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun `a runtime permission failure routes through the permission request`() {
        var requests = 0
        state =
            searching().copy(
                notice =
                NearbyNotice(
                    UiMessage.Res(R.string.nearby_notice_permission),
                    NoticeAction.GRANT_PERMISSION,
                    warning = true
                )
            )
        render(NearbyActions(requestPermissions = { requests++ }))

        compose.onNodeWithTag("nearby_notice_action")
            .assertTextContains(text(R.string.permissions_required))
            .performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    // --- Rows ---------------------------------------------------------------------------------------------

    @Test
    fun `a found peer gets a card whose sync button connects to that endpoint`() {
        val connects = mutableListOf<String>()
        state = found(row("ep-1", "a1b2c3d4", RowAction.SYNC))
        render(NearbyActions(connect = { connects += it }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(plural(R.plurals.nearby_headline_found, 1))
        compose.onNodeWithTag("nearby_status_hint").assertDoesNotExist()
        compose.onNodeWithTag("nearby_peer_ep-1").assertIsDisplayed()
        compose.onNodeWithText("a1b2c3d4").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tap_sync)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_verified_ep-1").assertDoesNotExist()
        compose.onNodeWithContentDescription("a1b2c3d4").assertIsDisplayed()

        compose.onNodeWithTag("nearby_sync_ep-1").assertIsDisplayed().assertIsEnabled()
            .assertTextContains(text(R.string.sync))
            .assertContentDescriptionEquals(text(R.string.nearby_cd_sync_with, "a1b2c3d4"))
            .performClick()

        compose.runOnIdle { assertEquals(listOf("ep-1"), connects) }
    }

    @Test
    fun `several peers are counted in the headline`() {
        state = found(row("ep-1", "a1b2c3d4", RowAction.SYNC), row("ep-2", "e5f6a7b8", RowAction.SYNC))
        render()

        compose.onNodeWithTag("nearby_headline").assertTextEquals(plural(R.plurals.nearby_headline_found, 2))
        compose.onNodeWithTag("nearby_peer_ep-1").assertIsDisplayed()
        compose.onNodeWithTag("nearby_peer_ep-2").assertIsDisplayed()
    }

    @Test
    fun `a requesting row says connecting and offers cancel for that endpoint`() {
        val cancels = mutableListOf<String>()
        state =
            found(
                row(
                    "ep-1",
                    "a1b2c3d4",
                    RowAction.CONNECTING,
                    attempt = AttemptState.Requesting(1),
                    status = UiMessage.Res(R.string.nearby_connecting)
                )
            ).copy(headline = Headline.CONNECTING)
        render(NearbyActions(cancelAttempt = { cancels += it }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_connecting))
        compose.onNodeWithContentDescription(text(R.string.cd_sync_icon)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_row_status_ep-1").assertTextEquals(text(R.string.nearby_connecting))
        compose.onNodeWithTag("nearby_sync_ep-1").assertDoesNotExist()
        compose.onNodeWithTag("nearby_cancel_ep-1").assertIsDisplayed()
            .assertTextContains(text(R.string.cancel))
            .assertContentDescriptionEquals(text(R.string.nearby_cd_cancel_for, "a1b2c3d4"))
            .performClick()

        compose.runOnIdle { assertEquals(listOf("ep-1"), cancels) }
    }

    @Test
    fun `a failed but still discovered row offers retry with the reason`() {
        val connects = mutableListOf<String>()
        val failure = RadioOutcome.Failure(RadioFailureKind.ENDPOINT, 8012, "rejected")
        state =
            found(
                row(
                    "ep-1",
                    "a1b2c3d4",
                    RowAction.RETRY,
                    attempt = AttemptState.Failed(1, failure),
                    status = UiMessage.Res(R.string.nearby_connection_failed, "rejected")
                )
            )
        render(NearbyActions(connect = { connects += it }))

        compose.onNodeWithTag("nearby_row_status_ep-1")
            .assertTextEquals(text(R.string.nearby_connection_failed, "rejected"))
        compose.onNodeWithTag("nearby_sync_ep-1").assertIsDisplayed()
            .assertTextContains(text(R.string.nearby_retry))
            .assertContentDescriptionEquals(text(R.string.nearby_cd_retry_with, "a1b2c3d4"))
            .performClick()

        compose.runOnIdle { assertEquals(listOf("ep-1"), connects) }
    }

    @Test
    fun `syncing shows the progress line, the syncing headline and a busy row without buttons`() {
        val progress =
            progress(
                "ep-1",
                PeerPhase.TRANSFERRING,
                pubkey = "a1b2c3d4e5f6a7b8",
                stats = TransferStats(sent = 2, applied = 5)
            )
        state =
            found(
                row(
                    "ep-1",
                    "a1b2c3d4",
                    RowAction.BUSY,
                    attempt = AttemptState.Connected(1, incoming = false),
                    status = UiMessage.Res(R.string.nearby_transferring, 5, 2),
                    progress = progress
                )
            ).copy(headline = Headline.SYNCING, progressVisible = true)
        render()

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_syncing))
        compose.onNodeWithTag("nearby_progress").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.cd_sync_icon)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_row_status_ep-1").assertTextEquals(text(R.string.nearby_transferring, 5, 2))
        compose.onNodeWithTag("nearby_verified_ep-1").assertIsDisplayed()
            .assertTextEquals(text(R.string.nearby_verified_identity))
        compose.onNodeWithTag("nearby_sync_ep-1").assertDoesNotExist()
        compose.onNodeWithTag("nearby_cancel_ep-1").assertDoesNotExist()
    }

    @Test
    fun `one peer up to date and another transferring read as syncing with the progress line`() {
        state =
            found(
                row(
                    "ep-1",
                    "a1b2c3d4",
                    RowAction.DONE,
                    attempt = AttemptState.Connected(1, incoming = false),
                    status = UiMessage.Res(R.string.nearby_up_to_date, 4, 1),
                    progress = progress("ep-1", PeerPhase.UP_TO_DATE, pubkey = "a1b2c3d4e5f6a7b8")
                ),
                row(
                    "ep-2",
                    "e5f6a7b8",
                    RowAction.BUSY,
                    attempt = AttemptState.Connected(2, incoming = false),
                    status = UiMessage.Res(R.string.nearby_transferring, 1, 0),
                    progress = progress("ep-2", PeerPhase.TRANSFERRING, pubkey = "e5f6a7b8c9d0e1f2")
                )
            ).copy(headline = Headline.SYNCING, progressVisible = true)
        render()

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_syncing))
        compose.onNodeWithTag("nearby_progress").assertIsDisplayed()
        compose.onNodeWithTag("nearby_row_status_ep-1").assertTextEquals(text(R.string.nearby_up_to_date, 4, 1))
        compose.onNodeWithTag("nearby_row_status_ep-2").assertTextEquals(text(R.string.nearby_transferring, 1, 0))
    }

    @Test
    fun `a connected up-to-date row shows the result and no sync button`() {
        state =
            found(
                row(
                    "ep-1",
                    "a1b2c3d4",
                    RowAction.DONE,
                    attempt = AttemptState.Connected(1, incoming = false),
                    status = UiMessage.Res(R.string.nearby_up_to_date, 4, 1),
                    progress = progress("ep-1", PeerPhase.UP_TO_DATE, pubkey = "a1b2c3d4e5f6a7b8")
                )
            )
        render()

        compose.onNodeWithTag("nearby_row_status_ep-1").assertTextEquals(text(R.string.nearby_up_to_date, 4, 1))
        compose.onNodeWithTag("nearby_verified_ep-1").assertIsDisplayed()
        compose.onNodeWithTag("nearby_sync_ep-1").assertDoesNotExist()
        compose.onNodeWithTag("nearby_cancel_ep-1").assertDoesNotExist()
        compose.onNodeWithTag("nearby_progress").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.tap_sync)).assertDoesNotExist()
    }

    @Test
    fun `an incoming connection never discovered renders with the generic name`() {
        state =
            found(
                row(
                    "ep-9",
                    null,
                    RowAction.BUSY,
                    attempt = AttemptState.Connected(null, incoming = true),
                    discovered = false,
                    status = UiMessage.Res(R.string.nearby_authenticating),
                    progress = progress("ep-9", PeerPhase.AUTHENTICATING)
                )
            ).copy(headline = Headline.SYNCING, progressVisible = true)
        render()

        compose.onNodeWithTag("nearby_peer_ep-9").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nearby_phone)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_verified_ep-9").assertDoesNotExist()
        compose.onNodeWithTag("nearby_row_status_ep-9").assertTextEquals(text(R.string.nearby_authenticating))
        compose.onNodeWithTag("nearby_sync_ep-9").assertDoesNotExist()
    }

    @Test
    fun `the verified caption names the identity only and never claims membership before or after authorization`() {
        // Model a verified key before group authorization and after refusal: neither caption may claim membership.
        // The supplied pubkeys stand in for protocol verification; this test checks presentation only.
        state =
            found(
                row(
                    "ep-1",
                    "a1b2c3d4",
                    RowAction.BUSY,
                    attempt = AttemptState.Connected(1, incoming = false),
                    status = UiMessage.Res(R.string.nearby_opening_group),
                    progress = progress("ep-1", PeerPhase.OPENING_GROUP, pubkey = "a1b2c3d4e5f6a7b8")
                ),
                row(
                    "ep-2",
                    "e5f6a7b8",
                    RowAction.DONE,
                    attempt = AttemptState.Connected(2, incoming = false),
                    status = UiMessage.Res(R.string.nearby_unauthorized),
                    progress =
                    progress("ep-2", PeerPhase.UNAUTHORIZED, pubkey = "e5f6a7b8c9d0e1f2", closeReason = "unauthorized")
                )
            ).copy(headline = Headline.SYNCING, progressVisible = true)
        render()

        val caption = text(R.string.nearby_verified_identity)
        assertEquals("Verified identity", caption)
        assertFalse("The caption must not claim membership", caption.contains("member", ignoreCase = true))
        compose.onNodeWithTag("nearby_verified_ep-1").assertIsDisplayed().assertTextEquals(caption)
        compose.onNodeWithTag("nearby_verified_ep-2").assertIsDisplayed().assertTextEquals(caption)
        compose.onNodeWithTag("nearby_row_status_ep-2").assertTextEquals(text(R.string.nearby_unauthorized))

        compose.onAllNodesWithText("Verified member").assertCountEquals(0)
        compose.onAllNodesWithText("Verified member", substring = true, ignoreCase = true).assertCountEquals(0)
        // Only the status lines may mention membership: the caption and every other node stay silent on it.
        val membershipTags =
            compose.onAllNodes(hasText("member", substring = true, ignoreCase = true))
                .fetchSemanticsNodes()
                .map { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertTrue("Nodes mentioning membership: $membershipTags", membershipTags.isNotEmpty())
        assertTrue(
            "Only a row status line may mention membership, found $membershipTags",
            membershipTags.all { it != null && it.startsWith("nearby_row_status_") }
        )
    }

    @Test
    fun `recent results sit under their own heading without buttons`() {
        state =
            found(
                row("ep-2", "e5f6a7b8", RowAction.SYNC),
                row(
                    "ep-1",
                    null,
                    RowAction.NONE,
                    attempt = AttemptState.None,
                    discovered = false,
                    status = UiMessage.Res(R.string.nearby_sync_complete),
                    progress = progress("ep-1", PeerPhase.CLOSED, pubkey = "a1b2c3d4e5f6a7b8", closeReason = "stopped"),
                    recent = true
                )
            )
        render()

        compose.onNodeWithTag("nearby_recent").assertIsDisplayed()
        compose.onNodeWithTag("nearby_peer_ep-1").assertIsDisplayed()
        compose.onNodeWithTag("nearby_row_status_ep-1").assertTextEquals(text(R.string.nearby_sync_complete))
        compose.onNodeWithTag("nearby_sync_ep-1").assertDoesNotExist()
        compose.onNodeWithTag("nearby_sync_ep-2").assertIsDisplayed()
        assertTrue(
            "Disconnect copy must not promise a completed sync",
            !text(R.string.nearby_sync_complete).contains("complete", ignoreCase = true)
        )
    }

    @Test
    fun `every row button describes the peer it acts on`() {
        state =
            found(
                row("ep-1", "Anita", RowAction.SYNC),
                row("ep-2", "Bao", RowAction.CONNECTING, attempt = AttemptState.Requesting(2)),
                row("ep-3", "Chidi", RowAction.RETRY, attempt = AttemptState.Failed(3, null))
            )
        render()

        compose.onNodeWithTag("nearby_sync_ep-1")
            .assertContentDescriptionEquals(text(R.string.nearby_cd_sync_with, "Anita"))
        compose.onNodeWithTag("nearby_cancel_ep-2")
            .assertContentDescriptionEquals(text(R.string.nearby_cd_cancel_for, "Bao"))
        compose.onNodeWithTag("nearby_sync_ep-3")
            .assertContentDescriptionEquals(text(R.string.nearby_cd_retry_with, "Chidi"))
    }

    // --- Prerequisites ------------------------------------------------------------------------------------

    @Test
    fun `missing permissions relabel the button, explain in a warning and request on tap`() {
        var requests = 0
        permissions = NearbyPermissionState(granted = false, bluetoothEnabled = false)
        render(NearbyActions(requestPermissions = { requests++ }))

        card("nearby_permission_warning", text(R.string.permissions_hint)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_primary").assertIsEnabled()
            .assertTextContains(text(R.string.permissions_required))
            .performClick()

        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun `permanent denial offers the app settings instead of another prompt`() {
        var requests = 0
        var settings = 0
        permissions = NearbyPermissionState(granted = false, needsSettings = true)
        render(NearbyActions(requestPermissions = { requests++ }, openAppSettings = { settings++ }))

        card("nearby_permission_warning", text(R.string.nearby_permission_settings_hint)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_primary").assertIsEnabled()
            .assertTextContains(text(R.string.nearby_open_settings))
            .performClick()

        compose.runOnIdle {
            assertEquals(1, settings)
            assertEquals(0, requests)
        }
    }

    @Test
    fun `bluetooth off asks to turn it on from the button only`() {
        var enables = 0
        var starts = 0
        permissions = NearbyPermissionState(granted = true, bluetoothEnabled = false)
        render(NearbyActions(enableBluetooth = { enables++ }, start = { starts++ }))

        compose.onNodeWithTag("nearby_headline").assertTextEquals(text(R.string.nearby_headline_idle))
        compose.onNodeWithTag("nearby_orbit_idle").assertIsDisplayed()
        card("nearby_permission_warning", text(R.string.nearby_bluetooth_off_hint)).assertIsDisplayed()
        compose.onNodeWithTag("nearby_primary").assertIsEnabled()
            .assertTextContains(text(R.string.enable_bluetooth))
            .performClick()

        compose.runOnIdle {
            assertEquals(1, enables)
            assertEquals(0, starts)
        }
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
    fun `permission set follows the SDK with fine and coarse together on 31 and local network only from 37`() {
        val android12 = requiredNearbyPermissions(31)
        assertTrue(android12.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(android12.contains(android.Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(requiredNearbyPermissions(33).contains(android.Manifest.permission.NEARBY_WIFI_DEVICES))
        assertFalse(requiredNearbyPermissions(36).contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(requiredNearbyPermissions(37).contains("android.permission.ACCESS_LOCAL_NETWORK"))
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
        state = NearbySyncUiState(enabled = false)
        render()
        capture("nearby-idle-light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark fixture captures the idle screen`() {
        state = NearbySyncUiState(enabled = false)
        render(dark = true)
        capture("nearby-idle-dark")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the searching screen mid-pulse`() {
        // Drive the clock explicitly to capture a fixed point within the pulse, independent of idle synchronization.
        compose.mainClock.autoAdvance = false
        state = searching()
        render()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("nearby_orbit_scanning").assertIsDisplayed()
        capture("nearby-searching-light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures a found peer`() {
        state = found(row("ep-1", "a1b2c3d4", RowAction.SYNC))
        render()
        capture("nearby-peer-light")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `360dp at 200 percent text keeps the dock and the row button reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        state = found(row("ep-1", "a1b2c3d4", RowAction.SYNC), row("ep-2", "e5f6a7b8", RowAction.SYNC))
        render()
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }

        compose.onNodeWithTag("nearby_primary").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("nearby_sync_ep-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nearby_sync_ep-2").performScrollTo().assertIsDisplayed()
        capture("nearby-peer-font200", expectedWidth = 360)
    }

    // --- Fixtures -----------------------------------------------------------------------------------------

    private fun searching() = NearbySyncUiState(
        phase = RunPhase.ACTIVE,
        advertising = CapabilityState.Running,
        discovery = CapabilityState.Running,
        headline = Headline.SEARCHING,
        notice = NearbyNotice(UiMessage.Res(R.string.nearby_searching_hint))
    )

    private fun found(vararg rows: NearbyPeerRow) =
        searching().copy(headline = Headline.FOUND, notice = null, rows = rows.toList())

    private fun row(
        endpointId: String,
        name: String?,
        action: RowAction,
        attempt: AttemptState = AttemptState.None,
        discovered: Boolean = true,
        status: UiMessage? = null,
        progress: PeerProgress? = null,
        recent: Boolean = false
    ) = NearbyPeerRow(
        endpointId = endpointId,
        displayName = name?.let(UiMessage::Raw) ?: UiMessage.Res(R.string.nearby_phone),
        verifiedPubkey = progress?.peerPubkey,
        discovered = discovered,
        attempt = attempt,
        progress = progress,
        action = action,
        status = status,
        recent = recent
    )

    private fun progress(
        endpointId: String,
        phase: PeerPhase,
        pubkey: String? = null,
        stats: TransferStats = TransferStats(),
        closeReason: String? = null
    ) = PeerProgress(endpointId, pubkey, phase, "g1", stats, closeReason)

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

    // These captures check dimensions and nonblank rendering, not pixel equality against a golden image.
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
