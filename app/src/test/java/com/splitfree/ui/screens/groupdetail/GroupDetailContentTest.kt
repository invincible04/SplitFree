package com.splitfree.ui.screens.groupdetail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
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
import androidx.compose.ui.test.performSemanticsAction
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
                expenses = state.expenses + extraExpenses
            )
        render()
        showPage(R.string.tab_expenses)
        scrollScreen(2_000f)
        compose.onNodeWithTag("group_expense_${ME}_extra1").assertIsDisplayed()
        compose.onNodeWithTag("group_tabs").assertIsNotDisplayed()

        // The tab is off screen, so select it the way accessibility services would.
        compose.onNodeWithText(text(R.string.tab_members)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNodeWithTag("group_pane_people").assertIsDisplayed()
        compose.onNodeWithTag("group_tabs").assertIsDisplayed()
        compose.onNodeWithTag("group_people_invite").assertIsDisplayed()
        compose.onNodeWithTag("group_pane_expenses").assertDoesNotExist()
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
    fun `tools sheet leads to nearby sync and to the relay draft`() {
        var nearby = 0
        var begun = 0
        var checked = 0
        var cancelled = 0
        var saved = 0
        render(
            GroupDetailActions(
                nearbySync = { nearby++ },
                beginRelayEdit = { begun++ },
                checkAllRelays = { checked++ },
                cancelRelayEdit = { cancelled++ },
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
        compose.onNodeWithText(text(R.string.save)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.done)).assertIsDisplayed()
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
        compose.onNodeWithTag("group_scroll").performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
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
    private fun capture(name: String, expectedWidth: Int = 390, overlay: SemanticsNodeInteraction? = null) {
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
