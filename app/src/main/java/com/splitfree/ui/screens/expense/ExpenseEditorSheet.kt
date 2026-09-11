package com.splitfree.ui.screens.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.money.ExpenseCurrencyCatalog
import com.splitfree.ui.viewmodels.AddExpenseUiState
import com.splitfree.util.CurrencyFormatter
import java.util.Currency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpenseEditorSheet(
    kind: String,
    state: AddExpenseUiState,
    actions: ExpenseEditorActions,
    onDismiss: () -> Unit
) {
    var search by rememberSaveable(kind) { mutableStateOf("") }
    val currencies = remember(state.currency) {
        ExpenseCurrencyCatalog.codes.sortedWith(compareBy<String> { it != state.currency }.thenBy { it })
    }
    val query = search.trim()
    val matchingCurrencies = if (kind == "currency") {
        currencies.filter { code ->
            code.contains(query, true) || Currency.getInstance(code).displayName.contains(query, true)
        }
    } else {
        emptyList()
    }
    val youLabel = stringResource(R.string.expense_you)
    val title = stringResource(
        when (kind) {
            "payer" -> R.string.expense_choose_payer
            "currency" -> R.string.expense_currency_title
            "category" -> R.string.expense_category_title
            else -> R.string.expense_split_edit
        }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxHeight(0.85f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.expense_split_done))
                }
            }
            if (kind != "category") {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    label = {
                        Text(
                            stringResource(
                                if (kind ==
                                    "currency"
                                ) {
                                    R.string.expense_currency_search
                                } else {
                                    R.string.expense_search_people
                                }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 20.dp,
                        vertical = 12.dp
                    ).testTag("expense_search")
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).then(
                    if (kind == "payer" || kind == "currency" ||
                        kind == "category"
                    ) {
                        Modifier.selectableGroup()
                    } else {
                        Modifier
                    }
                ).testTag("expense_sheet_list")
            ) {
                when (kind) {
                    "payer" -> {
                        val members = state.members.filter { key ->
                            query.isBlank() || key.contains(query, ignoreCase = true) ||
                                state.memberNames[key].orEmpty().contains(query, ignoreCase = true) ||
                                (key == state.myPubkey && youLabel.contains(query, ignoreCase = true))
                        }
                        if (members.isEmpty()) item { Text(stringResource(R.string.expense_no_people_found)) }
                        items(members, key = { it }) { key ->
                            ChoiceRow(memberName(state, key), key == state.paidBy, "payer_$key") {
                                actions.payer(key)
                                onDismiss()
                            }
                        }
                    }
                    "currency" -> {
                        if (matchingCurrencies.isEmpty()) {
                            item {
                                Text(stringResource(R.string.expense_no_currencies_found))
                            }
                        }
                        items(matchingCurrencies, key = { it }) { code ->
                            ChoiceRow(
                                "$code · ${Currency.getInstance(code).displayName}",
                                state.currency == code,
                                "currency_$code"
                            ) {
                                actions.currency(code)
                                onDismiss()
                            }
                        }
                    }
                    "category" -> items(expenseCategories.entries.toList(), key = { it.key }) { category ->
                        ChoiceRow(
                            stringResource(category.value),
                            state.category == category.key,
                            "category_${category.key}"
                        ) {
                            actions.category(category.key)
                            onDismiss()
                        }
                    }
                    else -> {
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SplitType.entries.forEach { type ->
                                    FilterChip(
                                        selected = state.splitType == type,
                                        onClick = {
                                            actions.splitType(type)
                                        },
                                        label = {
                                            Text(splitLabel(type))
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("split_${type.name}")
                                    )
                                }
                            }
                        }
                        state.splitError?.let { error -> item { EditorError(error) } }
                        val matchingMembers = (state.members + state.participants).distinct().filter { key ->
                            query.isBlank() || key.contains(query, ignoreCase = true) ||
                                state.memberNames[key].orEmpty().contains(query, ignoreCase = true) ||
                                (key == state.myPubkey && youLabel.contains(query, ignoreCase = true))
                        }
                        if (matchingMembers.isEmpty()) item { Text(stringResource(R.string.expense_no_people_found)) }
                        items(matchingMembers, key = { it }) { key ->
                            val name = memberName(state, key)
                            val included = key in state.participants
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                        .toggleable(value = included, role = Role.Checkbox, onValueChange = {
                                            actions.participant(key)
                                        })
                                        .testTag("participant_$key"),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = included, onCheckedChange = null)
                                    Text(
                                        name +
                                            if (key !in
                                                state.members
                                            ) {
                                                " · " + stringResource(R.string.expense_member_left)
                                            } else {
                                                ""
                                            },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (state.splitType != SplitType.EXACT && included) {
                                        state.previewSplits.firstOrNull { it.pubkey == key }?.let {
                                            Text(
                                                CurrencyFormatter.format(it.share, state.currency),
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.testTag("split_allocation_$key")
                                            )
                                        }
                                    }
                                }
                                if (included && state.splitType != SplitType.EQUAL) {
                                    val label = stringResource(
                                        when (state.splitType) {
                                            SplitType.EXACT -> R.string.expense_split_person_amount
                                            SplitType.PERCENTAGE -> R.string.expense_split_person_percent
                                            else -> R.string.expense_split_person_shares
                                        },
                                        name
                                    )
                                    OutlinedTextField(
                                        value = state.memberInputs[key].orEmpty(),
                                        onValueChange = {
                                            actions.memberInput(key, it)
                                        },
                                        label = { Text(label) },
                                        suffix = {
                                            Text(
                                                when (state.splitType) {
                                                    SplitType.EXACT -> state.currency
                                                    SplitType.PERCENTAGE -> "%"
                                                    else -> ""
                                                }
                                            )
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.fillMaxWidth().padding(
                                            bottom = 8.dp
                                        ).testTag("split_input_$key")
                                    )
                                }
                            }
                        }
                        item { ExpenseSplitPreview(state) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics {
            this.selected = selected
            role = Role.RadioButton
        }.testTag(tag),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
