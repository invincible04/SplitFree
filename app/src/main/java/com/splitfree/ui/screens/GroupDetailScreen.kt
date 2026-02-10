package com.splitfree.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.domain.model.DebtTransaction
import com.splitfree.domain.model.Expense
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
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        uiState.groupName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
            ExtendedFloatingActionButton(
                onClick = { onAddExpense(uiState.groupId) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Expense") }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Tabs
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text("Balances") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text("Expenses") })
                }

                when (selectedTab) {
                    0 -> BalancesTab(uiState.debts, onSettle = { showSettleDialog = it })
                    1 -> ExpensesTab(uiState.expenses)
                }
            }

            // Invite button at bottom-left
            SmallFloatingActionButton(
                    onClick = {
                        val link = viewModel.getInviteLink()
                        if (link != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Join my SplitFree group: $link")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share invite"))
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = "Invite members")
                }
        }
    }

    showSettleDialog?.let { debt ->
        AlertDialog(
            onDismissRequest = { showSettleDialog = null },
            title = { Text("Settle Up") },
            text = {
                Column {
                    Text("Record payment:")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PubkeyChip(debt.from)
                        Text(" → ", style = MaterialTheme.typography.titleMedium)
                        PubkeyChip(debt.to)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatAmount(debt.amount, debt.currency),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.recordSettlement(debt)
                    showSettleDialog = null
                }) { Text("Confirm Payment") }
            },
            dismissButton = {
                TextButton(onClick = { showSettleDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BalancesTab(debts: List<DebtTransaction>, onSettle: (DebtTransaction) -> Unit) {
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
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "All settled up! 🎉",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(debts) { debt ->
                DebtCard(debt, onSettle = { onSettle(debt) })
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun ExpensesTab(expenses: List<Expense>) {
    if (expenses.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Receipt,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No expenses yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Tap + to add the first expense",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(expenses) { expense ->
                ExpenseRow(expense)
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun DebtCard(debt: DebtTransaction, onSettle: () -> Unit) {
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
                    formatAmount(debt.amount, debt.currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            FilledTonalButton(onClick = onSettle) {
                Text("Settle")
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense) {
    val categoryEmoji = when (expense.category.lowercase()) {
        "food" -> "🍕"
        "transport", "travel" -> "🚗"
        "shopping" -> "🛍️"
        "entertainment" -> "🎬"
        "utilities" -> "💡"
        "rent", "housing" -> "🏠"
        "health" -> "💊"
        else -> "💰"
    }

    ListItem(
        headlineContent = {
            Text(
                expense.description,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                "Paid by ${expense.paidBy.take(6)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(categoryEmoji)
            }
        },
        trailingContent = {
            Text(
                formatAmount(expense.amount, expense.currency),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    )
}

@Composable
private fun PubkeyChip(pubkey: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp
    ) {
        Text(
            text = pubkey.take(6) + "…",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun formatAmount(amountCents: Long, currency: String): String {
    val symbol = when (currency.uppercase()) {
        "INR" -> "₹"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "JPY" -> "¥"
        else -> currency
    }
    return "$symbol${"%.2f".format(amountCents / 100.0)}"
}
