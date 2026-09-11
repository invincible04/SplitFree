package com.splitfree.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Flat card: `surfaceContainerLowest` fill, 1dp `outlineVariant` hairline, no elevation. Used for group,
 * summary and settings cards. Pass [onClick] to make the whole card a button.
 */
@Composable
fun SfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val color = MaterialTheme.colorScheme.surfaceContainerLowest
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            color = color,
            border = border
        ) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    }
}

/**
 * [SfCard] that hosts a vertical list of rows (balances, activity, settings); call [SfDivider] between rows.
 * Rows own their own horizontal padding so dividers can run edge-to-edge.
 */
@Composable
fun SfListCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    items: @Composable ColumnScope.() -> Unit
) {
    SfCard(modifier = modifier, shape = shape, content = items)
}

/** 1dp hairline in `outlineVariant`; the only divider colour used in the app. */
@Composable
fun SfDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
