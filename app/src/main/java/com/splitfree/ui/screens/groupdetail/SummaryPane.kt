package com.splitfree.ui.screens.groupdetail

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
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MemberAvatar
import com.splitfree.ui.components.MoneyText
import com.splitfree.ui.components.SectionHead
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.SignedMoneyText
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.GroupDetailUiState

private val OwedRowMinHeight = 79.dp
private val RecordButtonMaxWidth = 132.dp
private const val RECENT_EXPENSE_COUNT = 3

/**
 * Summary tab: who pays whom in [currency] followed by the three most recent expenses in that currency, so
 * checking the last expense never needs a tab switch. "Record payment" appears only on debts I am party to.
 */
@Composable
internal fun SummaryPane(
    state: GroupDetailUiState,
    currency: String?,
    onSettle: (DebtTransaction) -> Unit,
    onOpenExpense: (String) -> Unit,
    onSeeAll: () -> Unit
) {
    val debts = remember(state.debts, currency) {
        state.debts.filter { it.currency == currency }.distinctBy(::debtKey)
    }
    val recent = remember(state.expenses, currency) {
        state.expenses
            .filter { it.currency == currency }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            .take(RECENT_EXPENSE_COUNT)
    }
    val hasCurrencyExpenses = remember(state.expenses, currency) { state.expenses.any { it.currency == currency } }
    val debtRows = rememberDebtRows(debts, state)
    val recentRows = rememberExpenseRows(recent, state)

    Column(Modifier.fillMaxWidth().testTag("group_pane_summary")) {
        SectionHead(title = stringResource(R.string.group_who_pays_whom)) {
            SectionMeta(pluralStringResource(R.plurals.group_open_debts, debts.size, debts.size))
        }
        if (debts.isEmpty()) {
            HintCard(
                text =
                if (currency != null && hasCurrencyExpenses) {
                    stringResource(R.string.group_summary_settled_in, currency)
                } else if (currency != null) {
                    stringResource(R.string.group_no_balances_in_currency, currency)
                } else {
                    stringResource(R.string.group_no_balances_hint)
                },
                icon = Icons.Outlined.CheckCircle
            )
        } else {
            SfListCard {
                debtRows.forEachIndexed { index, row ->
                    if (index > 0) SfDivider()
                    OwedRow(row = row, myPubkey = state.myPubkey, onSettle = { onSettle(row.debt) })
                }
            }
        }

        SectionHead(title = stringResource(R.string.group_recent_activity)) {
            SfTextButton(
                text = stringResource(R.string.group_see_all),
                onClick = onSeeAll,
                modifier = Modifier.testTag("group_see_all")
            )
        }
        if (recent.isEmpty()) {
            HintCard(
                text =
                if (currency != null) {
                    stringResource(R.string.group_summary_no_currency_expenses, currency)
                } else {
                    stringResource(R.string.tap_add_first_expense)
                },
                icon = Icons.Outlined.Receipt
            )
        } else {
            SfListCard {
                recentRows.forEachIndexed { index, row ->
                    if (index > 0) SfDivider()
                    ExpenseRow(row = row, onClick = { onOpenExpense(row.expense.id) })
                }
            }
        }
    }
}

/** Right-hand count text for a [SectionHead]. */
@Composable
internal fun SectionMeta(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Names for one debt row, resolved once per list change instead of on every recomposition. */
internal data class DebtRowModel(
    val debt: DebtTransaction,
    val fromName: String,
    val toName: String,
    val otherPubkey: String,
    val otherName: String?
)

@Composable
private fun rememberDebtRows(debts: List<DebtTransaction>, state: GroupDetailUiState): List<DebtRowModel> =
    remember(debts, state.myPubkey, state.memberNames, state.members) {
        debts.map { debt ->
            val other = if (debt.from == state.myPubkey) debt.to else debt.from
            DebtRowModel(
                debt = debt,
                fromName = disambiguatedMemberName(debt.from, state.memberNames, state.members),
                toName = disambiguatedMemberName(debt.to, state.memberNames, state.members),
                otherPubkey = other,
                otherName = state.memberNames[other]
            )
        }
    }

/**
 * One simplified debt: the other party's avatar, the sentence in words, the amount coloured by my direction
 * (neutral for debts between others) and, when I am a party, a capped "Record payment" button.
 */
@Composable
private fun OwedRow(row: DebtRowModel, myPubkey: String, onSettle: () -> Unit) {
    val debt = row.debt
    val mine = debt.involves(myPubkey)
    val sentence =
        when (myPubkey) {
            debt.to -> stringResource(R.string.group_debt_owes_you, row.fromName)
            debt.from -> stringResource(R.string.group_debt_you_owe, row.toName)
            else -> stringResource(R.string.group_debt_owes, row.fromName, row.toName)
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = OwedRowMinHeight)
            .padding(start = 14.dp, end = if (mine) 6.dp else 14.dp, top = 11.dp, bottom = 11.dp)
            .testTag("group_debt_${debtKey(debt)}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemberAvatar(pubkey = row.otherPubkey, name = row.otherName, size = 36.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(sentence, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            if (mine) {
                SignedMoneyText(
                    amountMinor = debt.signedFor(myPubkey),
                    currency = debt.currency,
                    style = MaterialTheme.typography.titleSmall
                )
            } else {
                MoneyText(
                    amountMinor = debt.amount,
                    currency = debt.currency,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (mine) {
            // Capped so the label wraps instead of starving the sentence column at large text.
            SfTextButton(
                text = stringResource(R.string.record_payment),
                onClick = onSettle,
                modifier = Modifier.widthIn(max = RecordButtonMaxWidth)
            )
        }
    }
}
