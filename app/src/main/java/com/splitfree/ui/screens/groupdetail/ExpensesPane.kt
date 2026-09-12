package com.splitfree.ui.screens.groupdetail

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.Expense
import com.splitfree.ui.components.CategoryIcon
import com.splitfree.ui.components.EmptyState
import com.splitfree.ui.components.MoneyText
import com.splitfree.ui.components.SectionHead
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.GroupDetailUiState
import com.splitfree.util.CurrencyFormatter

private val ExpenseRowMinHeight = 82.dp
private val ExpenseValueMaxWidth = 120.dp
private const val SECONDS_TO_MILLIS = 1000L

/** Expenses tab: every expense in [currency], newest first. */
@Composable
internal fun ExpensesPane(state: GroupDetailUiState, currency: String?, onOpenExpense: (String) -> Unit) {
    val expenses = remember(state.expenses, currency) {
        state.expenses.filter { it.currency == currency }.distinctBy { it.id }.sortedByDescending { it.timestamp }
    }
    Column(Modifier.fillMaxWidth().testTag("group_pane_expenses")) {
        SectionHead(title = stringResource(R.string.group_expense_history)) {
            SectionMeta(pluralStringResource(R.plurals.group_expense_entries, expenses.size, expenses.size))
        }
        when {
            state.expenses.isEmpty() || currency == null ->
                EmptyState(
                    icon = Icons.Outlined.Receipt,
                    title = stringResource(R.string.no_expenses_yet),
                    body = stringResource(R.string.tap_add_first_expense)
                )
            expenses.isEmpty() ->
                EmptyState(
                    icon = Icons.Outlined.Receipt,
                    title = stringResource(R.string.group_summary_no_currency_expenses, currency),
                    body = stringResource(R.string.tap_add_first_expense)
                )
            else -> {
                val rows = rememberExpenseRows(expenses, state)
                SfListCard {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) SfDivider()
                        ExpenseRow(row = row, onClick = { onOpenExpense(row.expense.id) })
                    }
                }
            }
        }
    }
}

/**
 * Display strings for one expense row, computed once per list change rather than on every recomposition:
 * name lookup, relative time and money formatting are the expensive parts of drawing a row.
 */
internal data class ExpenseRowModel(
    val expense: Expense,
    val payer: String,
    val relativeTime: String,
    val delta: PersonalDelta,
    val deltaAmount: String?
)

/** Builds [ExpenseRowModel]s for [expenses], keyed on everything the strings depend on. */
@Composable
internal fun rememberExpenseRows(expenses: List<Expense>, state: GroupDetailUiState): List<ExpenseRowModel> {
    val you = stringResource(R.string.you)
    return remember(expenses, state.myPubkey, state.memberNames, state.members, you) {
        val now = System.currentTimeMillis()
        expenses.map { expense ->
            val delta = expense.personalDelta(state.myPubkey)
            ExpenseRowModel(
                expense = expense,
                payer =
                if (expense.paidBy == state.myPubkey) {
                    you
                } else {
                    disambiguatedMemberName(expense.paidBy, state.memberNames, state.members)
                },
                relativeTime =
                DateUtils.getRelativeTimeSpanString(
                    expense.timestamp * SECONDS_TO_MILLIS,
                    now,
                    DateUtils.MINUTE_IN_MILLIS
                ).toString(),
                delta = delta,
                deltaAmount =
                when (delta) {
                    is PersonalDelta.Lent -> CurrencyFormatter.format(delta.amount, expense.currency)
                    is PersonalDelta.Share -> CurrencyFormatter.format(delta.amount, expense.currency)
                    PersonalDelta.NoChange, PersonalDelta.NotInvolved -> null
                }
            )
        }
    }
}

/**
 * One expense (min 82dp): category tile, description, "Payer paid · when" and, on the right, the amount over
 * what it did to my balance ("you lent" / "your share" / "not involved"). Tapping opens the detail sheet.
 * Shared by the summary and expenses panes.
 */
@Composable
internal fun ExpenseRow(row: ExpenseRowModel, onClick: () -> Unit) {
    val expense = row.expense
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = ExpenseRowMinHeight)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("group_expense_${expense.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryIcon(category = expense.category)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                expense.description,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.group_expense_payer_meta, row.payer, row.relativeTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = ExpenseValueMaxWidth), horizontalAlignment = Alignment.End) {
            MoneyText(
                amountMinor = expense.amount,
                currency = expense.currency,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                personalDeltaText(row.delta, row.deltaAmount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun personalDeltaText(delta: PersonalDelta, amount: String?): String = when (delta) {
    is PersonalDelta.Lent -> stringResource(R.string.group_delta_lent, amount.orEmpty())
    is PersonalDelta.Share -> stringResource(R.string.group_delta_share, amount.orEmpty())
    PersonalDelta.NoChange -> stringResource(R.string.group_delta_no_change)
    PersonalDelta.NotInvolved -> stringResource(R.string.group_delta_not_involved)
}
