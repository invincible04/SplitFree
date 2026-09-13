package com.splitfree.ui.screens.groupdetail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.GroupDetailUiState

private const val ME = "preview-self"
private const val RAHUL = "preview-rahul"
private const val MEERA = "preview-meera"
private const val SAM = "preview-sam"
private const val DAY_SECONDS = 86_400L
private const val PREVIEW_RELAY = "wss://relay.example"

/** Synthetic preview data lives in the debug source set, never in the release app. */
private fun previewGroup(): GroupDetailUiState {
    val now = System.currentTimeMillis() / 1000
    val members = listOf(ME, RAHUL, MEERA, SAM)
    fun expense(id: String, amount: Long, paidBy: String, title: String, category: String, age: Long) = Expense(
        id = id,
        amount = amount,
        currency = "INR",
        description = title,
        paidBy = paidBy,
        splitType = SplitType.EQUAL,
        splitAmong = members.map { SplitEntry(it, amount / members.size) },
        timestamp = now - age,
        category = category
    ).let { AuthoredExpense(it, paidBy) }
    return GroupDetailUiState(
        groupId = "preview-group",
        groupName = "Goa trip",
        memberCount = members.size,
        members = members,
        memberNames = mapOf(ME to "Priya", RAHUL to "Rahul", MEERA to "Meera", SAM to "Sam"),
        createdBy = ME,
        myPubkey = ME,
        relays = listOf(PREVIEW_RELAY),
        debts = listOf(
            DebtTransaction(MEERA, ME, 80000, "INR"),
            DebtTransaction(SAM, ME, 160000, "INR"),
            DebtTransaction(ME, RAHUL, 30000, "INR"),
            DebtTransaction(ME, RAHUL, 6000, "USD")
        ),
        expenses = listOf(
            expense("beach", 400000, ME, "Beach stay", "rent", 3 * DAY_SECONDS),
            expense("dinner", 160000, RAHUL, "Dinner", "food", 2 * DAY_SECONDS),
            expense("taxi", 80000, MEERA, "Airport taxi", "transport", DAY_SECONDS)
        )
    )
}

@Composable
private fun GroupDetailPreviewHost(state: GroupDetailUiState, sheet: GroupSheet? = null, tab: Int = TAB_SUMMARY) {
    var selectedTab by rememberSaveable { mutableIntStateOf(tab) }
    SplitFreeTheme {
        GroupDetailContent(
            state = state,
            inviteLink = "splitfree://join?d=preview",
            relayStatuses = mapOf(PREVIEW_RELAY to RelayCheckStatus.ONLINE),
            relayInfo = mapOf(PREVIEW_RELAY to RelayInfo(latencyMs = 120)),
            selectedCurrency = null,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            actions = GroupDetailActions(),
            sheet = sheet,
            onSheet = {},
            snackbarHostState = remember { SnackbarHostState() }
        )
    }
}

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun GroupDetailPreview() {
    GroupDetailPreviewHost(previewGroup())
}

@Preview(name = "Expenses tab", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun GroupDetailExpensesPreview() {
    GroupDetailPreviewHost(previewGroup(), tab = TAB_EXPENSES)
}

@Preview(name = "People tab", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun GroupDetailPeoplePreview() {
    GroupDetailPreviewHost(previewGroup(), tab = TAB_PEOPLE)
}

@Preview(name = "Fresh group", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun GroupDetailEmptyPreview() {
    GroupDetailPreviewHost(
        GroupDetailUiState(
            groupName = "Goa trip",
            members = listOf(ME),
            memberNames = mapOf(ME to "Priya"),
            myPubkey = ME
        )
    )
}
