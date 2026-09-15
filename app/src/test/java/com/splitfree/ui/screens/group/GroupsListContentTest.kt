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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.splitfree.R
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.usecase.group.GroupSummary
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.GroupsListUiState
import com.splitfree.ui.viewmodels.QrScanPhase
import com.splitfree.ui.viewmodels.QrScanState
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
class GroupsListContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(readyState())
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    @Test
    fun `both scan buttons disabled during preparation while paste stays usable`() {
        var cancels = 0
        compose.setContent {
            SplitFreeTheme {
                GroupsListContent(
                    state = readyState(),
                    actions = GroupsListActions(cancelScan = { cancels++ }),
                    scanState = QrScanState(phase = QrScanPhase.Preparing)
                )
            }
        }
        compose.onNodeWithTag("home_scan").assertIsNotEnabled()
        compose.onNodeWithTag("home_paste").assertIsEnabled()
        compose.onNodeWithTag("home_scan_status").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
        listNode("home_quick_scan").assertIsNotEnabled()
        listNode("home_quick_paste").assertIsEnabled()
    }

    @Test
    fun `scanner failure stays visible and scan is retryable`() {
        var scans = 0
        compose.setContent {
            SplitFreeTheme {
                GroupsListContent(
                    state = readyState(),
                    actions = GroupsListActions(scanQr = { scans++ }),
                    scanState = QrScanState(error = R.string.qr_scan_services_unavailable)
                )
            }
        }
        compose.onNodeWithText(text(R.string.qr_scan_services_unavailable)).assertIsDisplayed()
        compose.onNodeWithTag("home_scan").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, scans) }
    }

    @Test
    fun `ready state shows net to receive with per currency totals`() {
        render()

        compose.onNodeWithTag("home_hero").assertIsDisplayed()
            .assertTextContains(text(R.string.net_to_receive).uppercase() + " · INR")
            .assertTextContains("₹2,500.00")
            .assertTextContains("₹3,150.00")
            .assertTextContains("₹650.00")
        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithText("3 groups").assertIsDisplayed()
    }

    @Test
    fun `group cards spell out the direction of each balance`() {
        render()

        compose.onNodeWithTag("home_group_goa").assertIsDisplayed()
            .assertTextContains("₹2,400.00").assertTextContains(text(R.string.direction_owed_to_you))
            .assertTextContains("4 people · INR")
        compose.onNodeWithTag("home_group_flat").assertTextContains(text(R.string.direction_owed_to_you))
        listNode("home_group_club").assertTextContains("₹650.00").assertTextContains(text(R.string.direction_you_owe))
    }

    @Test
    fun `group card announces name people amount and direction as one node`() {
        render()

        compose.onNode(hasContentDescription("Goa trip, 4 people, ₹2,400.00 owed to you")).assertIsDisplayed()
    }

    @Test
    fun `switching to USD shows settled and no-expenses wording`() {
        val selections = mutableListOf<String>()
        render(GroupsListActions(selectCurrency = { selections += it }))

        compose.onNodeWithTag("home_currency").assertIsDisplayed().assertTextContains("INR").performClick()
        compose.onNodeWithTag("home_currency_USD").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("USD"), selections)
            state = state.copy(selectedCurrency = "USD")
        }

        compose.onNodeWithTag("home_hero")
            .assertTextContains(text(R.string.net_to_pay).uppercase() + " · USD")
            .assertTextContains("$15.00")
        compose.onNodeWithTag("home_group_goa").assertTextContains(text(R.string.direction_no_expenses, "USD"))
        compose.onNodeWithTag("home_group_flat").assertTextContains(text(R.string.direction_you_owe))
        listNode("home_group_club").assertTextContains(text(R.string.direction_settled)).assertTextContains("$0.00")
    }

    @Test
    fun `currency selector is hidden with a single currency`() {
        state = state.copy(
            currencies = listOf("INR"),
            groups = state.groups.map { it.copy(myBalances = it.myBalances - "USD", currencies = setOf("INR")) }
        )
        render()

        compose.onNodeWithTag("home_currency").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.balances_in_currency, "INR").uppercase()).assertIsDisplayed()
    }

    @Test
    fun `tapping a group card opens that group`() {
        val opened = mutableListOf<String>()
        render(GroupsListActions(openGroup = { opened += it }))

        // A real touch on the fully visible first card, then accessibility clicks on the rest: cards lower
        // down can sit behind the bottom dock, whose button legitimately wins the hit test there.
        compose.onNodeWithTag("home_group_goa").performClick()
        compose.onNodeWithTag("home_group_flat").performAccessibleClick()
        listNode("home_group_club").performAccessibleClick()

        compose.runOnIdle { assertEquals(listOf("goa", "flat", "club"), opened) }
    }

    @Test
    fun `dock button creates a group and quick tiles reach the invite actions`() {
        var creates = 0
        var scans = 0
        var pastes = 0
        render(GroupsListActions(createGroup = { creates++ }, scanQr = { scans++ }, pasteInvite = { pastes++ }))

        compose.onNodeWithTag("home_new_group").assertIsDisplayed().assertIsEnabled().performClick()
        listNode("home_quick_scan").performClick()
        listNode("home_quick_paste").performClick()
        compose.onNodeWithTag("home_scan").performClick()
        compose.onNodeWithTag("home_paste").performClick()

        compose.runOnIdle {
            assertEquals(1, creates)
            assertEquals(2, scans)
            assertEquals(2, pastes)
        }
    }

    @Test
    fun `settings tile opens settings`() {
        var settings = 0
        render(GroupsListActions(openSettings = { settings++ }))

        compose.onNodeWithTag("home_settings").assertIsDisplayed()
            .assert(hasContentDescription(text(R.string.settings))).performClick()

        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test
    fun `connecting shows a neutral pill and no banner until the relays fail`() {
        state = state.copy(connection = ConnectionStatus.Connecting)
        render()

        compose.onNode(hasContentDescription(text(R.string.cd_connection_status, text(R.string.status_connecting))))
            .assertIsDisplayed()
        compose.onNodeWithTag("home_offline").assertDoesNotExist()
        compose.onAllNodes(hasText(text(R.string.offline_banner))).assertCountEquals(0)

        state = state.copy(connection = ConnectionStatus.Offline)
        compose.onNodeWithTag("home_offline").assertIsDisplayed()
        compose.onNode(hasContentDescription(text(R.string.cd_connection_status, text(R.string.status_offline))))
            .assertIsDisplayed()
    }

    @Test
    fun `offline shows a banner and an offline pill and never claims data loss`() {
        state = state.copy(connection = ConnectionStatus.Offline)
        render()

        compose.onNodeWithTag("home_offline").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.offline_banner)).assertIsDisplayed()
        compose.onNode(hasContentDescription(text(R.string.cd_connection_status, text(R.string.status_offline))))
            .assertIsDisplayed()

        state = state.copy(connection = ConnectionStatus.Connected)
        compose.onNodeWithTag("home_offline").assertDoesNotExist()
        compose.onNode(hasContentDescription(text(R.string.cd_connection_status, text(R.string.status_online))))
            .assertIsDisplayed()
    }

    @Test
    fun `loading shows the header and a skeleton but no money`() {
        state = GroupsListUiState(loading = true, connection = ConnectionStatus.Connected)
        render()

        compose.onNodeWithText(text(R.string.home_title)).assertIsDisplayed()
        compose.onNodeWithTag("home_skeleton").assertIsDisplayed()
        compose.onNodeWithTag("home_hero").assertDoesNotExist()
        compose.onNodeWithTag("home_empty").assertDoesNotExist()
        compose.onAllNodes(hasText("₹", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText(text(R.string.direction_settled), substring = true)).assertCountEquals(0)
    }

    @Test
    fun `empty shows the empty state without a hero and keeps the invite tiles`() {
        var creates = 0
        state = GroupsListUiState(loading = false, connection = ConnectionStatus.Connected)
        render(GroupsListActions(createGroup = { creates++ }))

        compose.onNodeWithTag("home_hero").assertDoesNotExist()
        compose.onNodeWithTag("home_currency").assertDoesNotExist()
        compose.onNodeWithTag("home_empty").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.no_groups_yet)).assertIsDisplayed()
        compose.onNodeWithTag("home_empty_new_group").performClick()
        listNode("home_quick_scan").assertIsDisplayed()
        listNode("home_quick_paste").assertIsDisplayed()
        compose.onAllNodes(hasText("₹", substring = true)).assertCountEquals(0)

        compose.runOnIdle { assertEquals(1, creates) }
    }

    @Test
    fun `unavailable balances replace the hero total but keep the other groups' amounts`() {
        state = state.copy(
            groups = state.groups.mapIndexed { index, group ->
                if (index ==
                    0
                ) {
                    group.copy(balancesAvailable = false, myBalances = emptyMap(), currencies = emptySet())
                } else {
                    group
                }
            }
        )
        var retries = 0
        render(GroupsListActions(retryBalances = { retries++ }))

        compose.onNodeWithTag("home_hero").assertDoesNotExist()
        compose.onNodeWithTag("home_balances_unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.balances_unavailable_body)).assertIsDisplayed()
        compose.onNodeWithTag("home_group_goa").assertTextContains(text(R.string.balance_unavailable))
        compose.onNode(inCard("goa", hasText("₹", substring = true)), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(inCard("goa", hasText(text(R.string.direction_settled))), useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("home_group_flat").assertTextContains("₹750.00")
            .assertTextContains(text(R.string.direction_owed_to_you))
        compose.onNodeWithTag("home_retry_balances").performClick()
        compose.runOnIdle { assertEquals(1, retries) }

        state = state.copy(groups = readyState().groups)
        compose.onNodeWithTag("home_balances_unavailable").assertDoesNotExist()
        compose.onNodeWithTag("home_hero").assertIsDisplayed().assertTextContains("₹2,500.00")
    }

    @Test
    fun `unavailable observation with no list is not the new user empty state`() {
        var retries = 0
        state =
            state.copy(
                groups = emptyList(),
                currencies = emptyList(),
                selectedCurrency = null,
                observationUnavailable = true
            )
        render(GroupsListActions(retryBalances = { retries++ }))

        compose.onNodeWithTag("home_balances_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("home_empty").assertDoesNotExist()
        compose.onNodeWithTag("home_hero").assertDoesNotExist()
        compose.onAllNodes(hasText(text(R.string.no_groups_yet))).assertCountEquals(0)
        compose.onNodeWithTag("home_retry_balances").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `settled and no-activity groups keep their wording next to an unavailable one`() {
        state = GroupsListUiState(
            loading = false,
            connection = ConnectionStatus.Connected,
            currencies = listOf("INR"),
            selectedCurrency = "INR",
            groups = listOf(
                summary("sealed", "Sealed", 2, emptyMap(), emptySet()).copy(balancesAvailable = false),
                summary("even", "Even", 2, mapOf("INR" to 0L), setOf("INR")),
                summary("fresh", "Fresh start", 2, emptyMap(), emptySet())
            )
        )
        render()

        compose.onNodeWithTag("home_group_sealed").assertTextContains(text(R.string.balance_unavailable))
        listNode("home_group_even").assertTextContains(text(R.string.direction_settled)).assertTextContains("₹0.00")
        listNode("home_group_fresh").assertTextContains(text(R.string.direction_no_expenses, "INR"))
        compose.onAllNodes(hasText(text(R.string.balance_unavailable))).assertCountEquals(1)
    }

    @Test
    fun `a group with no activity in any currency shows no amount and no fake zero`() {
        state = GroupsListUiState(
            loading = false,
            connection = ConnectionStatus.Connected,
            groups = listOf(summary("fresh", "Fresh start", 2, emptyMap(), emptySet())),
            currencies = emptyList(),
            selectedCurrency = null
        )
        render()

        compose.onNodeWithTag("home_hero").assertTextContains(text(R.string.nothing_to_settle_yet).uppercase())
        compose.onNodeWithTag("home_group_fresh").assertTextContains(text(R.string.direction_no_expenses_yet))
            .assertTextContains("2 people")
        compose.onAllNodes(hasText("₹", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("0.00", substring = true)).assertCountEquals(0)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `light fixture captures the home screen`() {
        render(dark = false)
        capture("home-light")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `dark fixture captures the home screen`() {
        render(dark = true)
        capture("home-dark")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `empty fixture captures the empty state`() {
        state = GroupsListUiState(loading = false, connection = ConnectionStatus.Connected)
        render()
        capture("home-empty")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `loading fixture captures the skeleton`() {
        state = GroupsListUiState(loading = true, connection = ConnectionStatus.Offline)
        render()
        capture("home-loading")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `360dp at 200 percent text wraps everything and stacks the invite tiles`() {
        RuntimeEnvironment.setFontScale(2f)
        var opened = 0
        render(GroupsListActions(openGroup = { opened++ }))
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }

        compose.onNodeWithTag("home_new_group").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("home_hero").assertIsDisplayed().assertTextContains("₹2,500.00")
        capture("home-font200", expectedWidth = 360)

        listNode("home_group_club").assertTextContains(text(R.string.direction_you_owe)).performAccessibleClick()
        val clubTitle = compose.onNode(inCard("club", hasText("Badminton")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val clubMoney = compose.onNode(inCard("club", hasText("₹650.00")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Balance must move under the title at large text", clubMoney.top >= clubTitle.bottom)
        val card = compose.onNodeWithTag("home_group_club").fetchSemanticsNode().boundsInRoot
        assertEquals("Stacked balance starts at the card's inner edge", card.left + 14f, clubMoney.left, 0.5f)
        // Scroll once to the last tile, then read both tiles' bounds from the same scroll position.
        val paste = listNode("home_quick_paste").fetchSemanticsNode().boundsInRoot
        val scan = compose.onNodeWithTag("home_quick_scan").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Quick tiles must stack at large text", paste.top >= scan.bottom)
        assertEquals("Stacked tiles span the same width", scan.width, paste.width, 0.5f)
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private fun render(actions: GroupsListActions = GroupsListActions(), dark: Boolean = false) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            SplitFreeTheme(darkTheme = dark) { GroupsListContent(state, actions) }
        }
    }

    private fun SemanticsNodeInteraction.performAccessibleClick(): SemanticsNodeInteraction =
        assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue("Accessibility click must be handled", action())
        }

    private fun inCard(id: String, matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag("home_group_$id"))

    private fun listNode(tag: String) = compose.onNodeWithTag("home_list")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag).assertIsDisplayed() }

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun capture(name: String, expectedWidth: Int = 390) {
        compose.onNodeWithTag("home_new_group").assertIsDisplayed()
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

    private fun readyState() = GroupsListUiState(
        loading = false,
        connection = ConnectionStatus.Connected,
        currencies = listOf("INR", "USD"),
        selectedCurrency = "INR",
        groups = listOf(
            summary("goa", "Goa trip", 4, mapOf("INR" to 240000L), setOf("INR")),
            summary("flat", "Flatmates", 3, mapOf("INR" to 75000L, "USD" to -1500L), setOf("INR", "USD")),
            summary("club", "Badminton", 6, mapOf("INR" to -65000L), setOf("INR", "USD"))
        )
    )

    private fun summary(id: String, name: String, people: Int, mine: Map<String, Long>, currencies: Set<String>) =
        GroupSummary(
            group = Group(
                id = id,
                name = name,
                createdBy = "you",
                createdAt = 1000L,
                members = List(people) { if (it == 0) "you" else "member-$it" },
                relays = listOf("wss://relay.example")
            ),
            myBalances = mine,
            hasExpenses = currencies.isNotEmpty(),
            currencies = currencies
        )
}
