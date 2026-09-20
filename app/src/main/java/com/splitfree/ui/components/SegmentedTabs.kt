package com.splitfree.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
private val SegmentLabelPadding = 4.dp
private val ScrollableSegmentLabelPadding = 14.dp

/** Font scale from which segments are content-sized and the track scrolls instead of truncating labels. */
private const val SCROLLABLE_FONT_SCALE = 1.3f

/**
 * Segmented control: `surfaceContainer` track with 4dp inset and one raised `surfaceBright` pill that slides
 * to the selected segment while the labels crossfade between `onSurface` and muted. Each segment is a 48dp
 * `Role.Tab` inside a `selectableGroup`. Below font scale [SCROLLABLE_FONT_SCALE] the segments share the
 * width equally; from there on each segment is as wide as its single-line label, the track scrolls
 * horizontally, the selected segment is highlighted in place and is brought into view whenever the selection
 * changes, so no label is ever truncated. [optionModifier] is applied to the segment at each index so callers
 * can attach test tags to individual segments.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    positionProvider: (() -> Float)? = null,
    optionModifier: (index: Int) -> Modifier = { Modifier }
) {
    val scrollable = LocalDensity.current.fontScale >= SCROLLABLE_FONT_SCALE
    val count = options.size.coerceAtLeast(1)
    val scrollState = rememberScrollState()
    val segmentWidths = remember(options, scrollable) { mutableStateMapOf<Int, Int>() }
    // Reveal only within this strip. A bring-into-view request also scrolls ancestor lists/pagers,
    // which can pull a group tab animation back onto the outgoing page at large text sizes.
    LaunchedEffect(selectedIndex, scrollable, segmentWidths.toMap(), scrollState.viewportSize, scrollState.maxValue) {
        if (scrollable &&
            selectedIndex in options.indices &&
            (0..selectedIndex).all { it in segmentWidths } &&
            scrollState.viewportSize > 0
        ) {
            val start = (0 until selectedIndex).sumOf { segmentWidths.getValue(it) }
            val end = start + segmentWidths.getValue(selectedIndex)
            val target = when {
                start < scrollState.value -> start
                end > scrollState.value + scrollState.viewportSize -> end - scrollState.viewportSize
                else -> scrollState.value
            }.coerceIn(0, scrollState.maxValue)
            if (target != scrollState.value) scrollState.animateScrollTo(target)
        }
    }
    val pillIndex by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, count - 1).toFloat(),
        animationSpec = tween(SfMotion.Base, easing = SfMotion.Ease),
        label = "segmentPill"
    )
    val pillPosition: () -> Float = positionProvider ?: { pillIndex }
    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(TrackShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(TrackPadding)
    ) {
        if (!scrollable) SelectionPill(pillIndex = pillPosition, count = count)
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier)
                .selectableGroup()
        ) {
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
                        .then(if (scrollable) Modifier else Modifier.weight(1f))
                        .onSizeChanged { segmentWidths[index] = it.width }
                        .then(optionModifier(index))
                        .padding(horizontal = SegmentInset)
                        .heightIn(min = SegmentMinHeight)
                        .clip(SegmentShape)
                        .then(
                            if (scrollable && selected) {
                                Modifier.background(MaterialTheme.colorScheme.surfaceBright)
                            } else {
                                Modifier
                            }
                        )
                        .selectable(
                            selected = selected,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) }
                        )
                        .padding(
                            horizontal = if (scrollable) ScrollableSegmentLabelPadding else SegmentLabelPadding,
                            vertical = 6.dp
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = ink,
                        textAlign = TextAlign.Center,
                        maxLines = if (scrollable) 1 else 2,
                        softWrap = !scrollable,
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
