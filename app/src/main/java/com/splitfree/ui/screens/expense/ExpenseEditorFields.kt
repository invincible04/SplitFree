package com.splitfree.ui.screens.expense

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.ui.components.MemberStack
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.SettingsChevron
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.theme.tabular
import com.splitfree.ui.util.WidthClass
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.asString
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.AddExpenseUiState
import com.splitfree.util.CurrencyFormatter

private const val DISABLED_ALPHA = 0.38f
private val SummaryRowMinHeight = 74.dp
private val SummaryIconColumn = 34.dp
private val SplitPreviewShape = RoundedCornerShape(18.dp)
private val SplitPreviewAvatar = 31.dp
private const val SPLIT_PREVIEW_FACES = 3

/** Editor context line: a small group glyph and "Group · N people" in muted `bodySmall`. */
@Composable
internal fun ExpenseEditorContext(state: AddExpenseUiState) {
    val people = pluralStringResource(R.plurals.people_count, state.members.size, state.members.size)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Group,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(7.dp))
        Text(
            stringResource(R.string.dot_separated, state.groupName, people),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Leading symbol of the formatted zero amount ("₹" for INR, "KWD" for currencies without a symbol). */
internal fun currencySymbol(currency: String): String =
    CurrencyFormatter.format(0, currency).takeWhile { !it.isDigit() }.trim().ifEmpty { currency }

/**
 * Amount block: eyebrow label, muted currency symbol, a borderless `displaySmall` decimal input and
 * the currency pill on one line over a hairline that turns `primary` while focused. The label, placeholder
 * and error live inside the field's decoration so they are announced with it, as a Material label would be.
 */
@Composable
internal fun ExpenseAmountField(
    state: AddExpenseUiState,
    enabled: Boolean,
    onAmount: (String) -> Unit,
    onCurrency: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val symbol = remember(state.currency) { currencySymbol(state.currency) }
    val ink = MaterialTheme.colorScheme.onSurface
    val textStyle =
        MaterialTheme.typography.displaySmall.tabular().copy(
            color = if (enabled) ink else ink.copy(alpha = DISABLED_ALPHA)
        )
    val line by animateColorAsState(
        targetValue =
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(SfMotion.Fast, easing = SfMotion.Ease),
        label = "amountLine"
    )
    BasicTextField(
        value = state.amount,
        onValueChange = onAmount,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("expense_amount"),
        textStyle = textStyle,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        interactionSource = interaction,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Column {
                MiniLabel(stringResource(R.string.expense_amount_label))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        symbol,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).padding(vertical = 7.dp), contentAlignment = Alignment.CenterStart) {
                        if (state.amount.isEmpty()) {
                            Text(
                                stringResource(R.string.expense_amount_placeholder),
                                style = textStyle,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                    Spacer(Modifier.width(8.dp))
                    SfSecondaryButton(
                        text = state.currency,
                        onClick = onCurrency,
                        enabled = enabled,
                        leadingIcon = Icons.Outlined.ExpandMore,
                        modifier = Modifier.testTag("expense_currency")
                    )
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(thickness = 1.dp, color = line)
                if (state.amountError != null) {
                    Spacer(Modifier.height(6.dp))
                    EditorError(state.amountError)
                }
            }
        }
    )
}

/** Editor text field colours: card fill, hairline border that turns `primary` on focus, `outline` placeholder. */
@Composable
internal fun editorTextFieldColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceContainerLowest,
        unfocusedContainerColor = colors.surfaceContainerLowest,
        disabledContainerColor = colors.surfaceContainerLowest,
        errorContainerColor = colors.surfaceContainerLowest,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outlineVariant,
        disabledBorderColor = colors.outlineVariant,
        errorBorderColor = colors.error,
        cursorColor = colors.primary,
        focusedPlaceholderColor = colors.outline,
        unfocusedPlaceholderColor = colors.outline,
        disabledPlaceholderColor = colors.outline.copy(alpha = DISABLED_ALPHA),
        focusedLeadingIconColor = colors.onSurfaceVariant,
        unfocusedLeadingIconColor = colors.onSurfaceVariant
    )
}

