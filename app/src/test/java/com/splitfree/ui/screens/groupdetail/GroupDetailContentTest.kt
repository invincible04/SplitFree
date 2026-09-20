package com.splitfree.ui.screens.groupdetail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.GroupDetailUiState
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupDetailContentTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(readyState())
    private var sheet by mutableStateOf<GroupSheet?>(null)
    private var selectedCurrency by mutableStateOf<String?>(null)
    private var inviteLink by mutableStateOf<String?>(INVITE_LINK)
    private var selectedTab by mutableIntStateOf(TAB_SUMMARY)
    private lateinit var contentView: View
    private var renderedFontScale = 1f

    // --- Summary card -------------------------------------------------------------------------------------

    @Test
    fun `summary card states direction in words with the net amount for the default currency`() {
        render()

        compose.onNodeWithText("YOU ARE OWED · INR").assertIsDisplayed()
        compose.onNodeWithTag("group_summary_amount").assertTextEquals("₹2,100.00")
        compose.onNodeWithText("Across 4 expenses").assertIsDisplayed()
        compose.onNodeWithText("4 people · private group").assertIsDisplayed()
    }

    @Test
    fun `summary card says you owe when I am only a debtor`() {
        state = state.copy(debts = listOf(DebtTransaction(ME, RAHUL, 30000, "INR")))
        render()

        compose.onNodeWithText("YOU OWE · INR").assertIsDisplayed()
        compose.onNodeWithTag("group_summary_amount").assertTextEquals("₹300.00")
    }

    @Test
    fun `zero personal balance is not reported as everyone settled`() {
        state = state.copy(debts = listOf(DebtTransaction(SAM, RAHUL, 50000, "INR")))
        render()

        compose.onNodeWithText("YOUR BALANCE · INR").assertIsDisplayed()
        compose.onNodeWithTag("group_summary_amount").assertTextEquals("₹0.00")
        compose.onNodeWithText(text(R.string.group_summary_others_open)).assertIsDisplayed()
        compose.onNodeWithText("Everyone is settled in INR").assertDoesNotExist()
    }

    @Test
    fun `no debts with expenses reads settled, no data at all reads neutral`() {
        state = state.copy(debts = emptyList(), expenses = state.expenses.filter { it.expense.currency == "INR" })
        render()
        compose.onAllNodesWithText("Everyone is settled in INR").assertCountEquals(2)

        state = GroupDetailUiState(groupName = "Goa trip", members = state.members, myPubkey = ME)
        compose.onNode(hasText(text(R.string.no_expenses_yet)) and hasAnyAncestor(hasTestTag("group_summary")))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.group_no_balances_hint)).assertIsDisplayed()
        compose.onNodeWithText("YOUR BALANCE").assertIsDisplayed()
        compose.onNodeWithTag("group_currency").assertDoesNotExist()
        compose.onNodeWithTag("group_summary_amount").assertDoesNotExist()
    }

    @Test
    fun `unavailable balances show recovery without a zero or settled wording and keep the history`() {
        state = state.copy(balancesAvailable = false)
        var retries = 0
        render(GroupDetailActions(retryBalances = { retries++ }))

        compose.onNodeWithTag("group_summary_unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.group_balances_unavailable_body)).assertIsDisplayed()
        compose.onNodeWithTag("group_summary").assertDoesNotExist()
        compose.onNodeWithTag("group_summary_amount").assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.record_payment)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.group_who_pays_whom)).assertCountEquals(0)
        compose.onAllNodesWithText("Everyone is settled in INR").assertCountEquals(0)
        compose.onAllNodesWithText("YOUR BALANCE · INR").assertCountEquals(0)
        compose.onAllNodes(hasText("₹0.00")).assertCountEquals(0)
        compose.onNodeWithTag("group_retry_balances").performClick()
        compose.runOnIdle { assertEquals(1, retries) }

        // The expense history is still readable: only money derived from it is withheld.
        scrollScreen(700f)
        compose.onNodeWithTag("group_expense_${MEERA}_taxi").assertIsDisplayed()

        state = state.copy(balancesAvailable = true)
        compose.onNodeWithTag("group_summary_unavailable").assertDoesNotExist()
        compose.onNodeWithTag("group_summary_amount").assertTextEquals("₹2,100.00")
    }

    @Test
    fun `unavailable balances with an empty history do not read as no expenses`() {
        state = state.copy(balancesAvailable = false, debts = emptyList(), expenses = emptyList())
        render()

        compose.onNodeWithTag("group_summary_unavailable").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.no_expenses_yet)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.tap_add_first_expense)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.group_no_balances_hint)).assertCountEquals(0)
    }

    // --- Currency selection -------------------------------------------------------------------------------

    @Test
    fun `currency chip appears only with two or more currencies`() {
        state =
            state.copy(
                debts = state.debts.filter { it.currency == "INR" },
                expenses = state.expenses.filter {
                    it.expense.currency ==
                        "INR"
                }
            )
        render()
        compose.onNodeWithTag("group_currency").assertDoesNotExist()
        compose.onNodeWithText("BALANCES IN INR").assertIsDisplayed()

        state = readyState()
        compose.onNodeWithTag("group_currency").assertIsDisplayed().assertTextEquals("INR")
        compose.onNodeWithText("BALANCES IN").assertIsDisplayed()
    }

    @Test
    fun `switching currency filters the summary, the debts and the recent expenses`() {
        render()

        compose.onNodeWithTag("group_currency").performClick()
        compose.onNodeWithTag("group_currency_USD").performClick()

        compose.runOnIdle { assertEquals("USD", selectedCurrency) }
        compose.onNodeWithText("YOU OWE · USD").assertIsDisplayed()
        compose.onNodeWithTag("group_summary_amount").assertTextEquals("$60.00")
        compose.onNodeWithText("1 open").assertIsDisplayed()
        compose.onNodeWithText("You owe Rahul").assertIsDisplayed()
        compose.onNodeWithText("Meera owes you").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_${RAHUL}_museum").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_${MEERA}_taxi").assertDoesNotExist()
    }

    // --- Summary pane -------------------------------------------------------------------------------------

    @Test
    fun `record payment is offered only on debts I am party to`() {
        render()

        compose.onNodeWithText("4 open").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.record_payment)).assertCountEquals(3)
        compose.onNodeWithText("Meera owes you").assertIsDisplayed()
        compose.onNodeWithText("Sam owes Rahul").assertIsDisplayed()
        compose.onNode(hasTestTag(debtTag(SAM, RAHUL)) and hasAnyDescendant(hasText(text(R.string.record_payment))))
            .assertDoesNotExist()
        compose.onNode(hasTestTag(debtTag(MEERA, ME)) and hasAnyDescendant(hasText(text(R.string.record_payment))))
            .assertExists()
    }

    @Test
    fun `recent activity shows the newest three with my personal delta`() {
        render()

        scrollScreen(700f)
        compose.onNodeWithTag("group_expense_${MEERA}_taxi").assertIsDisplayed()
        compose.onNodeWithText("you lent ₹3,000.00").assertIsDisplayed()
        compose.onNodeWithText("your share ₹400.00").assertExists()
        compose.onNodeWithTag("group_expense_${SAM}_snacks").assertDoesNotExist()
    }

    @Test
    fun `see all switches to the expenses tab`() {
        render()

        scrollScreen(300f)
        compose.onNodeWithTag("group_see_all").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(TAB_EXPENSES, selectedTab) }
        compose.onNodeWithTag("group_pane_expenses").assertExists()
        compose.onNodeWithTag("group_pane_summary").assertDoesNotExist()
    }

    @Test
    fun `tabs switch panes in any order and only the selected pane stays`() {
        render()

        showPage(R.string.tab_members)
        compose.runOnIdle { assertEquals(TAB_PEOPLE, selectedTab) }
        compose.onNodeWithTag("group_pane_people").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_summary").assertDoesNotExist()
        compose.onNodeWithTag("group_pane_expenses").assertDoesNotExist()

        showPage(R.string.tab_balances)
        compose.runOnIdle { assertEquals(TAB_SUMMARY, selectedTab) }
        compose.onNodeWithTag("group_pane_summary").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_people").assertDoesNotExist()

        showPage(R.string.tab_expenses)
        compose.onNodeWithTag("group_pane_expenses").assertIsDisplayed()
        compose.onNodeWithText("4 entries").assertIsDisplayed()
    }

    @Test
    fun `switching from a long pane while scrolled down keeps the tabs in view`() {
        val extraMembers = (1..8).map { "extra$it".padEnd(64, '0') }
        val extraDebts = extraMembers.map { DebtTransaction(it, ME, 10000, "INR") }
        val extraExpenses = (1..12).map { index ->
            state.expenses.first().let {
                it.copy(
                    expense = it.expense.copy(
                        id = "extra$index",
                        description = "Extra $index",
                        timestamp =
                        1_000L + index
                    )
                )
            }
        }
        state =
            state.copy(
                members = state.members + extraMembers,
                memberNames = state.memberNames + extraMembers.associateWith { "Guest ${it.take(6)}" },
                debts = state.debts + extraDebts,
                expenses = state.expenses + extraExpenses
            )
        render()
        scrollScreen(2_000f)
        compose.onNodeWithTag("group_see_all").assertIsDisplayed()
        // With pinned tabs, the tab bar remains accessible at the top while scrolled down.
        compose.onNodeWithTag("group_tabs").assertIsDisplayed()

        compose.onNodeWithTag("group_see_all").performClick()
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(TAB_EXPENSES, selectedTab) }
        compose.onNodeWithTag("group_tabs").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_expenses").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_${MEERA}_taxi").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_summary").assertDoesNotExist()
    }

    @Test
    fun `short summary does not scroll a detached header or create empty space`() {
        assertShortPaneDoesNotScroll(TAB_SUMMARY, "group_pane_summary")
    }

    @Test
    fun `short expenses do not scroll a detached header or create empty space`() {
        assertShortPaneDoesNotScroll(TAB_EXPENSES, "group_pane_expenses")
    }

    @Test
    fun `short people list does not scroll a detached header or create empty space`() {
        assertShortPaneDoesNotScroll(TAB_PEOPLE, "group_pane_people")
    }

    private fun assertShortPaneDoesNotScroll(tab: Int, paneTag: String) {
        selectedTab = tab
        state = state.copy(
            members = listOf(ME, MEERA),
            memberCount = 2,
            debts = listOf(DebtTransaction(MEERA, ME, 250000, "INR")),
            expenses = state.expenses.filter { it.expense.id == "beach" }
        )
        render()
        val beforeTabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val beforePane = compose.onNodeWithTag(paneTag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("group_scroll").performTouchInput {
            swipeUp(startY = height * 0.85f, endY = height * 0.6f, durationMillis = 1000)
        }
        compose.waitForIdle()
        val afterTabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val afterPane = compose.onNodeWithTag(paneTag).fetchSemanticsNode().boundsInRoot
        assertEquals("A short page has no extra header-only scroll range", beforeTabs.top, afterTabs.top, 1f)
        assertEquals("The pane must remain next to the tabs", beforePane.top, afterPane.top, 1f)
        assertEquals(
            "Scrolling must not open a blank header-sized gap",
            beforePane.top - beforeTabs.bottom,
            afterPane.top - afterTabs.bottom,
            1f
        )
        capture("group-short-$tab-after-swipe")
    }

    @Test
    fun `empty pages stay still after vertical swipes in every tab`() {
        state = state.copy(members = listOf(ME), memberCount = 1, debts = emptyList(), expenses = emptyList())
        render()
        listOf(R.string.tab_balances, R.string.tab_expenses, R.string.tab_members).forEach { tab ->
            showPage(tab)
            val before = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("group_scroll").performTouchInput {
                swipeUp(startY = height * 0.85f, endY = height * 0.6f, durationMillis = 1000)
            }
            compose.waitForIdle()
            assertEquals(
                "Empty tab $tab must not collapse into blank space",
                before,
                compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot.top,
                1f
            )
        }
    }

    @Test
    fun `long content follows the header immediately in every tab without exposing the top bar`() {
        val guests = (1..15).map { "guest$it".padEnd(64, '0') }
        val base = state.expenses.first()
        state = state.copy(
            members = state.members + guests,
            debts = guests.map { DebtTransaction(it, ME, 10000, "INR") },
            expenses = (1..20).map { base.copy(expense = base.expense.copy(id = "long$it", timestamp = it.toLong())) }
        )
        render()
        listOf(
            R.string.tab_balances to "group_pane_summary",
            R.string.tab_expenses to "group_pane_expenses",
            R.string.tab_members to "group_pane_people"
        ).forEach { (tab, paneTag) ->
            showPage(tab)
            val beforeTabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
            val beforePane = compose.onNodeWithTag(paneTag).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("group_scroll").performTouchInput {
                swipeUp(startY = height * 0.85f, endY = height * 0.72f, durationMillis = 1000)
            }
            compose.waitForIdle()
            val afterTabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
            val afterPane = compose.onNodeWithTag(paneTag).fetchSemanticsNode().boundsInRoot
            assertTrue("Long tab $tab should scroll its real content", afterPane.top < beforePane.top - 10f)
            assertEquals(
                "Header and content move together in tab $tab",
                beforePane.top - beforeTabs.bottom,
                afterPane.top - afterTabs.bottom,
                1f
            )
            val body = compose.onNodeWithTag("group_body").fetchSemanticsNode().boundsInRoot
            val topBar = compose.onNodeWithTag("group_top_bar").fetchSemanticsNode().boundsInRoot
            val header = compose.onNodeWithTag("group_summary").fetchSemanticsNode().boundsInRoot
            assertTrue("The scrolling body starts below the toolbar", body.top >= topBar.bottom)
            assertTrue("The partially scrolled header is clipped below the toolbar", header.top >= body.top)
            capture("group-long-$tab-partial-swipe")
        }
    }

    @Test
    fun `switching between a scrolled history and short people page retains each real scroll position`() {
        selectedTab = TAB_EXPENSES
        val base = state.expenses.first()
        state = state.copy(
            members = listOf(ME, MEERA),
            memberCount = 2,
            expenses = (0..30).map { base.copy(expense = base.expense.copy(id = "tab_$it", timestamp = 100L - it)) }
        )
        render()
        scrollScreen(1000f)
        val anchor = compose.onNodeWithTag("group_expense_${ME}_tab_10").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithTag("group_tabs").assertIsDisplayed()
        val tabsBeforeSwitch = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        showPage(R.string.tab_members)
        assertEquals(
            "Changing panes must not move shared tabs vertically",
            tabsBeforeSwitch,
            compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        )
        val tabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val people = compose.onNodeWithTag("group_pane_people").fetchSemanticsNode().boundsInRoot
        assertEquals("Fresh short page has no stale collapsed-header gap", tabs.bottom + 10f, people.top, 1f)
        state =
            state.copy(
                expenses =
                listOf(base.copy(expense = base.expense.copy(id = "new_arrival", timestamp = 1000L))) + state.expenses
            )
        showPage(R.string.tab_expenses)
        assertEquals(
            "Returning to expenses keeps the same row position after an arrival on another tab",
            anchor,
            compose.onNodeWithTag("group_expense_${ME}_tab_10").fetchSemanticsNode().positionInRoot.y,
            1f
        )
        scrollScreen(100000f)
        val last = compose.onNodeWithTag("group_expense_${ME}_tab_30").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("group_add_expense").fetchSemanticsNode().boundsInRoot
        assertTrue("Last expense can be read and tapped above Add expense", last.bottom <= add.top - 10f)
        capture("group-last-expense-clear-of-button")
        state = state.copy(expenses = emptyList(), debts = emptyList())
        compose.onNodeWithTag("group_tabs").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_expenses").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h844dp-mdpi")
    fun `large text tabs support swipes and non adjacent clicks without moving back to another page`() {
        RuntimeEnvironment.setFontScale(2f)
        state = state.copy(members = listOf(ME, MEERA), memberCount = 2)
        render()
        scrollScreen(100000f)
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertSelectedPage(TAB_EXPENSES)
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertSelectedPage(TAB_PEOPLE)
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertSelectedPage(TAB_EXPENSES)
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertSelectedPage(TAB_SUMMARY)
        compose.onNodeWithTag("group_tabs").performTouchInput { swipeLeft() }
        showPage(R.string.tab_members)
        assertSelectedPage(TAB_PEOPLE)
        compose.onNodeWithTag("group_tabs").performTouchInput { swipeRight() }
        showPage(R.string.tab_balances)
        assertSelectedPage(TAB_SUMMARY)
        scrollScreen(100000f)
        val tabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("group_body").fetchSemanticsNode().boundsInRoot
        assertEquals("Large-text tabs remain pinned below the toolbar", body.top + 18f, tabs.top, 1f)
        capture("group-large-text-pinned-tabs", expectedWidth = 360)
    }

    @Test
    fun `non adjacent tab clicks and externally selected tabs settle on the requested page`() {
        render()
        listOf(
            R.string.tab_members to TAB_PEOPLE,
            R.string.tab_balances to TAB_SUMMARY,
            R.string.tab_expenses to TAB_EXPENSES,
            R.string.tab_members to TAB_PEOPLE,
            R.string.tab_balances to TAB_SUMMARY
        ).forEach { (label, tab) ->
            showPage(label)
            assertSelectedPage(tab)
        }
        selectedTab = TAB_EXPENSES
        compose.waitForIdle()
        assertSelectedPage(TAB_EXPENSES)
    }

    private fun assertSelectedPage(tab: Int) {
        compose.runOnIdle { assertEquals(tab, selectedTab) }
        val page = compose.onNodeWithTag("group_scroll").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("group_body").fetchSemanticsNode().boundsInRoot
        assertEquals("The selected page must fully settle, not stop between tabs", body.left, page.left, 1f)
        assertEquals("The selected page fills the body", body.width, page.width, 1f)
    }

    @Test
    fun `toolbar pixels remain unchanged when a long page is scrolled underneath`() {
        selectedTab = TAB_EXPENSES
        val base = state.expenses.first()
        state = state.copy(
            expenses = (1..20).map {
                base.copy(expense = base.expense.copy(id = "toolbar$it", timestamp = it.toLong()))
            }
        )
        render(dark = true)
        val barHeight = compose.onNodeWithTag("group_top_bar").fetchSemanticsNode().boundsInRoot.bottom.toInt()
        fun toolbarPixels(): IntArray = compose.runOnIdle {
            val decor = contentView.rootView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            IntArray(decor.width * barHeight).also {
                bitmap.getPixels(it, 0, decor.width, 0, 0, decor.width, barHeight)
                bitmap.recycle()
            }
        }
        val before = toolbarPixels()
        scrollScreen(150f)
        assertTrue("Header must not draw behind the transparent title bar", before.contentEquals(toolbarPixels()))
        scrollScreen(10000f)
        assertTrue("Pinned content must not draw behind the title bar", before.contentEquals(toolbarPixels()))
        capture("group-dark-pinned-toolbar")
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun `landscape long history keeps tabs reachable and last expense above the action`() {
        selectedTab = TAB_EXPENSES
        val base = state.expenses.first()
        state = state.copy(
            expenses = (1..20).map {
                base.copy(expense = base.expense.copy(id = "landscape$it", timestamp = it.toLong()))
            }
        )
        render()
        scrollScreen(100000f)
        compose.onNodeWithTag("group_tabs").assertIsDisplayed()
        val tabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("group_body").fetchSemanticsNode().boundsInRoot
        assertEquals("Tabs pin below the toolbar in landscape", body.top + 18f, tabs.top, 1f)
        val last = compose.onNodeWithTag("group_expense_${ME}_landscape1").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("group_add_expense").fetchSemanticsNode().boundsInRoot
        assertTrue("Landscape last row remains above Add expense", last.bottom <= add.top - 10f)
        capture("group-landscape-pinned-tabs", expectedWidth = 640, expectedMinHeight = 300)
    }

    @Test
    fun `touch scrolling collapses shared chrome and short page can expand it without a gap`() {
        selectedTab = TAB_EXPENSES
        val base = state.expenses.first()
        state = state.copy(
            members = listOf(ME, MEERA),
            memberCount = 2,
            expenses = (0..30).map { base.copy(expense = base.expense.copy(id = "touch$it", timestamp = 100L - it)) }
        )
        render()
        repeat(5) {
            compose.onNodeWithTag("group_scroll").performTouchInput { swipeUp(durationMillis = 500) }
            compose.waitForIdle()
        }
        val bodyTop = compose.onNodeWithTag("group_body").fetchSemanticsNode().boundsInRoot.top
        val pinned = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        assertEquals("Real touch scrolling pins shared tabs", bodyTop + 18f, pinned.top, 1f)
        compose.onNodeWithTag("group_tabs").performTouchInput { swipeDown(durationMillis = 500) }
        compose.waitForIdle()
        assertEquals(
            "Dragging tabs down while the list is deep must not expand chrome",
            pinned,
            compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        )
        showPage(R.string.tab_members)
        assertEquals(
            "Tab switch preserves shared chrome",
            pinned,
            compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        )
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeDown(durationMillis = 700) }
        compose.waitForIdle()
        compose.onNodeWithTag("group_summary").assertIsDisplayed()
        val tabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val people = compose.onNodeWithTag("group_pane_people").fetchSemanticsNode().boundsInRoot
        assertTrue("A downward gesture at short-pane top can restore shared header", tabs.top > pinned.top + 100f)
        assertEquals("Expanded pane stays immediately below tabs", tabs.bottom + 10f, people.top, 1f)
        capture("group-shared-header-expanded-on-people")
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun `compact header consumes its own scroll before collapsing so its controls remain reachable`() {
        RuntimeEnvironment.setFontScale(2f)
        state = state.copy(balancesAvailable = false)
        render()
        val tabs = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("group_header").performTouchInput {
            swipeUp(startY = height * 0.9f, endY = height * 0.1f, durationMillis = 700)
        }
        compose.waitForIdle()
        assertEquals(
            "Internal header scroll must not first collapse shared chrome",
            tabs,
            compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        )
        compose.onNodeWithTag("group_retry_balances").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("group_header").performTouchInput { swipeDown(durationMillis = 700) }
        compose.waitForIdle()
        compose.onNodeWithTag("group_currency").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `horizontal drag moves only pane content and keeps one shared header and tab strip`() {
        state = state.copy(members = listOf(ME), memberCount = 1, debts = emptyList(), expenses = emptyList())
        render()
        val headerBefore = compose.onNodeWithTag("group_summary").fetchSemanticsNode().boundsInRoot
        val tabsBefore = compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot
        val paneBefore = compose.onNodeWithTag("group_pane_summary").fetchSemanticsNode().positionInRoot.x
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("group_scroll").performTouchInput {
            down(Offset(width * 0.8f, height * 0.65f))
            moveTo(Offset(width * 0.5f, height * 0.65f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodes(hasTestTag("group_tabs")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("group_summary")).assertCountEquals(1)
        assertEquals(headerBefore, compose.onNodeWithTag("group_summary").fetchSemanticsNode().boundsInRoot)
        assertEquals(tabsBefore, compose.onNodeWithTag("group_tabs").fetchSemanticsNode().boundsInRoot)
        assertTrue(
            "Only the pane moves horizontally",
            compose.onNodeWithTag("group_pane_summary").fetchSemanticsNode().positionInRoot.x < paneBefore - 20f
        )
        capture("group-shared-header-mid-drag")
        compose.onNodeWithTag("group_scroll").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun `swiping horizontally switches between group tabs`() {
        render()
        compose.onNodeWithTag("group_pane_summary").assertIsDisplayed()

        // Swipe left to switch from Summary to Expenses
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(TAB_EXPENSES, selectedTab) }
        compose.onNodeWithTag("group_pane_expenses").assertIsDisplayed()

        // Swipe left again to switch from Expenses to People
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(TAB_PEOPLE, selectedTab) }
        compose.onNodeWithTag("group_pane_people").assertIsDisplayed()

        // Swipe right to switch back to Expenses
        compose.onNodeWithTag("group_scroll").performTouchInput { swipeRight() }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(TAB_EXPENSES, selectedTab) }
        compose.onNodeWithTag("group_pane_expenses").assertIsDisplayed()
    }

    @Test
    fun `long expense histories compose only the viewport and keep rows anchored when expenses arrive`() {
        selectedTab = TAB_EXPENSES
        val base = state.expenses.first()
        state = state.copy(
            expenses = (0 until 2000).map { index ->
                base.copy(
                    expense = base.expense.copy(
                        id = "history_$index",
                        description = "History $index",
                        timestamp = 10_000L - index
                    )
                )
            }
        )
        render()

        compose.onNodeWithText("2000 entries").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_${ME}_history_0").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_${ME}_history_1999").assertDoesNotExist()
        compose.onNodeWithTag("group_scroll").performScrollToKey(expenseRowKey(ExpenseIdentity(ME, "history_1000")))
        val before = compose.onNodeWithTag("group_expense_${ME}_history_1000").fetchSemanticsNode().positionInRoot.y

        state = state.copy(
            expenses = listOf(base.copy(expense = base.expense.copy(id = "new", timestamp = 20_000L))) + state.expenses
        )

        val after = compose.onNodeWithTag("group_expense_${ME}_history_1000").fetchSemanticsNode().positionInRoot.y
        assertEquals("Stable row keys keep the same expense anchored after insertion", before, after, 1f)
        compose.onNodeWithTag("group_expense_${ME}_history_0").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_${ME}_new").assertDoesNotExist()
        capture("group-long-history-middle")

        compose.onNodeWithTag("group_scroll").performScrollToKey(expenseRowKey(ExpenseIdentity(ME, "history_1999")))
        compose.onNodeWithTag("group_expense_${ME}_history_1999").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "history_1999")), sheet) }
        sheet = null
        capture("group-long-history-end")
        compose.onNodeWithTag("group_add_expense").assertIsDisplayed()
    }

    @Test
    fun `selected tab survives a state update`() {
        render()
        showPage(R.string.tab_members)

        state = state.copy(groupName = "Goa trip 2")

        compose.onNodeWithText("Goa trip 2").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_people").assertIsDisplayed()
        compose.runOnIdle { assertEquals(TAB_PEOPLE, selectedTab) }
    }

    @Test
    fun `record payment opens the settle sheet and confirming reports the debt`() {
        val settled = mutableListOf<DebtTransaction>()
        render(GroupDetailActions(confirmSettle = { settled += it }))

        compose.onNode(hasText(text(R.string.record_payment)) and hasAnyAncestor(hasTestTag(debtTag(MEERA, ME))))
            .performClick()

        compose.runOnIdle { assertEquals(GroupSheet.Settle(DebtTransaction(MEERA, ME, 80000, "INR")), sheet) }
        compose.onNodeWithTag("group_sheet_amount").assertTextEquals("₹800.00")
        compose.onNodeWithText("Meera paid you").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.group_settle_warning)).assertIsDisplayed()

        compose.onNodeWithTag("group_confirm_settle").performClick()

        compose.runOnIdle {
            assertEquals(listOf(DebtTransaction(MEERA, ME, 80000, "INR")), settled)
            assertNull(sheet)
        }
    }

    // --- Expenses pane ------------------------------------------------------------------------------------

    @Test
    fun `expenses pane lists every expense in the currency newest first`() {
        render()
        showPage(R.string.tab_expenses)

        compose.onNodeWithText("4 entries").assertIsDisplayed()
        val tops = listOf("taxi", "dinner", "beach", "snacks").map { id ->
            compose.onNodeWithTag(
                "group_expense_${state.expenses.single {
                    it.expense.id == id
                }.authorPubkey}_$id"
            ).assertExists().fetchSemanticsNode().positionInRoot.y
        }
        assertEquals("Rows must be ordered newest first", tops.sorted(), tops)
        compose.onNodeWithText(text(R.string.group_delta_not_involved)).assertExists()
        compose.onNodeWithText("your share ₹200.00").assertExists()
    }

    @Test
    fun `tapping an expense opens its detail sheet`() {
        render()
        showPage(R.string.tab_expenses)

        compose.onNodeWithTag("group_expense_${MEERA}_taxi").performClick()

        compose.runOnIdle { assertEquals(GroupSheet.ExpenseDetail(ExpenseIdentity(MEERA, "taxi")), sheet) }
        compose.onNodeWithTag("group_sheet_amount").assertTextEquals("₹800.00")
        compose.onNode(
            hasText("Airport taxi") and hasAnyAncestor(hasTestTag("group_sheet_expense"))
        ).assertIsDisplayed()
        compose.onNode(
            hasText("Meera paid · Transport", substring = true) and hasAnyAncestor(hasTestTag("group_sheet_expense"))
        )
            .assertIsDisplayed()
        compose.onAllNodesWithText("₹200.00").assertCountEquals(4)

        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle { assertNull(sheet) }
    }

    @Test
    fun `delete is offered only on expenses I authored`() {
        render()
        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(MEERA, "taxi"))

        compose.onNodeWithTag("group_expense_delete").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_edit").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_only_author")
            .assertIsDisplayed()
            .assertTextEquals(text(R.string.group_expense_only_author, "Meera"))

        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach"))

        compose.onNodeWithTag("group_expense_delete").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_edit").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.done)).assertIsDisplayed()
        compose.onNodeWithTag("group_expense_only_author").assertDoesNotExist()
    }

    @Test
    fun `edit closes the sheet and hands the expense to the editor`() {
        val edited = mutableListOf<ExpenseIdentity>()
        render(GroupDetailActions(editExpense = { edited += it }))
        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach"))

        compose.onNodeWithTag("group_expense_edit").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ExpenseIdentity(ME, "beach")), edited)
            assertNull(sheet)
        }
    }

    @Test
    fun `colliding uuid rows open the selected details and only my record exposes mutations`() {
        val mine = state.expenses.first().copy(expense = state.expenses.first().expense.copy(id = "shared"))
        val theirs = AuthoredExpense(mine.expense.copy(description = "Their stay", amount = 500000), RAHUL)
        state = state.copy(expenses = listOf(theirs, mine))
        val edited = mutableListOf<ExpenseIdentity>()
        val deleted = mutableListOf<ExpenseIdentity>()
        render(GroupDetailActions(editExpense = { edited += it }, deleteExpense = { deleted += it }))
        showPage(R.string.tab_expenses)

        compose.onNodeWithText("2 entries").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_${RAHUL}_shared").performClick()
        compose.runOnIdle { assertEquals(GroupSheet.ExpenseDetail(theirs.identity), sheet) }
        compose.onNodeWithTag("group_sheet_amount").assertTextEquals("₹5,000.00")
        compose.onNodeWithTag("group_expense_edit").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_delete").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.done)).performClick()

        compose.onNodeWithTag("group_expense_${ME}_shared").performClick()
        compose.runOnIdle { assertEquals(GroupSheet.ExpenseDetail(mine.identity), sheet) }
        compose.onNodeWithTag("group_sheet_amount").assertTextEquals("₹4,000.00")
        compose.onNodeWithTag("group_expense_edit").performClick()
        compose.runOnIdle { assertEquals(listOf(mine.identity), edited) }

        compose.onNodeWithTag("group_expense_${ME}_shared").performClick()
        compose.onNodeWithTag("group_expense_delete").performClick()
        compose.onNodeWithTag("group_confirm_delete").performClick()
        compose.runOnIdle { assertEquals(listOf(mine.identity), deleted) }
    }

    @Test
    fun `recent activity preserves both colliding identities and never falls back after removal`() {
        val mine = state.expenses.first()
        val theirs = AuthoredExpense(mine.expense.copy(description = "Their stay"), RAHUL)
        state = state.copy(expenses = listOf(theirs, mine))
        render()

        scrollScreen(700f)
        compose.onNodeWithTag("group_expense_${RAHUL}_beach").assertExists()
        compose.onNodeWithTag("group_expense_${ME}_beach").performClick()
        compose.runOnIdle { assertEquals(GroupSheet.ExpenseDetail(mine.identity), sheet) }
        state = state.copy(expenses = listOf(theirs))
        compose.onNodeWithTag("group_sheet_expense").assertDoesNotExist()
        compose.runOnIdle { assertNull(sheet) }
    }

    @Test
    fun `delete confirmation does not carry to another author with the same uuid`() {
        val mine = state.expenses.first()
        val theirs = AuthoredExpense(mine.expense.copy(description = "Their stay"), RAHUL)
        state = state.copy(expenses = listOf(theirs, mine))
        render()
        sheet = GroupSheet.ExpenseDetail(mine.identity)
        compose.onNodeWithTag("group_expense_delete").performClick()
        compose.onNodeWithTag("group_confirm_delete").assertIsDisplayed()

        sheet = GroupSheet.ExpenseDetail(theirs.identity)

        compose.onNodeWithTag("group_confirm_delete").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_delete").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_edit").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_only_author").assertIsDisplayed()
    }

    @Test
    fun `no author information hides delete without blaming anyone`() {
        state = state.copy(expenses = state.expenses.map { it.copy(authorPubkey = "") })
        render()
        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity("", "beach"))

        compose.onNodeWithTag("group_expense_delete").assertDoesNotExist()
        compose.onNodeWithTag("group_expense_only_author").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.done)).assertIsDisplayed()
    }

    @Test
    fun `deleting asks for confirmation and reports the expense once confirmed`() {
        val deleted = mutableListOf<ExpenseIdentity>()
        render(GroupDetailActions(deleteExpense = { deleted += it }))
        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach"))

        compose.onNodeWithTag("group_expense_delete").performClick()

        compose.onNodeWithText(text(R.string.group_expense_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.group_expense_delete_body)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Confirmation must not delete yet", deleted.isEmpty())
            assertEquals(GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach")), sheet)
        }

        compose.onNodeWithTag("group_cancel_delete").performClick()
        compose.onNodeWithText(text(R.string.group_expense_details)).assertIsDisplayed()
        compose.onNodeWithTag("group_expense_delete").performClick()
        compose.onNodeWithTag("group_confirm_delete").performClick()

        compose.runOnIdle {
            assertEquals(listOf(ExpenseIdentity(ME, "beach")), deleted)
            assertNull(sheet)
        }
    }

    @Test
    fun `empty expenses pane offers the first expense`() {
        state =
            GroupDetailUiState(
                groupName = "Goa trip",
                members = state.members,
                memberNames = state.memberNames,
                myPubkey = ME
            )
        render()
        showPage(R.string.tab_expenses)

        compose.onNode(hasText(text(R.string.no_expenses_yet)) and hasAnyAncestor(hasTestTag("group_pane_expenses")))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.tap_add_first_expense)).assertIsDisplayed()
    }

    // --- People pane --------------------------------------------------------------------------------------

    @Test
    fun `people pane shows roles and lets only the creator remove others`() {
        val removed = mutableListOf<String>()
        render(GroupDetailActions(removeMember = { removed += it }))
        showPage(R.string.tab_members)

        compose.onNodeWithText("4 people").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.group_you_creator)).assertIsDisplayed()
        compose.onNodeWithText("Rahul").assertIsDisplayed()
        compose.onNodeWithTag("group_remove_$ME").assertDoesNotExist()
        compose.onNodeWithTag("group_remove_$RAHUL").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(GroupSheet.RemoveMember(RAHUL), sheet) }
        compose.onNodeWithText(text(R.string.remove_member_body, "Rahul")).assertIsDisplayed()
        compose.onNodeWithTag("group_confirm_remove").performClick()
        compose.runOnIdle {
            assertEquals(listOf(RAHUL), removed)
            assertNull(sheet)
        }
    }

    @Test
    fun `non-creator sees the creator badge and no remove buttons`() {
        state = state.copy(createdBy = RAHUL)
        render()
        showPage(R.string.tab_members)

        compose.onNodeWithText(text(R.string.group_creator)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.you)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.remove_member)).assertCountEquals(0)
    }

    // --- Top bar, FAB, sheets -----------------------------------------------------------------------------

    @Test
    fun `add expense button invokes the action`() {
        var added = 0
        render(GroupDetailActions(addExpense = { added++ }))

        compose.onNodeWithTag("group_add_expense").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(1, added) }
    }

    @Test
    fun `invite sheet merges QR, copy, share and the bearer key warning`() {
        var copies = 0
        var shares = 0
        render(GroupDetailActions(copyInvite = { copies++ }, share = { shares++ }))

        compose.onNodeWithTag("group_invite").performClick()

        compose.runOnIdle { assertEquals(GroupSheet.Invite, sheet) }
        compose.onNodeWithText("Invite to Goa trip").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.share_invite_warning)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.group_invite_copy)).assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(
                text(R.string.invite_qr_content_desc)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("group_share_invite").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, shares)
            assertEquals(GroupSheet.Invite, sheet)
        }
        compose.onNodeWithTag("group_copy_invite").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, copies)
            assertNull(sheet)
        }
    }

    @Test
    fun `invite actions stay disabled until a link exists`() {
        inviteLink = null
        render()
        sheet = GroupSheet.Invite

        compose.onNodeWithTag("group_copy_invite").assertIsNotEnabled()
        compose.onNodeWithTag("group_share_invite").assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.generating_invite_link)).assertExists()
    }

    @Test
    fun `invite refreshes on opening and foreground return but not other sheets`() {
        var refreshes = 0
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                InviteRefreshEffect(sheet) { refreshes++ }
            }
        }
        compose.runOnIdle {
            assertEquals(0, refreshes)
            sheet = GroupSheet.Invite
        }
        compose.runOnIdle {
            assertEquals(1, refreshes)
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle {
            assertEquals(2, refreshes)
            sheet = GroupSheet.Tools
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test
    fun `invite failure is persistent and retryable without an obsolete QR`() {
        var retries = 0
        render(GroupDetailActions(retryInvite = { retries++ }))
        sheet = GroupSheet.Invite
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(text(R.string.invite_qr_content_desc))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            inviteLink = null
            state = state.copy(inviteError = UiMessage.Res(R.string.create_invite_failed))
        }
        compose.onNodeWithText(text(R.string.create_invite_failed)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.invite_qr_content_desc)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.generating_invite_link)).assertDoesNotExist()
        compose.onNodeWithTag("group_copy_invite").assertIsNotEnabled()
        compose.onNodeWithTag("group_share_invite").assertIsNotEnabled()
        capture("group-invite-error", overlay = compose.onNodeWithTag("group_sheet_invite"))
        compose.onNodeWithTag("group_retry_invite").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `invalidated invite hides previous QR while a replacement is loading`() {
        render()
        sheet = GroupSheet.Invite
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(text(R.string.invite_qr_content_desc))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { inviteLink = null }
        compose.onNodeWithContentDescription(text(R.string.invite_qr_content_desc)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.generating_invite_link)).assertExists()
        compose.onNodeWithTag("group_copy_invite").assertIsNotEnabled()
    }

    @Test
    fun `legacy relay invite error leads directly to editable relays without changing them`() {
        var begun = 0
        inviteLink = null
        state = state.copy(inviteError = UiMessage.Res(R.string.invite_relays_need_edit))
        val saved = state.relays
        render(GroupDetailActions(beginRelayEdit = { begun++ }))
        sheet = GroupSheet.Invite
        compose.onNodeWithText(text(R.string.invite_relays_need_edit)).assertIsDisplayed()
        compose.onNodeWithTag("group_invite_relays").performClick()
        compose.runOnIdle {
            assertEquals(1, begun)
            assertEquals(GroupSheet.SyncStatus, sheet)
            assertEquals(saved, state.relays)
        }
    }

    @Test
    fun `legacy overbudget relay editor disables Save until a deliberate replacement fits`() {
        val relays = listOf("wss://relay.test/" + "a".repeat(111), "wss://relay.test/" + "b".repeat(111))
        state = state.copy(relays = relays, draftRelays = relays)
        render()
        sheet = GroupSheet.SyncStatus
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.invite_relays_need_edit)).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(draftRelays = relays.drop(1)) }
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled()
        compose.runOnIdle { assertEquals(relays, state.relays) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h844dp-mdpi")
    fun `legacy invitation error remains recoverable at 200 percent text`() {
        RuntimeEnvironment.setFontScale(2f)
        inviteLink = null
        state = state.copy(inviteError = UiMessage.Res(R.string.invite_relays_need_edit))
        render()
        sheet = GroupSheet.Invite
        compose.onNodeWithTag("group_invite_relays").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("group_retry_invite").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("group_copy_invite").assertIsNotEnabled()
        capture(
            "group-invite-legacy-font200",
            expectedWidth = 360,
            overlay = compose.onNodeWithTag("group_sheet_invite")
        )
    }

    @Test
    fun `tools sheet leads to nearby sync and to the relay draft`() {
        var nearby = 0
        var begun = 0
        var checked = 0
        var cancelled = 0
        var saved = 0
        var resets = 0
        render(
            GroupDetailActions(
                nearbySync = { nearby++ },
                beginRelayEdit = { begun++ },
                checkAllRelays = { checked++ },
                cancelRelayEdit = { cancelled++ },
                resetRelays = { resets++ },
                saveRelays = {
                    saved++
                    it()
                }
            )
        )

        compose.onNodeWithTag("group_tools").performClick()
        compose.onNodeWithTag("group_tool_nearby").performClick()
        compose.runOnIdle {
            assertEquals(1, nearby)
            assertNull(sheet)
        }

        compose.onNodeWithTag("group_tools").performClick()
        compose.onNodeWithTag("group_tool_relays").performClick()
        compose.runOnIdle {
            assertEquals(GroupSheet.SyncStatus, sheet)
            assertEquals(1, begun)
            assertEquals(1, checked)
        }
        compose.onNodeWithText("Group relays (2)").assertIsDisplayed()
        compose.onNodeWithText("relay.one.example").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.relay_status_online)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.relay_status_idle)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.relay_remove)).assertCountEquals(2)
        // The creator can add from the curated list and return to the defaults.
        compose.onNodeWithTag("relay_add_toggle").assertIsDisplayed()
        compose.onNodeWithTag("relay_reset").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, resets) }

        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertNull(sheet)
        }

        sheet = GroupSheet.SyncStatus
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle {
            assertEquals(1, saved)
            assertNull(sheet)
        }
    }

    @Test
    fun `members only read the relay list`() {
        state = state.copy(createdBy = RAHUL)
        render()
        sheet = GroupSheet.SyncStatus

        compose.onNodeWithText(text(R.string.relay_editor_hint_member)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.relay_remove)).assertCountEquals(0)
        compose.onNodeWithTag("relay_add_toggle").assertDoesNotExist()
        compose.onNodeWithTag("relay_reset").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.save)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.done)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun `group sheets keep their confirmation controls reachable at 200 percent in landscape`() {
        RuntimeEnvironment.setFontScale(2f)
        render()

        sheet = GroupSheet.Settle(DebtTransaction(MEERA, ME, 80000, "INR"))
        val confirm = compose.onNodeWithTag("group_confirm_settle").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Confirm must keep a full touch target", confirm.boundsInRoot.height >= 48f)
        assertEquals("Confirm must not be clipped", confirm.size.height.toFloat(), confirm.boundsInRoot.height, 1f)
        capture(
            "group-sheet-settle-landscape-font200",
            expectedWidth = 640,
            overlay = compose.onNodeWithTag("group_sheet_settle"),
            expectedMinHeight = 300
        )

        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach"))
        compose.onNodeWithTag("group_expense_edit").assertIsDisplayed()
        compose.onNodeWithTag("group_expense_delete").assertIsDisplayed().performClick()
        compose.onNodeWithTag("group_confirm_delete").assertIsDisplayed()

        // The relay sheet body scrolls, so Save stays reachable even with the suggestions open.
        sheet = GroupSheet.SyncStatus
        compose.onNodeWithTag("relay_add_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("relay_suggestions").assertExists()
        val save = compose.onNodeWithText(text(R.string.save)).assertIsDisplayed().fetchSemanticsNode()
        assertTrue("Save must keep a full touch target", save.boundsInRoot.height >= 48f)
    }

    // --- Screenshots --------------------------------------------------------------------------------------

    @Test
    fun `renders the summary page in light`() {
        render()
        capture("group-summary-light")
    }

    @Test
    fun `renders the summary page in dark`() {
        render(dark = true)
        capture("group-summary-dark")
    }

    @Test
    fun `renders the expenses page`() {
        render()
        showPage(R.string.tab_expenses)
        capture("group-expenses-light")
    }

    @Test
    fun `renders the people page`() {
        render()
        showPage(R.string.tab_members)
        capture("group-people-light")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h844dp-mdpi")
    fun `renders the summary page at 200 percent text on a 360dp phone`() {
        RuntimeEnvironment.setFontScale(2f)
        render()
        compose.runOnIdle { assertEquals("Compose must actually use 200 percent text", 2f, renderedFontScale, 0f) }
        compose.onNodeWithTag("group_add_expense").assertIsDisplayed()
        capture("group-summary-font200", expectedWidth = 360)
    }

    @Test
    fun `renders the invite sheet`() {
        render()
        sheet = GroupSheet.Invite
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(
                text(R.string.invite_qr_content_desc)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        capture("group-sheet-invite-light", overlay = compose.onNodeWithTag("group_sheet_invite"))
    }

    @Test
    fun `renders the settle sheet`() {
        render()
        sheet = GroupSheet.Settle(DebtTransaction(MEERA, ME, 80000, "INR"))
        compose.onNodeWithTag("group_confirm_settle").assertIsDisplayed()
        capture("group-sheet-settle-light", overlay = compose.onNodeWithTag("group_sheet_settle"))
    }

    @Test
    fun `renders the relays sheet`() {
        render()
        sheet = GroupSheet.SyncStatus
        compose.onNodeWithText(text(R.string.save)).assertIsDisplayed()
        capture("group-sheet-relays-light", overlay = compose.onNodeWithTag("group_sheet_relays"))
    }

    @Test
    fun `renders the expense detail sheet`() {
        render()
        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach"))
        compose.onNodeWithText(text(R.string.done)).assertIsDisplayed()
        capture("group-sheet-expense-light", overlay = compose.onNodeWithTag("group_sheet_expense"))
    }

    @Test
    fun `renders the delete expense confirmation`() {
        render()
        sheet = GroupSheet.ExpenseDetail(ExpenseIdentity(ME, "beach"))
        compose.onNodeWithTag("group_expense_delete").performClick()
        compose.onNodeWithTag("group_confirm_delete").assertIsDisplayed()
        capture("group-sheet-delete-expense-light", overlay = compose.onNodeWithTag("group_sheet_delete_expense"))
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private fun render(actions: GroupDetailActions = GroupDetailActions(), dark: Boolean = false) {
        compose.setContent {
            contentView = LocalView.current
            renderedFontScale = LocalDensity.current.fontScale
            val snackbarHostState = remember { SnackbarHostState() }
            SplitFreeTheme(darkTheme = dark) {
                GroupDetailContent(
                    state = state,
                    inviteLink = inviteLink,
                    relayStatuses = mapOf("wss://relay.one.example" to RelayCheckStatus.ONLINE),
                    relayInfo = mapOf("wss://relay.one.example" to RelayInfo(latencyMs = 0)),
                    selectedCurrency = selectedCurrency,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    actions = actions.copy(selectCurrency = {
                        selectedCurrency = it
                        actions.selectCurrency(it)
                    }),
                    sheet = sheet,
                    onSheet = { sheet = it },
                    snackbarHostState = snackbarHostState
                )
            }
        }
        compose.onNodeWithText("Goa trip").assertIsDisplayed()
    }

    /** Scrolls the whole screen (header + pane) vertically by [dy] pixels. */
    private fun scrollScreen(dy: Float) {
        compose.onNodeWithTag("group_body").performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, dy)
        }
        compose.waitForIdle()
    }

    private fun showPage(tab: Int) {
        compose.onNodeWithText(text(tab)).performClick()
        compose.waitForIdle()
    }

    private fun text(resource: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(resource, *args)

    private fun debtTag(from: String, to: String, currency: String = "INR") = "group_debt_$from:$to:$currency"

    /**
     * Draws the main window and, for sheets, the sheet's own window on top: a modal sheet lives in a second
     * window whose view is reachable only through one of its semantics nodes.
     */
    private fun capture(
        name: String,
        expectedWidth: Int = 390,
        overlay: SemanticsNodeInteraction? = null,
        expectedMinHeight: Int = 600
    ) {
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
        assertTrue("Capture must have screen height", bitmap.height >= expectedMinHeight)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Capture must contain rendered content, not a blank bitmap", pixels.toSet().size > 16)
        val output = File("build/outputs/ui-screenshots/$name.png")
        val directory = requireNotNull(output.parentFile)
        assertTrue("Screenshot directory must exist", directory.isDirectory || directory.mkdirs())
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot PNG must be nonempty", output.length() > 0)
    }

    private companion object {
        const val ME = "me00000000000000000000000000000000000000000000000000000000000001"
        const val RAHUL = "rahul000000000000000000000000000000000000000000000000000000000002"
        const val MEERA = "meera000000000000000000000000000000000000000000000000000000000003"
        const val SAM = "sam0000000000000000000000000000000000000000000000000000000000004"
        const val INVITE_LINK = "splitfree://join?g=demo&k=fixture"
        const val DAY_SECONDS = 86_400L

        fun readyState(): GroupDetailUiState {
            val now = System.currentTimeMillis() / 1000
            fun expense(
                id: String,
                amount: Long,
                paidBy: String,
                description: String,
                category: String,
                shares: Map<String, Long>,
                ageSeconds: Long,
                currency: String = "INR"
            ) = Expense(
                id = id,
                amount = amount,
                currency = currency,
                description = description,
                paidBy = paidBy,
                splitType = SplitType.EQUAL,
                splitAmong = shares.map { (key, share) -> SplitEntry(key, share) },
                timestamp = now - ageSeconds,
                category = category
            ).let { AuthoredExpense(it, paidBy) }
            val equal = mapOf(ME to 0L, RAHUL to 0L, MEERA to 0L, SAM to 0L)
            return GroupDetailUiState(
                groupId = "group-1",
                groupName = "Goa trip",
                memberCount = 4,
                members = listOf(ME, RAHUL, MEERA, SAM),
                memberNames = mapOf(ME to "Priya", RAHUL to "Rahul", MEERA to "Meera", SAM to "Sam"),
                createdBy = ME,
                myPubkey = ME,
                relays = listOf("wss://relay.one.example", "wss://relay.two.example"),
                debts =
                listOf(
                    DebtTransaction(MEERA, ME, 80000, "INR"),
                    DebtTransaction(SAM, ME, 160000, "INR"),
                    DebtTransaction(ME, RAHUL, 30000, "INR"),
                    DebtTransaction(SAM, RAHUL, 50000, "INR"),
                    DebtTransaction(ME, RAHUL, 6000, "USD")
                ),
                expenses =
                listOf(
                    expense("beach", 400000, ME, "Beach stay", "rent", equal.mapValues { 100000L }, 3 * DAY_SECONDS),
                    expense("dinner", 160000, RAHUL, "Dinner", "food", equal.mapValues { 40000L }, 2 * DAY_SECONDS),
                    expense("taxi", 80000, MEERA, "Airport taxi", "transport", equal.mapValues { 20000L }, DAY_SECONDS),
                    expense(
                        "snacks",
                        30000,
                        SAM,
                        "Snacks",
                        "food",
                        mapOf(SAM to 15000L, MEERA to 15000L),
                        4 * DAY_SECONDS
                    ),
                    expense(
                        "museum",
                        6000,
                        RAHUL,
                        "Museum tickets",
                        "entertainment",
                        mapOf(ME to 3000L, RAHUL to 3000L),
                        5 * 3_600L,
                        currency = "USD"
                    )
                )
            )
        }
    }
}
