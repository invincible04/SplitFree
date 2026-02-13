package com.splitfree.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
    onNavigateToGroup: (String) -> Unit = {},
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettleDialog by remember { mutableStateOf<DebtTransaction?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showShareWarning by remember { mutableStateOf(false) }

    var showRemoveDialog by remember { mutableStateOf<String?>(null) }

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
                    val scope = rememberCoroutineScope()
                    IconButton(onClick = { onNearbySync(uiState.groupId) }) {
                        Icon(Icons.Default.Bluetooth, "Nearby sync")
                    }
                    IconButton(onClick = { showQrDialog = true }) {
                        Icon(Icons.Outlined.QrCode2, "Show QR")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val json = viewModel.exportGroupData()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(Intent.createChooser(intent, "Export group data"))
                        }
                    }) {
                        Icon(Icons.Outlined.FileDownload, "Export")
                    }
                    IconButton(onClick = { showShareWarning = true }) {
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
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        text = { Text("Members") })
                }

                when (selectedTab) {
                    0 -> BalancesTab(uiState.debts, hasExpenses = uiState.expenses.isNotEmpty(), onSettle = { showSettleDialog = it })
                    1 -> ExpensesTab(uiState.expenses)
                    2 -> MembersTab(
                        members = uiState.members,
                        createdBy = uiState.createdBy,
                        isCreator = uiState.myPubkey == uiState.createdBy,
                        myPubkey = uiState.myPubkey,
                        onRemove = { showRemoveDialog = it }
                    )
                }
            }

            // Invite button at bottom-left
            SmallFloatingActionButton(
                    onClick = { showShareWarning = true },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = "Invite members")
                }
        }
    }

    if (showShareWarning) {
        AlertDialog(
            onDismissRequest = { showShareWarning = false },
            title = { Text("Share invite link?") },
            text = { Text("This link contains the group encryption key. Anyone with this link can join and read all expenses. Share only via private messages — avoid public channels or group chats where bots may preview the URL.") },
            confirmButton = {
                TextButton(onClick = {
                    showShareWarning = false
                    val link = viewModel.getInviteLink()
                    if (link != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join my SplitFree group: $link")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share invite"))
                    }
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { showShareWarning = false }) { Text("Cancel") }
            }
        )
    }

    if (showQrDialog) {
        val link = viewModel.getInviteLink()
        if (link != null) {
            val qrBitmap = remember(link) { com.splitfree.domain.crypto.QrGenerator.encode(link) }
            AlertDialog(
                onDismissRequest = { showQrDialog = false },
                title = { Text("Invite QR Code") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Invite QR code",
                            modifier = Modifier.size(256.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Scan to join ${uiState.groupName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQrDialog = false }) { Text("Done") }
                }
            )
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

    showRemoveDialog?.let { pubkey ->
        AlertDialog(
            onDismissRequest = { showRemoveDialog = null },
            title = { Text("Remove Member") },
            text = { Text("Remove ${pubkey.take(8)}…? This creates a new group without them. All remaining members will be migrated automatically.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = null
                    viewModel.removeMember(pubkey) { newGroupId -> onNavigateToGroup(newGroupId) }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BalancesTab(debts: List<DebtTransaction>, hasExpenses: Boolean, onSettle: (DebtTransaction) -> Unit) {
    if (debts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (hasExpenses) Icons.Outlined.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = if (hasExpenses) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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

@Composable
private fun MembersTab(
    members: List<String>,
    createdBy: String,
    isCreator: Boolean,
    myPubkey: String,
    onRemove: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(members) { pubkey ->
            ListItem(
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pubkey.take(8) + "…" + pubkey.takeLast(4))
                        if (pubkey == createdBy) {
                            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("Creator", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (pubkey == myPubkey) {
                            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text("You", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                trailingContent = {
                    if (isCreator && pubkey != myPubkey) {
                        IconButton(onClick = { onRemove(pubkey) }) {
                            Icon(Icons.Outlined.PersonRemove, "Remove member",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    }
}
