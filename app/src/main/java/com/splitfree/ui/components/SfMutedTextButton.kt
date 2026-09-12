package com.splitfree.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val MutedTextButtonHeight = 48.dp
private const val DISABLED_CONTENT_ALPHA = 0.35f

/**
 * Quiet text action in `onSurfaceVariant` for a secondary path that must not compete with the screen's
 * primary button (mock `.onboard-actions .text-btn`: "Restore an existing identity"). Same 48dp target and
 * `labelMedium` label as [SfTextButton]; only the colour differs, so it reads as a link rather than a call to
 * action.
 */
@Composable
fun SfMutedTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MutedTextButtonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors =
        ButtonDefaults.textButtonColors(
            contentColor = content,
            disabledContentColor = content.copy(alpha = DISABLED_CONTENT_ALPHA)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
