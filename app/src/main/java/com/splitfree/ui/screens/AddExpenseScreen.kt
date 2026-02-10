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
    var paidBy by remember { mutableStateOf("") }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    var memberInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Initialize paidBy to self when members load
    LaunchedEffect(uiState.myPubkey) {
        if (paidBy.isEmpty() && uiState.myPubkey.isNotEmpty()) paidBy = uiState.myPubkey
    }
    // Initialize member inputs when members load
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
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it },
                label = { Text("Currency") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Paid by selector
            if (uiState.members.size > 1) {
                Text("Paid by", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    uiState.members.forEachIndexed { index, pk ->
                        SegmentedButton(
                            selected = paidBy == pk,
                            onClick = { paidBy = pk },
                            shape = SegmentedButtonDefaults.itemShape(index, uiState.members.size)
                        ) { Text(pk.take(6) + "…") }
                    }
                }
            }

            // Split type selector
            Text("Split type", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SplitType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = splitType == type,
                        onClick = {
                            splitType = type
                            memberInputs = uiState.members.associateWith { "" }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, SplitType.entries.size)
                    ) { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            // Per-member inputs (not shown for equal split)
            if (splitType != SplitType.EQUAL && uiState.members.isNotEmpty()) {
                val label = when (splitType) {
                    SplitType.EXACT -> "Amount"
                    SplitType.PERCENTAGE -> "%"
                    SplitType.SHARES -> "Shares"
                    else -> ""
                }
                uiState.members.forEach { pk ->
                    OutlinedTextField(
                        value = memberInputs[pk] ?: "",
                        onValueChange = { memberInputs = memberInputs + (pk to it) },
                        label = { Text("${pk.take(6)}… — $label") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
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
                modifier = Modifier.fillMaxWidth(),
                enabled = amount.isNotBlank() && description.isNotBlank()
            ) {
                Text("Add Expense")
            }
        }
    }
}
