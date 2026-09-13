package com.splitfree.ui.screens.groupdetail

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.ui.components.CategoryIcon
import com.splitfree.ui.components.EmptyState
import com.splitfree.ui.components.MoneyText
import com.splitfree.ui.components.SectionHead
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.GroupDetailUiState
import com.splitfree.util.CurrencyFormatter

private val ExpenseRowMinHeight = 82.dp
private val ExpenseValueMaxWidth = 120.dp
private val HistoryCardStroke = 1.dp
private const val SECONDS_TO_MILLIS = 1000L

/** Lazy list key of the row for [identity]; stable across arrivals, removals and reorders. */
internal fun expenseRowKey(identity: ExpenseIdentity): String =
    "expense_${identity.authorPubkey}_${identity.expenseUuid}"

/** Every expense in [currency], one per [ExpenseIdentity], newest first. */
@Composable
internal fun rememberCurrencyExpenses(state: GroupDetailUiState, currency: String?): List<AuthoredExpense> =
    remember(state.expenses, currency) {
        state.expenses.filter { it.expense.currency == currency }
            .distinctBy { it.identity }.sortedByDescending { it.expense.timestamp }
    }

/**
 * Expenses tab as items of the group screen's lazy list: a heading with the entry count, then one row per
 * entry of [expenses] (see [rememberCurrencyExpenses]). Rows are keyed on the [ExpenseIdentity], so a row
 * keeps its composition and the list its scroll anchor when expenses arrive or leave, and each row formats
 * its own strings when it is composed rather than the whole history at once. Consecutive rows draw as one
 * list card. [rowModifier] is applied to every item so the pane can share one entrance motion.
 */
internal fun LazyListScope.expenseItems(
    state: GroupDetailUiState,
    expenses: List<AuthoredExpense>,
    currency: String?,
    horizontalPadding: Dp,
    rowModifier: Modifier,
    onOpenExpense: (ExpenseIdentity) -> Unit
) {
    item(key = "expenses_heading", contentType = "expenses_heading") {
        Column(rowModifier.fillMaxWidth().padding(horizontal = horizontalPadding).testTag("group_pane_expenses")) {
            SectionHead(title = stringResource(R.string.group_expense_history)) {
                SectionMeta(pluralStringResource(R.plurals.group_expense_entries, expenses.size, expenses.size))
            }
            if (state.expenses.isEmpty() || currency == null) {
                EmptyState(
                    icon = Icons.Outlined.Receipt,
                    title = stringResource(R.string.no_expenses_yet),
                    body = stringResource(R.string.tap_add_first_expense)
                )
            } else if (expenses.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Receipt,
                    title = stringResource(R.string.group_summary_no_currency_expenses, currency),
                    body = stringResource(R.string.tap_add_first_expense)
                )
            }
        }
    }
    itemsIndexed(
        expenses,
        key = { _, authored -> expenseRowKey(authored.identity) },
        contentType = { _, _ -> "expense" }
    ) { index, authored ->
        HistoryCardSlice(
            first = index == 0,
            last = index == expenses.lastIndex,
            modifier = rowModifier.padding(horizontal = horizontalPadding)
        ) {
            val row = rememberExpenseRow(authored, state)
            ExpenseRow(row = row, onClick = { onOpenExpense(row.identity) })
        }
    }
}

/**
 * One lazy row drawn as a slice of a list card: `surfaceContainerLowest` fill, the 1dp `outlineVariant`
 * outline along the card's outer edges and a hairline above every row but the first, with the card corners
 * on the first and last slice. Stacked slices read as a single card while each stays its own lazy item.
 */
@Composable
private fun HistoryCardSlice(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = sliceShape(MaterialTheme.shapes.large, first = first, last = last)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .sliceOutline(shape, outline, first = first, last = last)
    ) {
        if (!first) SfDivider()
        content()
    }
}

/** [shape] with its corners kept only on the edges a slice shares with the card. */
private fun sliceShape(shape: CornerBasedShape, first: Boolean, last: Boolean): Shape = shape.copy(
    topStart = if (first) shape.topStart else CornerSize(0.dp),
    topEnd = if (first) shape.topEnd else CornerSize(0.dp),
    bottomStart = if (last) shape.bottomStart else CornerSize(0.dp),
    bottomEnd = if (last) shape.bottomEnd else CornerSize(0.dp)
)

