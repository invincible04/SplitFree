package com.splitfree.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.domain.model.SplitType
import com.splitfree.ui.viewmodels.AddExpenseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onExpenseAdded: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("INR") }
    var currencyExpanded by remember { mutableStateOf(false) }
    var paidBy by remember { mutableStateOf("") }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    var memberInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var category by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }

    val currencies = listOf("INR", "USD", "EUR", "GBP", "JPY", "AUD", "CAD")
    val categories = listOf(
        "" to "None",
        "food" to "🍕 Food",
        "transport" to "🚗 Transport",
        "shopping" to "🛍️ Shopping",
        "entertainment" to "🎬 Entertainment",
        "utilities" to "💡 Utilities",
        "rent" to "🏠 Rent",
        "health" to "💊 Health",
        "other" to "💰 Other"
    )

    LaunchedEffect(uiState.myPubkey) {
        if (paidBy.isEmpty() && uiState.myPubkey.isNotEmpty()) paidBy = uiState.myPubkey
    }
    LaunchedEffect(uiState.members) {
        if (uiState.members.isNotEmpty() && memberInputs.isEmpty()) {
            memberInputs = uiState.members.associateWith { "" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Expense") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Amount + Currency row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium
                )
                ExposedDropdownMenuBox(
                    expanded = currencyExpanded,
                    onExpandedChange = { currencyExpanded = it },
                    modifier = Modifier.width(110.dp)
                ) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Currency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        shape = MaterialTheme.shapes.medium
                    )
                    ExposedDropdownMenu(
                        expanded = currencyExpanded,
                        onDismissRequest = { currencyExpanded = false }
                    ) {
                        currencies.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = { currency = c; currencyExpanded = false }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                placeholder = { Text("What was this for?") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // Category picker
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = categories.find { it.first == category }?.second ?: "None",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = MaterialTheme.shapes.medium
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    categories.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { category = key; categoryExpanded = false }
                        )
                    }
                }
            }

            // Paid by
            if (uiState.members.size > 1) {
                Text("Paid by", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    uiState.members.forEachIndexed { index, pk ->
                        SegmentedButton(
                            selected = paidBy == pk,
                            onClick = { paidBy = pk },
                            shape = SegmentedButtonDefaults.itemShape(index, uiState.members.size)
                        ) {
                            Text(
                                if (pk == uiState.myPubkey) "Me" else pk.take(6) + "…",
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Split type
            Text("Split type", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val labels = mapOf(
                    SplitType.EQUAL to "Equal",
                    SplitType.EXACT to "Exact",
                    SplitType.PERCENTAGE to "Percent",
                    SplitType.SHARES to "Shares"
                )
                SplitType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = splitType == type,
                        onClick = {
                            splitType = type
                            memberInputs = uiState.members.associateWith { "" }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, SplitType.entries.size)
                    ) {
                        Text(labels[type] ?: type.name, maxLines = 1)
                    }
                }
            }

            // Per-member inputs
            if (splitType != SplitType.EQUAL && uiState.members.isNotEmpty()) {
                val label = when (splitType) {
                    SplitType.EXACT -> "Amount"
                    SplitType.PERCENTAGE -> "Percentage"
                    SplitType.SHARES -> "Shares"
                    else -> ""
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.members.forEach { pk ->
                            val displayName = if (pk == uiState.myPubkey) "Me" else pk.take(8) + "…"
                            OutlinedTextField(
                                value = memberInputs[pk] ?: "",
                                onValueChange = { memberInputs = memberInputs + (pk to it) },
                                label = { Text("$displayName — $label") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = MaterialTheme.shapes.medium
                            )
                        }
                    }
                }
            }

            // Error
            uiState.error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Submit
            Button(
                onClick = {
                    val amountCents = amount.toBigDecimalOrNull()
                        ?.multiply(java.math.BigDecimal(100))
                        ?.toLong()
                        ?: 0L
                    if (amountCents > 0 && description.isNotBlank()) {
                        val inputs = memberInputs.mapValues { (_, v) -> v.toLongOrNull() ?: 0L }
                        viewModel.addExpense(description, amountCents, currency, paidBy, splitType, inputs)
                        onExpenseAdded()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = amount.isNotBlank() && description.isNotBlank(),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Add Expense", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
