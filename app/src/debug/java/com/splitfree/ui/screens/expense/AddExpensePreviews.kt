package com.splitfree.ui.screens.expense

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.AddExpenseUiState

/** Synthetic preview data lives in the debug source set, never in the release app. */
private fun previewState(): AddExpenseUiState {
    val members = listOf("preview-self", "preview-1", "preview-2", "preview-3")
    return AddExpenseUiState(
        loading = false,
        editable = true,
        groupName = "Example group",
        members = members,
        memberNames = members.drop(1).mapIndexed { index, key -> key to "Member ${index + 1}" }.toMap(),
        myPubkey = members.first(),
        paidBy = members.first(),
        participants = members.toSet(),
        amount = "1200",
        description = "Shared meal",
        currency = "INR",
        dirty = true,
        previewSplits = members.map { SplitEntry(it, 30000) }
    )
}

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun AddExpensePreview() {
    SplitFreeTheme { AddExpenseContent(previewState(), ExpenseEditorActions()) }
}