/** Description field: eyebrow label over a `medium`-cornered text field on card fill with a hairline border. */
@Composable
internal fun ExpenseDescriptionField(state: AddExpenseUiState, enabled: Boolean, onDescription: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val label = stringResource(R.string.expense_what_for)
    Column {
        MiniLabel(label)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescription,
            enabled = enabled,
            placeholder = {
                Text(
                    stringResource(R.string.expense_description_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag("expense_description"),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.medium,
            colors = editorTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            isError = state.descriptionError != null,
            supportingText = state.descriptionError?.let { { EditorError(it) } }
        )
    }
}

/** Keep same-name members distinguishable without replacing the real repository identity. */
@Composable
internal fun memberName(state: AddExpenseUiState, pubkey: String): String = disambiguatedMemberName(
    pubkey = pubkey,
    memberNames = state.memberNames,
    everyone = state.members + state.participants + state.paidBy,
    youLabel = stringResource(R.string.you),
    myPubkey = state.myPubkey
)

/** Long split-mode label for the summary row ("Equally", "Exact amounts", …). */
@Composable
internal fun splitLabel(type: SplitType): String = stringResource(
    when (type) {
        SplitType.EQUAL -> R.string.expense_split_equal
        SplitType.EXACT -> R.string.expense_split_exact
        SplitType.PERCENTAGE -> R.string.expense_split_percent
        SplitType.SHARES -> R.string.expense_split_shares
    }
)

/** Short split-mode label for the sheet's segmented control ("Equal", "Exact", …). */
@Composable
internal fun splitModeLabel(type: SplitType): String = stringResource(
    when (type) {
        SplitType.EQUAL -> R.string.expense_split_mode_equal
        SplitType.EXACT -> R.string.expense_split_mode_exact
        SplitType.PERCENTAGE -> R.string.expense_split_mode_percent
        SplitType.SHARES -> R.string.expense_split_mode_shares
    }
)

internal val expenseCategories = linkedMapOf(
    "" to R.string.expense_category_none, "food" to R.string.expense_category_food,
    "transport" to R.string.expense_category_transport, "shopping" to R.string.expense_category_shopping,
    "entertainment" to R.string.expense_category_entertainment, "utilities" to R.string.expense_category_utilities,
    "rent" to R.string.expense_category_rent, "health" to R.string.expense_category_health,
    "other" to R.string.expense_category_other
)

/** Payer, split and category rows in one [SfListCard]. */
@Composable
internal fun ExpenseSummaryRows(
    state: AddExpenseUiState,
    enabled: Boolean,
    onPayer: () -> Unit,
    onSplit: () -> Unit,
    onCategory: () -> Unit
) {
    val people = pluralStringResource(R.plurals.people_count, state.participants.size, state.participants.size)
    SfListCard {
        EditorSummaryRow(
            icon = Icons.Outlined.Person,
            label = stringResource(R.string.expense_paid_by),
            value = memberName(state, state.paidBy),
            enabled = enabled,
            tag = "expense_payer",
            onClick = onPayer
        )
        SfDivider()
        EditorSummaryRow(
            icon = Icons.AutoMirrored.Outlined.CallSplit,
            label = stringResource(R.string.expense_split_with),
            value = stringResource(R.string.dot_separated, splitLabel(state.splitType), people),
            enabled = enabled,
            tag = "expense_split",
            onClick = onSplit
        )
        SfDivider()
        EditorSummaryRow(
            icon = Icons.Outlined.LocalOffer,
            label = stringResource(R.string.expense_category_title),
            value = stringResource(expenseCategories[state.category] ?: R.string.expense_category_other),
            enabled = enabled,
            tag = "expense_category",
            onClick = onCategory
        )
    }
}

/** Summary row: `primary` icon in a 34dp column, muted label over a `titleSmall` value, faint chevron. */
@Composable
private fun EditorSummaryRow(
    icon: ImageVector,
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button }.testTag(tag),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            Modifier.heightIn(min = SummaryRowMinHeight).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(SummaryIconColumn), contentAlignment = Alignment.CenterStart) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else ink.copy(alpha = DISABLED_ALPHA)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) ink else ink.copy(alpha = DISABLED_ALPHA)
                )
            }
            Spacer(Modifier.width(8.dp))
            SettingsChevron()
        }
    }
}

