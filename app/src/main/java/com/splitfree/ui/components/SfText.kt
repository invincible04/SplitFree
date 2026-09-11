package com.splitfree.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.theme.tabular
import com.splitfree.util.CurrencyFormatter

/** Uppercase, letter-spaced eyebrow in `labelSmall`. Upper-casing happens here; pass the text as written. */
@Composable
fun MiniLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.splitFree.faint) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Section heading row: `titleMedium` [title] on the left, a right-aligned [trailing] slot for a count
 * (`bodySmall`, muted) or an [SfTextButton]. The row is at least a text button tall, so the title sits at the
 * same height whichever the slot holds.
 */
private val SectionHeadMinHeight = 48.dp

@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(top = 14.dp, start = 1.dp, end = 1.dp)
            .heightIn(min = SectionHeadMinHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        trailing()
    }
}

/**
 * Money in a [style] with tabular numerals, formatted per currency by [CurrencyFormatter.format]
 * ([amountMinor] is in the smallest unit). Always shows the sign the formatter produces.
 */
@Composable
fun MoneyText(
    amountMinor: Long,
    currency: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1
) {
    Text(
        CurrencyFormatter.format(amountMinor, currency),
        modifier = modifier,
        style = style.tabular(),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Magnitude of [amountMinor] coloured by direction: `positive` when > 0, `negative` when < 0, `onSurface`
 * when zero. Colour is never the only cue: always pair it with words ("owed to you" / "you owe").
 */
@Composable
fun SignedMoneyText(
    amountMinor: Long,
    currency: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    val palette = MaterialTheme.splitFree
    val color =
        when {
            amountMinor > 0 -> palette.positive
            amountMinor < 0 -> palette.negative
            else -> MaterialTheme.colorScheme.onSurface
        }
    Text(
        CurrencyFormatter.formatMagnitude(amountMinor, currency),
        modifier = modifier,
        style = style.tabular(),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