/**
 * Strokes [shape] inside the slice. The horizontal edges of an open side are pushed a stroke beyond the
 * bounds, where the slice's clip discards them, so only the card's outer edges are drawn.
 */
private fun Modifier.sliceOutline(shape: Shape, color: Color, first: Boolean, last: Boolean): Modifier = drawBehind {
    val stroke = HistoryCardStroke.toPx()
    val top = if (first) stroke / 2 else -stroke
    val bottom = if (last) size.height - stroke / 2 else size.height + stroke
    translate(left = stroke / 2, top = top) {
        drawOutline(
            outline = shape.createOutline(Size(size.width - stroke, bottom - top), layoutDirection, this),
            color = color,
            style = Stroke(stroke)
        )
    }
}

/**
 * Display strings for one expense row, computed once per remembered row or list rather than on every
 * recomposition: name lookup, relative time and money formatting are the expensive parts of drawing a row.
 */
internal data class ExpenseRowModel(
    val expense: Expense,
    val identity: ExpenseIdentity,
    val payer: String,
    val relativeTime: String,
    val delta: PersonalDelta,
    val deltaAmount: String?
)

/** Builds [ExpenseRowModel]s for [expenses], keyed on everything the strings depend on. */
@Composable
internal fun rememberExpenseRows(expenses: List<AuthoredExpense>, state: GroupDetailUiState): List<ExpenseRowModel> {
    val you = stringResource(R.string.you)
    return remember(expenses, state.myPubkey, state.memberNames, state.members, you) {
        val now = System.currentTimeMillis()
        expenses.map { expenseRow(it, state, you, now) }
    }
}

/** The [ExpenseRowModel] for a single row, remembered for as long as the row is composed. */
@Composable
internal fun rememberExpenseRow(authored: AuthoredExpense, state: GroupDetailUiState): ExpenseRowModel {
    val you = stringResource(R.string.you)
    return remember(authored, state.myPubkey, state.memberNames, state.members, you) {
        expenseRow(authored, state, you, System.currentTimeMillis())
    }
}

private fun expenseRow(authored: AuthoredExpense, state: GroupDetailUiState, you: String, now: Long): ExpenseRowModel {
    val expense = authored.expense
    val delta = expense.personalDelta(state.myPubkey)
    return ExpenseRowModel(
        expense = expense,
        identity = authored.identity,
        payer =
        if (expense.paidBy == state.myPubkey) {
            you
        } else {
            disambiguatedMemberName(expense.paidBy, state.memberNames, state.members)
        },
        relativeTime =
        DateUtils.getRelativeTimeSpanString(
            expense.timestamp * SECONDS_TO_MILLIS,
            now,
            DateUtils.MINUTE_IN_MILLIS
        ).toString(),
        delta = delta,
        deltaAmount =
        when (delta) {
            is PersonalDelta.Lent -> CurrencyFormatter.format(delta.amount, expense.currency)
            is PersonalDelta.Share -> CurrencyFormatter.format(delta.amount, expense.currency)
            PersonalDelta.NoChange, PersonalDelta.NotInvolved -> null
        }
    )
}

/**
 * One expense (min 82dp): category tile, description, "Payer paid · when" and, on the right, the amount over
 * what it did to my balance ("you lent" / "your share" / "not involved"). Tapping opens the detail sheet.
 * Shared by the summary and expenses panes.
 */
@Composable
internal fun ExpenseRow(row: ExpenseRowModel, onClick: () -> Unit) {
    val expense = row.expense
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = ExpenseRowMinHeight)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("group_expense_${row.identity.authorPubkey}_${expense.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryIcon(category = expense.category)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                expense.description,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.group_expense_payer_meta, row.payer, row.relativeTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.widthIn(max = ExpenseValueMaxWidth), horizontalAlignment = Alignment.End) {
            MoneyText(
                amountMinor = expense.amount,
                currency = expense.currency,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                personalDeltaText(row.delta, row.deltaAmount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun personalDeltaText(delta: PersonalDelta, amount: String?): String = when (delta) {
    is PersonalDelta.Lent -> stringResource(R.string.group_delta_lent, amount.orEmpty())
    is PersonalDelta.Share -> stringResource(R.string.group_delta_share, amount.orEmpty())
    PersonalDelta.NoChange -> stringResource(R.string.group_delta_no_change)
    PersonalDelta.NotInvolved -> stringResource(R.string.group_delta_not_involved)
}
