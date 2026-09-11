package com.splitfree.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SfMotion

private val TrackShape = RoundedCornerShape(14.dp)
private val SegmentShape = RoundedCornerShape(11.dp)
private val TrackPadding = 4.dp
private val SegmentMinHeight = 48.dp

/**
 * Equal-width segmented control (mock `.segment`): `surfaceContainer` track with 4dp inset, the selected
 * segment lifted in `surfaceContainerLowest` with `onSurface` text, others muted. Each segment is a 48dp
 * `Role.Tab` inside a `selectableGroup`. Replaces `PrimaryTabRow` on Group detail.
 */
@Composable
fun SegmentedTabs(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(TrackShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(TrackPadding)
            .selectableGroup()
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                animationSpec = tween(SfMotion.Fast, easing = SfMotion.Ease),
                label = "segmentFill"
            )
            val ink by animateColorAsState(
                targetValue =
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(SfMotion.Fast, easing = SfMotion.Ease),
                label = "segmentInk"
            )
            Box(
                modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 1.5.dp)
                    .heightIn(min = SegmentMinHeight)
                    .clip(SegmentShape)
                    .background(fill)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
