package com.splitfree.ui.screens.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.money.ExpenseCurrencyCatalog
import com.splitfree.ui.components.ChoiceRow
import com.splitfree.ui.components.MoneyText
import com.splitfree.ui.components.SegmentedTabs
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfPrimaryButton
import com.splitfree.ui.components.SfSheet
import com.splitfree.ui.components.SfSheetFooter
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.theme.tabular
import com.splitfree.ui.viewmodels.AddExpenseUiState
import java.util.Currency
import kotlin.math.roundToInt

private const val SHEET_MAX_HEIGHT_FRACTION = 0.85f
private val CheckTileSize = 48.dp
private val CheckTileShape = RoundedCornerShape(15.dp)
private val SplitRowMinHeight = 68.dp
private val SplitInputWidth = 88.dp
private val SplitInputWidthWithCurrency = 104.dp
private val SplitInputHeight = 48.dp

/**
 * Picker sheets for the editor (payer, currency, category, split). Choices apply immediately through
 * [actions]; the sheet is capped at 85% of the window height and hugs shorter content.
 */
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
    // Display-name lookups over ~150 currencies are not free; only redo them when the query changes.
    val matchingCurrencies = remember(kind, query, currencies) {
        if (kind == "currency") {
            currencies.filter { code ->
                code.contains(query, true) || Currency.getInstance(code).displayName.contains(query, true)
            }
        } else {
            emptyList()
        }
    }
    val youLabel = stringResource(R.string.expense_you)
    val matchesPeople: (String) -> Boolean = { key ->
        query.isBlank() ||
            key.contains(query, ignoreCase = true) ||
            state.memberNames[key].orEmpty().contains(query, ignoreCase = true) ||
            (key == state.myPubkey && youLabel.contains(query, ignoreCase = true))
    }
    val title = stringResource(
        when (kind) {
            "payer" -> R.string.expense_choose_payer
            "currency" -> R.string.expense_currency_title
            "category" -> R.string.expense_category_title
            else -> R.string.expense_split_title
        }
    )
    val singleChoice = kind == "payer" || kind == "currency" || kind == "category"
    SfSheet(onDismiss = onDismiss, title = title) {
        Column(Modifier.maxHeightFraction(SHEET_MAX_HEIGHT_FRACTION).imePadding()) {
            if (kind != "category") {
                SheetSearchField(
                    value = search,
                    onValueChange = { search = it },
                    label = stringResource(
                        if (kind == "currency") R.string.expense_currency_search else R.string.expense_search_people
                    )
                )
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 4.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (singleChoice) Modifier.selectableGroup() else Modifier)
                    .testTag("expense_sheet_list")
            ) {
                when (kind) {
                    "payer" -> {
                        val members = state.members.filter(matchesPeople)
                        if (members.isEmpty()) item { SheetHint(stringResource(R.string.expense_no_people_found)) }
                        choiceRows(members, key = { it }) { key ->
                            ChoiceRow(
                                title = memberName(state, key),
                                subtitle = null,
                                selected = key == state.paidBy,
                                onClick = {
                                    actions.payer(key)
                                    onDismiss()
                                },
                                modifier = Modifier.testTag("payer_$key")
                            )
                        }
                    }
                    "currency" -> {
                        if (matchingCurrencies.isEmpty()) {
                            item { SheetHint(stringResource(R.string.expense_no_currencies_found)) }
                        }
                        choiceRows(matchingCurrencies, key = { it }) { code ->
                            ChoiceRow(
                                title = code,
                                subtitle = Currency.getInstance(code).displayName,
                                selected = state.currency == code,
                                onClick = {
                                    actions.currency(code)
                                    onDismiss()
                                },
                                modifier = Modifier.testTag("currency_$code")
                            )
                        }
                    }
                    "category" -> choiceRows(expenseCategories.entries.toList(), key = { it.key }) { category ->
                        ChoiceRow(
                            title = stringResource(category.value),
                            subtitle = null,
                            selected = state.category == category.key,
                            onClick = {
                                actions.category(category.key)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("category_${category.key}")
                        )
                    }
                    else -> splitEditor(
                        state,
                        actions,
                        (state.members + state.participants).distinct().filter(matchesPeople)
                    )
                }
            }
            SfSheetFooter(secondary = null) {
                SfPrimaryButton(text = stringResource(R.string.expense_split_done), onClick = onDismiss)
            }
        }
    }
}

/** Choice rows separated by hairlines inside a bordered list. */
private inline fun <T> LazyListScope.choiceRows(
    items: List<T>,
    noinline key: (T) -> Any,
    crossinline row: @Composable (T) -> Unit
) {
    itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
        row(item)
        if (index < items.lastIndex) SfDivider()
    }
}

