package com.splitfree.ui.screens.expense

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
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

@Preview(name = "Edit expense", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun EditExpensePreview() {
    SplitFreeTheme { AddExpenseContent(previewState().copy(editing = true, dirty = false), ExpenseEditorActions()) }
}

@Preview(name = "Empty draft", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun AddExpenseEmptyPreview() {
    SplitFreeTheme {
        AddExpenseContent(
            previewState().copy(amount = "", description = "", previewSplits = emptyList()),
            ExpenseEditorActions()
        )
    }
}

@Preview(name = "Validation + save error", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun AddExpenseErrorsPreview() {
    SplitFreeTheme {
        AddExpenseContent(
            previewState().copy(
                amount = "abc",
                description = "",
                previewSplits = emptyList(),
                amountError = UiMessage.Res(R.string.expense_amount_invalid),
                descriptionError = UiMessage.Res(R.string.expense_description_required),
                error = UiMessage.Res(R.string.expense_save_failed)
            ),
            ExpenseEditorActions()
        )
    }
}

@Preview(name = "Saving", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun AddExpenseSavingPreview() {
    SplitFreeTheme { AddExpenseContent(previewState().copy(saving = true), ExpenseEditorActions()) }
}
