package com.splitfree.ui.screens.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.AddExpenseUiState
import com.splitfree.util.CurrencyFormatter

@Composable
internal fun ExpenseAmountField(
    state: AddExpenseUiState,
    enabled: Boolean,
    onAmount: (String) -> Unit,
    onCurrency: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.expense_amount_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onCurrency,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("expense_currency")
            ) {
                Text(state.currency)
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        OutlinedTextField(
            value = state.amount, onValueChange = onAmount, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("expense_amount"), singleLine = true,
            placeholder = { Text("0", style = MaterialTheme.typography.headlineLarge) },
            textStyle = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 36.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            isError = state.amountError != null,
            supportingText = state.amountError?.let { { EditorError(it) } },
            label = { Text(stringResource(R.string.expense_amount_label) + " · " + state.currency) }
        )
    }
}

@Composable
internal fun ExpenseDescriptionField(state: AddExpenseUiState, enabled: Boolean, onDescription: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.description, onValueChange = onDescription, enabled = enabled,
        label = { Text(stringResource(R.string.expense_what_for)) },
        placeholder = { Text(stringResource(R.string.expense_description_hint)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("expense_description"),
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        isError = state.descriptionError != null,
        supportingText = state.descriptionError?.let { { EditorError(it) } }
    )
}

/** Keep same-name members distinguishable without replacing the real repository identity. */
@Composable
internal fun memberName(state: AddExpenseUiState, pubkey: String): String = disambiguatedMemberName(
    pubkey = pubkey,
    memberNames = state.memberNames,
    everyone = state.members + state.participants + state.paidBy,
    youLabel = stringResource(R.string.expense_you),
    myPubkey = state.myPubkey
)

@Composable
internal fun splitLabel(type: SplitType): String = stringResource(
    when (type) {
        SplitType.EQUAL -> R.string.expense_split_equal
        SplitType.EXACT -> R.string.expense_split_exact
        SplitType.PERCENTAGE -> R.string.expense_split_percent
        SplitType.SHARES -> R.string.expense_split_shares
    }
)

internal val expenseCategories = linkedMapOf(
    "" to R.string.expense_category_none, "food" to R.string.expense_category_food,
    "transport" to R.string.expense_category_transport, "shopping" to R.string.expense_category_shopping,
    "entertainment" to R.string.expense_category_entertainment, "utilities" to R.string.expense_category_utilities,
    "rent" to R.string.expense_category_rent, "health" to R.string.expense_category_health,
    "other" to R.string.expense_category_other
)

@Composable
internal fun ExpenseSummaryRows(
    state: AddExpenseUiState,
    enabled: Boolean,
    onPayer: () -> Unit,
    onSplit: () -> Unit,
    onCategory: () -> Unit
) {
    val equalShare = state.previewSplits.takeIf {
        state.splitType == SplitType.EQUAL &&
            it.isNotEmpty() &&
            it.map { split -> split.share }.distinct().size == 1
    }?.first()?.share
    val splitSummary = if (equalShare != null) {
        stringResource(R.string.expense_each, CurrencyFormatter.format(equalShare, state.currency))
    } else {
        splitLabel(state.splitType)
    }
    Column {
        EditorSummaryRow(
            stringResource(R.string.expense_paid_by),
            memberName(state, state.paidBy),
            enabled,
            "expense_payer",
            onPayer
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        EditorSummaryRow(
            stringResource(R.string.expense_split_with),
            splitSummary + " · " +
                pluralStringResource(R.plurals.expense_people_count, state.participants.size, state.participants.size),
            enabled,
            "expense_split",
            onSplit
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        EditorSummaryRow(
            stringResource(R.string.expense_category_optional),
            stringResource(expenseCategories[state.category] ?: R.string.expense_category_other),
            enabled,
            "expense_category",
            onCategory
        )
    }
}

@Composable
private fun EditorSummaryRow(label: String, value: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ExpenseSplitPreview(state: AddExpenseUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.expense_split_preview),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
        )
        if (state.previewSplits.isEmpty()) {
            if (state.splitError != null) {
                EditorError(state.splitError)
            } else {
                Text(
                    stringResource(R.string.expense_preview_after_amount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.previewSplits.take(4).forEach { split ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(memberName(state, split.pubkey), style = MaterialTheme.typography.labelMedium)
                            Text(
                                CurrencyFormatter.format(split.share, state.currency),
                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum")
                            )
                        }
                    }
                }
            }
            if (state.previewSplits.size > 4) {
                Text(
                    pluralStringResource(
                        R.plurals.expense_people_count,
                        state.previewSplits.size,
                        state.previewSplits.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.previewSplits.isNotEmpty() && state.splitError != null) EditorError(state.splitError)
        state.remaining?.takeIf { state.splitType == SplitType.EXACT }?.let { remaining ->
            val text = when {
                remaining > 0 -> stringResource(
                    R.string.expense_remaining,
                    CurrencyFormatter.format(remaining, state.currency)
                )
                remaining < 0 -> stringResource(
                    R.string.expense_overallocated,
                    CurrencyFormatter.formatMagnitude(remaining, state.currency)
                )
                else -> stringResource(R.string.expense_allocated)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (remaining == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
