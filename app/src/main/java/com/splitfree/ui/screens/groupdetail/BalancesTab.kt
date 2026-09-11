package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.util.CurrencyFormatter

@Composable
fun BalancesTab(
    debts: List<DebtTransaction>,
    hasExpenses: Boolean,
    myPubkey: String = "",
    memberNames: Map<String, String> = emptyMap(),
    members: Collection<String> = emptyList(),
    onSettle: (DebtTransaction) -> Unit
) {
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    // A (from, to, currency) triple identifies one simplified debt; duplicates would crash LazyColumn.
    val uniqueDebts = remember(debts) { debts.distinctBy { debtKey(it) } }

    if (uniqueDebts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(tokens.emptyStatePadding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.emptyStateIcon),
                    tint = if (hasExpenses) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
                Spacer(Modifier.height(tokens.fieldSpacing))
                Text(
                    if (hasExpenses) stringResource(R.string.all_settled) else stringResource(R.string.no_balances_yet),
                    style = if (adaptive.isCompact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!hasExpenses) {
                    Text(
                        stringResource(R.string.add_expense_to_see_balances),
                        style = if (adaptive.isCompact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(tokens.screenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(tokens.fieldSpacing)
        ) {
            items(uniqueDebts, key = { debtKey(it) }) { debt ->
                DebtCard(
                    debt,
                    showSettle = debt.from == myPubkey || debt.to == myPubkey,
                    memberNames = memberNames,
                    members = members,
                    onSettle = { onSettle(debt) }
                )
            }
            item { Spacer(Modifier.height(tokens.listBottomSpacer)) }
        }
    }
}

private fun debtKey(debt: DebtTransaction): String = "${debt.from}:${debt.to}:${debt.currency}"

@Composable
fun DebtCard(
    debt: DebtTransaction,
    showSettle: Boolean = true,
    memberNames: Map<String, String> = emptyMap(),
    members: Collection<String> = emptyList(),
    onSettle: () -> Unit
) {
    val tokens = adaptiveSizeTokens()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(tokens.cardPadding).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
                ) {
                    PubkeyChip(debt.from, memberNames, members)
                    Text(
                        stringResource(R.string.owes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    PubkeyChip(debt.to, memberNames, members)
                }
                Spacer(Modifier.height(tokens.itemSpacing))
                Text(
                    CurrencyFormatter.format(debt.amount, debt.currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (showSettle) {
                FilledTonalButton(onClick = onSettle) {
                    Text(stringResource(R.string.settle))
                }
            }
        }
    }
}
