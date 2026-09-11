package com.splitfree.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.theme.splitFree

/** Dot colour for [StatusPill]: `Online` → positive, `Offline` → warning, `Neutral` → muted. */
enum class PillTone { Neutral, Online, Offline }

/**
 * 28dp outlined pill with a 6dp status dot and short `bodySmall` text. The words
 * carry the meaning; the dot colour only reinforces it.
 */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: PillTone = PillTone.Neutral) {
    val dot =
        when (tone) {
            PillTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
            PillTone.Online -> MaterialTheme.splitFree.positive
            PillTone.Offline -> MaterialTheme.splitFree.warning
        }
    Surface(
        modifier = modifier.height(28.dp),
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(12.dp).clip(CircleShape).background(dot.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Shared connection indicator: `positive` when [connected], `warning` otherwise, crossfading once over
 * `SfMotion.Base`. No perpetual pulse. Announced through [contentDescription] as an image.
 */
@Composable
fun StatusDot(connected: Boolean, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val palette = MaterialTheme.splitFree
    val color by animateColorAsState(
        targetValue = if (connected) palette.positive else palette.warning,
        animationSpec = tween(SfMotion.Base, easing = SfMotion.Ease),
        label = "statusDot"
    )
    Box(
        modifier =
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            }
    )
}
