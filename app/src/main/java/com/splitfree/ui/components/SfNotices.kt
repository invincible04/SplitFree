package com.splitfree.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.splitfree.ui.theme.splitFree

private val NoticeShape = RoundedCornerShape(18.dp)

/**
 * Soft brand-wash note: `primaryContainer` fill, `primary` text and a leading 20dp
 * [icon]. For guidance and reassurance, not for errors.
 */
@Composable
fun HintCard(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Info) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NoticeShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(11.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Cautionary note (invite warning, offline banner): `warningContainer` fill with `onSurfaceVariant`
 * body text and an optional leading 18dp [icon] in `warning` (e.g. `WifiOff` for the offline banner). The
 * wording must stand on its own; the icon and wash only reinforce it.
 */
@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(modifier = modifier.fillMaxWidth(), shape = NoticeShape, color = MaterialTheme.splitFree.warningContainer) {
        Row(
            modifier = Modifier.padding(horizontal = if (icon == null) 16.dp else 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.splitFree.warning
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Centered empty/zero state: a 64dp `surfaceContainer` tile with a `primary` [icon], `titleMedium` [title],
 * muted [body] and an optional [action] slot (typically an [SfSecondaryButton]).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(
                64.dp
            ).background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        action()
    }
}
