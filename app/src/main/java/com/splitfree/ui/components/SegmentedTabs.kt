package com.splitfree.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SfMotion
import kotlin.math.roundToInt

private val TrackShape = RoundedCornerShape(14.dp)
private val SegmentShape = RoundedCornerShape(11.dp)
private val TrackPadding = 4.dp
private val SegmentInset = 1.5.dp
private val SegmentMinHeight = 48.dp

/**
 * Equal-width segmented control: `surfaceContainer` track with 4dp inset and one raised `surfaceBright` pill
 * that slides to the selected segment while the labels crossfade between `onSurface` and muted. Each segment
 * is a 48dp `Role.Tab` inside a `selectableGroup`. [optionModifier] is applied to the segment at each index so
 * callers can attach test tags to individual segments.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    optionModifier: (index: Int) -> Modifier = { Modifier }
) {
    val count = options.size.coerceAtLeast(1)
    val pillIndex by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, count - 1).toFloat(),
        animationSpec = tween(SfMotion.Base, easing = SfMotion.Ease),
        label = "segmentPill"
    )
    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(TrackShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(TrackPadding)
    ) {
        SelectionPill(pillIndex = { pillIndex }, count = count)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val ink by animateColorAsState(
                    targetValue =
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(SfMotion.Base, easing = SfMotion.Ease),
                    label = "segmentInk"
                )
                Box(
                    modifier =
                    Modifier
                        .weight(1f)
                        .then(optionModifier(index))
                        .padding(horizontal = SegmentInset)
                        .heightIn(min = SegmentMinHeight)
                        .clip(SegmentShape)
                        .selectable(
                            selected = selected,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) }
                        )
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
}

/**
 * The single raised pill behind the selected segment. It fills the track's height, measures one segment wide
 * and is placed at [pillIndex] segments from the start; reading the animated index only in placement keeps
 * the slide out of composition and measurement.
 */
@Composable
private fun BoxScope.SelectionPill(pillIndex: () -> Float, count: Int) {
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val segmentWidth = constraints.maxWidth.toFloat() / count
                val inset = SegmentInset.roundToPx()
                val pillWidth = (segmentWidth.roundToInt() - inset * 2).coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(pillWidth, constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeRelative(x = (pillIndex() * segmentWidth).roundToInt() + inset, y = 0)
                }
            }
            .background(MaterialTheme.colorScheme.surfaceBright, SegmentShape)
    )
}
