package com.splitfree.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R

private val PrimaryButtonHeight = 56.dp
private val SecondaryButtonHeight = 50.dp
private val TextButtonHeight = 48.dp
private val IconButtonSize = 48.dp
private const val DISABLED_CONTAINER_ALPHA = 0.35f

/**
 * The single main action of a screen: "ink" fill (`inverseSurface` / `inverseOnSurface`), 56dp, full width,
 * `large` corners (mock `.primary`). While [loading] the label is hidden behind an 18dp ring and the button
 * is not clickable, but it keeps its full colour so the screen does not appear to have lost its action.
 * [containerColor] / [contentColor] exist for the destructive confirm in a sheet (`error` / `onError`);
 * every other caller keeps the ink default.
 */
@Composable
fun SfPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface
) {
    SfFilledButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        leadingIcon = leadingIcon,
        container = containerColor,
        content = contentColor
    )
}

/**
 * Citron/lime variant of [SfPrimaryButton] (`tertiaryContainer` / `onTertiaryContainer`) reserved for
 * create/add actions: New group, Create group, Add expense FAB, Get started (mock `.primary.lime`, `.fab`).
 */
@Composable
fun SfAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    SfFilledButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        leadingIcon = leadingIcon,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer
    )
}

@Composable
private fun SfFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    leadingIcon: ImageVector?,
    container: Color,
    content: Color
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = PrimaryButtonHeight),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.large,
        colors =
        ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (loading) container else container.copy(alpha = DISABLED_CONTAINER_ALPHA),
            disabledContentColor = content
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.alpha(if (loading) 0f else 1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                }
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (loading) {
                val loadingLabel = stringResource(R.string.cd_loading)
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).semantics { contentDescription = loadingLabel },
                    color = content,
                    trackColor = content.copy(alpha = DISABLED_CONTAINER_ALPHA),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Quiet alternative action: card fill (`surfaceContainerLowest`) with a 1dp `outlineVariant` border, 50dp
 * tall, `medium` corners (mock `.secondary`). Sizes to its content; pass a width/weight modifier as needed.
 */
@Composable
fun SfSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = SecondaryButtonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors =
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Inline text action (Invite, See all, Edit, Record payment). 48dp tall, `labelMedium`, `primary` by
 * default; pass another [color] for a destructive or quiet variant.
 */
@Composable
fun SfTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = TextButtonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors =
        ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = color.copy(alpha = DISABLED_CONTAINER_ALPHA)
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 48dp round icon button with a ripple-only pressed state (mock `.icon-btn`). Always give it a description. */
@Composable
fun SfIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(IconButtonSize),
        enabled = enabled,
        colors =
        IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = tint.copy(alpha = DISABLED_CONTAINER_ALPHA)
        )
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}
