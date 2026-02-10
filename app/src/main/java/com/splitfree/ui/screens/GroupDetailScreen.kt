package com.splitfree.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.domain.model.DebtTransaction
import com.splitfree.ui.viewmodels.GroupDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onAddExpense: (String) -> Unit,
    onNearbySync: (String) -> Unit = {},
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettleDialog by remember { mutableStateOf<DebtTransaction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.groupName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onNearbySync(uiState.groupId) }) {
                        Icon(Icons.Default.Bluetooth, "Nearby sync")
                    }
                    IconButton(onClick = {
                        val link = viewModel.getInviteLink()
                        if (link != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Join my SplitFree group: $link")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share invite"))
                        }
                    }) {
                        Icon(Icons.Default.Share, "Share invite")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddExpense(uiState.groupId) }) {
                Icon(Icons.Default.Add, "Add expense")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Balances",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (uiState.debts.isEmpty()) {
                item {
                    Text(
                        "All settled up!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(uiState.debts) { debt ->
                    DebtCard(debt, onSettle = { showSettleDialog = debt })
                }
            }
            item {
                Text(
                    "Recent Expenses",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            items(uiState.expenses) { expense ->
                ListItem(
                    headlineContent = { Text(expense.description) },
                    supportingContent = { Text("${expense.currency} ${"%.2f".format(expense.amount / 100.0)}") },
                    trailingContent = if (expense.category.isNotBlank()) {{ Text(expense.category) }} else null
                )
            }
            if (uiState.expenses.isEmpty()) {
                item {
                    Text(
                        "No expenses yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    showSettleDialog?.let { debt ->
        AlertDialog(
            onDismissRequest = { showSettleDialog = null },
            title = { Text("Settle Up") },
            text = {
                Text("Record that ${debt.from.take(8)}… paid ${debt.currency} ${"%.2f".format(debt.amount / 100.0)} to ${debt.to.take(8)}…?")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.recordSettlement(debt)
                    showSettleDialog = null
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showSettleDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DebtCard(debt: DebtTransaction, onSettle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${debt.from.take(8)}… owes ${debt.to.take(8)}…")
                Text("${debt.currency} ${"%.2f".format(debt.amount / 100.0)}", style = MaterialTheme.typography.titleSmall)
            }
            TextButton(onClick = onSettle) { Text("Settle") }
        }
    }
}
