package com.splitfree.ui.screens.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.AddExpenseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(onExpenseAdded: () -> Unit, onBack: () -> Unit, viewModel: AddExpenseViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var description by remember { mutableStateOf("") }
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onExpenseAdded()
    }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("INR") }
    var currencyExpanded by remember { mutableStateOf(false) }
    var paidBy by remember { mutableStateOf("") }
    var splitType by remember { mutableStateOf(SplitType.EQUAL) }
    var memberInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var category by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }

    val currencies = listOf("INR", "USD", "EUR", "GBP", "JPY", "AUD", "CAD")

    fun displayName(pk: String): String = when (pk) {
        uiState.myPubkey -> "Me"
        else -> uiState.memberNames[pk]?.takeIf { it.isNotBlank() } ?: (pk.take(6) + "…")
    }
    val categories =
        listOf(
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
            modifier =
            Modifier
                .padding(padding)
                .padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.screenPaddingVertical)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing)
        ) {
            Spacer(Modifier.height(tokens.denseSpacing))

            // Amount + Currency row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tokens.fieldSpacing)
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
                    modifier = Modifier.width(tokens.dropdownWidth)
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
                                onClick = {
                                    currency = c
                                    currencyExpanded = false
                                }
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
                            onClick = {
                                category = key
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            // Paid by
            if (uiState.members.size > 1) {
                Text(
                    "Paid by",
                    style =
                    if (adaptive.isCompact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    }
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    uiState.members.forEachIndexed { index, pk ->
                        SegmentedButton(
                            selected = paidBy == pk,
                            onClick = { paidBy = pk },
                            shape = SegmentedButtonDefaults.itemShape(index, uiState.members.size)
                        ) {
                            Text(
                                text = displayName(pk),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Split type
            Text(
                "Split type",
                style = if (adaptive.isCompact) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelLarge
                }
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val labels =
                    mapOf(
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
                        Text(
                            text = labels[type] ?: type.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Per-member inputs
            if (splitType != SplitType.EQUAL && uiState.members.isNotEmpty()) {
                val label =
                    when (splitType) {
                        SplitType.EXACT -> "Amount"
                        SplitType.PERCENTAGE -> "Percentage"
                        SplitType.SHARES -> "Shares"
                        else -> ""
                    }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(tokens.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
                    ) {
                        uiState.members.forEach { pk ->
                            OutlinedTextField(
                                value = memberInputs[pk] ?: "",
                                onValueChange = { memberInputs = memberInputs + (pk to it) },
                                label = { Text("${displayName(pk)} — $label") },
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
                        modifier = Modifier.padding(tokens.cardPadding),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Submit
            Button(
                onClick = {
                    val amountCents =
                        amount
                            .toBigDecimalOrNull()
                            ?.multiply(java.math.BigDecimal(100))
                            ?.toLong()
                            ?: 0L
                    if (amountCents > 0 && description.isNotBlank()) {
                        val inputs = memberInputs.mapValues { (_, v) -> v.toLongOrNull() ?: 0L }
                        viewModel.addExpense(description, amountCents, currency, paidBy, splitType, inputs)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(tokens.buttonHeight),
                enabled = amount.isNotBlank() && description.isNotBlank(),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = "Add Expense",
                    style = if (adaptive.isCompact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    }
                )
            }
            Spacer(Modifier.height(tokens.screenBottomSpacer))
        }
    }
}