private fun LazyListScope.splitEditor(state: AddExpenseUiState, actions: ExpenseEditorActions, members: List<String>) {
    item {
        val modes = SplitType.entries
        SegmentedTabs(
            options = modes.map { splitModeLabel(it) },
            selectedIndex = modes.indexOf(state.splitType),
            onSelect = { actions.splitType(modes[it]) },
            optionModifier = { Modifier.testTag("split_${modes[it].name}") }
        )
        Spacer(Modifier.height(12.dp))
        SheetHint(
            when (state.splitType) {
                SplitType.EQUAL -> stringResource(R.string.expense_split_hint_equal)
                SplitType.EXACT -> stringResource(R.string.expense_split_hint_exact, state.currency)
                SplitType.PERCENTAGE -> stringResource(R.string.expense_split_hint_percent)
                SplitType.SHARES -> stringResource(R.string.expense_split_hint_shares)
            }
        )
        Spacer(Modifier.height(6.dp))
    }
    if (members.isEmpty()) item { SheetHint(stringResource(R.string.expense_no_people_found)) }
    itemsIndexed(members, key = { _, key -> key }) { index, key ->
        SplitPersonRow(state, key, actions)
        if (index < members.lastIndex) SfDivider()
    }
    item {
        Spacer(Modifier.height(12.dp))
        ExpenseSplitStatus(state)
    }
}

/**
 * One split participant row: a 48dp check tile, the name (plus a note for departed members) and, on the trailing
 * side, either the computed share (equal split) or a compact value input (exact / percent / shares).
 */
@Composable
private fun SplitPersonRow(state: AddExpenseUiState, key: String, actions: ExpenseEditorActions) {
    val name = memberName(state, key)
    val included = key in state.participants
    val share = state.previewSplits.firstOrNull { it.pubkey == key }?.share
    val showsInput = included && state.splitType != SplitType.EQUAL
    val showsShareUnderName = included && share != null && state.splitType != SplitType.EXACT && showsInput
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = SplitRowMinHeight)
            .toggleable(value = included, role = Role.Checkbox, onValueChange = { actions.participant(key) })
            .testTag("participant_$key")
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckTile(checked = included)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (key !in state.members) {
                Text(
                    stringResource(R.string.expense_member_left),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showsShareUnderName) {
                MoneyText(
                    amountMinor = share ?: 0L,
                    currency = state.currency,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("split_allocation_$key")
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            showsInput -> SplitValueField(state, key, name, actions.memberInput)
            included && share != null -> MoneyText(
                amountMinor = share,
                currency = state.currency,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("split_allocation_$key")
            )
            included -> Text(
                stringResource(R.string.expense_share_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.splitFree.faint
            )
        }
    }
}

/** Check tile: `primary` fill with a check when on, hairline-bordered square when off. */
@Composable
private fun CheckTile(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
        Modifier
            .size(CheckTileSize)
            .then(
                if (checked) {
                    Modifier.background(colors.primary, CheckTileShape)
                } else {
                    Modifier.border(1.5.dp, colors.outlineVariant, CheckTileShape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onPrimary
            )
        }
    }
}

/** Per-person split value: right-aligned compact value box on card fill with a unit suffix. */
@Composable
private fun SplitValueField(state: AddExpenseUiState, key: String, name: String, onInput: (String, String) -> Unit) {
    val suffix = when (state.splitType) {
        SplitType.EXACT -> state.currency
        SplitType.PERCENTAGE -> "%"
        else -> ""
    }
    val label = stringResource(
        when (state.splitType) {
            SplitType.EXACT -> R.string.expense_split_person_amount
            SplitType.PERCENTAGE -> R.string.expense_split_person_percent
            else -> R.string.expense_split_person_shares
        },
        name
    )
    val width: Dp = if (state.splitType == SplitType.EXACT) SplitInputWidthWithCurrency else SplitInputWidth
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = state.memberInputs[key].orEmpty(),
        onValueChange = { onInput(key, it) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.tabular().copy(
            color = colors.onSurface,
            textAlign = TextAlign.End
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.semantics { contentDescription = label }.testTag("split_input_$key"),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .width(width)
                    .heightIn(min = SplitInputHeight)
                    .background(colors.surfaceContainerLowest, MaterialTheme.shapes.small)
                    .border(1.dp, if (focused) colors.primary else colors.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) { innerTextField() }
                if (suffix.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        suffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    )
}

/** Search box at the top of a picker sheet; [label] is announced instead of a floating label. */
@Composable
private fun SheetSearchField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1) },
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.medium,
        colors = editorTextFieldColors(),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag("expense_search")
    )
}

/** Caps a column at [fraction] of the available height while letting shorter content hug its size. */
private fun Modifier.maxHeightFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val cappedMax =
        if (constraints.hasBoundedHeight) (constraints.maxHeight * fraction).roundToInt() else constraints.maxHeight
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = cappedMax))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
