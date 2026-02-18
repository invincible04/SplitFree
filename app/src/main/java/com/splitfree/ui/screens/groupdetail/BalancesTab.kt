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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.splitfree.domain.model.expense.DebtTransaction

@Composable
fun BalancesTab(
    debts: List<DebtTransaction>,
    hasExpenses: Boolean,
    myPubkey: String = "",
    onSettle: (DebtTransaction) -> Unit
) {
    if (debts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = if (hasExpenses) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (hasExpenses) "All settled up! 🎉" else "No balances yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!hasExpenses) {
                    Text(
                        "Add an expense to see balances",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(debts) { debt ->
                DebtCard(debt, showSettle = debt.from == myPubkey || debt.to == myPubkey, onSettle = { onSettle(debt) })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun DebtCard(debt: DebtTransaction, showSettle: Boolean = true, onSettle: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PubkeyChip(debt.from)
                    Text("owes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    PubkeyChip(debt.to)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    com.splitfree.util.CurrencyFormatter
                        .format(debt.amount, debt.currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (showSettle) {
                FilledTonalButton(onClick = onSettle) {
                    Text("Settle")
                }
            }
        }
    }
}
