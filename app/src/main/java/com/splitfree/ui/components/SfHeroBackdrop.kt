package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.splitFree

/** Shared, non-interactive hero decoration. The parent Surface clips it to the card's existing shape. */
@Composable
internal fun BoxScope.SfHeroBackdrop() {
    val palette = MaterialTheme.splitFree
    if (palette.heroGradientEnd != palette.hero) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(palette.hero, palette.heroGradientEnd)))
                .drawWithCache {
                    // A diffuse highlight replaces the hard-edged circle only in the dark treatment.
                    val endX = if (layoutDirection == LayoutDirection.Ltr) size.width else 0f
                    val glow = Brush.radialGradient(
                        colors = listOf(
                            palette.heroAccent.copy(alpha = palette.heroAccentAlpha),
                            palette.heroAccent.copy(alpha = 0f)
                        ),
                        center = Offset(endX, 0f),
                        radius = size.maxDimension.coerceAtLeast(1f)
                    )
                    onDrawBehind { drawRect(glow) }
                }
        )
        return
    }
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset(x = 58.dp, y = (-40).dp)
            .size(125.dp)
            .clip(CircleShape)
            .background(palette.heroAccent.copy(alpha = palette.heroAccentAlpha))
    )
}
