package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R

private val SheetCorner = 28.dp
private val SheetPadding = 20.dp

/**
 * Modal bottom sheet for pickers and confirmations: `surface` colour, 28dp top corners, a 40x4dp grab
 * handle, a header row with a `titleLarge` [title] and a close button, then [content] padded 20dp. Put
 * [SfSheetFooter] at the end of [content] for the Cancel / Apply row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SfSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { SfSheetHandle() }
    ) {
        SfSheetHeader(title = title, onClose = onDismiss)
        Column(modifier = Modifier.padding(horizontal = SheetPadding).padding(bottom = 27.dp), content = content)
    }
}

/** 40x4dp `outlineVariant` grab handle. Exposed so previews can show the sheet layout inline. */
@Composable
fun SfSheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 11.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(
                width = 40.dp,
                height = 4.dp
            ).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
        )
    }
}

/** Sheet header row: `titleLarge` [title] and a trailing close [SfIconButton]. */
@Composable
fun SfSheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = SheetPadding, end = SheetPadding - 9.dp, bottom = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        SfIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cd_close),
            onClick = onClose
        )
    }
}

/**
 * Sheet action row: optional [secondary] taking one third and [primary] two thirds, 9dp apart, 18dp above.
 * Both slots are as tall as the taller button. Pass [SfSecondaryButton] / [SfPrimaryButton] with
 * `Modifier.fillMaxWidth()`.
 */
@Composable
fun SfSheetFooter(
    secondary: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    primary: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (secondary != null) {
                Box(Modifier.weight(1f).fillMaxHeight(), propagateMinConstraints = true) { secondary() }
                Spacer(Modifier.width(9.dp))
            }
            Box(Modifier.weight(2f).fillMaxHeight(), propagateMinConstraints = true) { primary() }
        }
    }
}