/** Headline of the split preview / sheet status: what each person pays, or why the split is not ready. */
@Composable
internal fun splitSummary(state: AddExpenseUiState): String {
    val shares = state.previewSplits.map { it.share }
    return when {
        state.amount.isBlank() -> stringResource(R.string.expense_enter_amount_preview)
        state.splitError != null -> state.splitError.asString()
        shares.isEmpty() -> stringResource(R.string.expense_enter_amount_preview)
        shares.distinct().size == 1 -> stringResource(
            R.string.expense_each,
            CurrencyFormatter.format(shares.first(), state.currency)
        )
        else -> stringResource(R.string.expense_split_summary_exact)
    }
}

/** Exact-mode remainder line: how much is left or over, or that the split matches the total. */
@Composable
internal fun exactRemainderText(state: AddExpenseUiState): String? {
    val remaining = state.remaining?.takeIf { state.splitType == SplitType.EXACT } ?: return null
    return when {
        remaining > 0 -> stringResource(R.string.expense_remaining, CurrencyFormatter.format(remaining, state.currency))
        remaining < 0 -> stringResource(
            R.string.expense_overallocated,
            CurrencyFormatter.formatMagnitude(remaining, state.currency)
        )
        else -> stringResource(R.string.expense_allocated)
    }
}

/**
 * Split preview: brand-wash card with the first participants' faces, the per-person share (or the
 * reason the split is not ready yet), a rounding note and an Edit button that opens the split sheet.
 */
@Composable
internal fun ExpenseSplitPreviewCard(state: AddExpenseUiState, enabled: Boolean, onEdit: () -> Unit) {
    val participants = (state.members + state.participants).distinct().filter { it in state.participants }
    val names = participants.associateWith { memberName(state, it) }
    val summary = splitSummary(state)
    val summaryIsError = state.amount.isNotBlank() && state.splitError != null
    val remainder = exactRemainderText(state)?.takeIf { state.remaining != 0L }
    val showFaces = adaptiveLayoutInfo().widthClass != WidthClass.Compact && participants.isNotEmpty()
    val editDescription = stringResource(R.string.expense_edit_split_description)
    Surface(shape = SplitPreviewShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showFaces) {
                MemberStack(pubkeys = participants, names = names, max = SPLIT_PREVIEW_FACES, size = SplitPreviewAvatar)
                Spacer(Modifier.width(11.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    summary,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summaryIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    remainder ?: stringResource(R.string.expense_split_preview_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remainder != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.width(10.dp))
            SfSecondaryButton(
                text = stringResource(R.string.edit),
                onClick = onEdit,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = editDescription }
            )
        }
    }
}

/** Status line under the split list: the split problem, the exact remainder, or a quiet confirmation. */
@Composable
internal fun ExpenseSplitStatus(state: AddExpenseUiState) {
    val remainder = exactRemainderText(state)
    when {
        state.splitError != null -> EditorError(state.splitError)
        remainder != null -> Text(
            remainder,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.remaining == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        state.previewSplits.isNotEmpty() -> Text(
            stringResource(R.string.expense_allocated),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        else -> Text(
            stringResource(R.string.expense_enter_amount_preview),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Muted body copy used for sheet hints and empty search results. */
@Composable
internal fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
