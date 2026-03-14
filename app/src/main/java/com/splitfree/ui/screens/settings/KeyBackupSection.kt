package com.splitfree.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.splitfree.R
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens

@Composable
fun KeyBackupSection(
    showKey: Boolean,
    nsec: String,
    showSeedPhrase: Boolean,
    seedPhrase: List<String>,
    onRevealKey: () -> Unit,
    onHideKey: () -> Unit,
    onCopyKey: () -> Unit,
    onRevealSeed: () -> Unit,
    onHideSeed: () -> Unit,
    onCopySeed: () -> Unit
) {
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val seedColumns = if (adaptive.isCompact) 2 else 3

    SectionHeader(icon = Icons.Outlined.Key, title = stringResource(R.string.key_backup))

    Card(
        modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(tokens.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = stringResource(R.string.cd_warning_icon),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(tokens.iconMedium)
            )
            Text(
                stringResource(R.string.key_backup_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    Spacer(Modifier.height(tokens.itemSpacing))

    if (showKey) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.private_key)) },
            supportingContent = {
                Text(nsec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            },
            trailingContent = { FilledTonalButton(onClick = onCopyKey) { Text(stringResource(R.string.copy)) } }
        )
        TextButton(onClick = onHideKey, modifier = Modifier.padding(start = tokens.screenPaddingHorizontal)) {
            Text(stringResource(R.string.hide_key))
        }
    } else {
        ListItem(
            headlineContent = { Text(stringResource(R.string.show_private_key)) },
            supportingContent = { Text(stringResource(R.string.tap_to_reveal_key)) },
            leadingContent = {
                Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.cd_show_key_icon))
            },
            trailingContent = { FilledTonalButton(onClick = onRevealKey) { Text(stringResource(R.string.reveal)) } }
        )
    }

    Spacer(Modifier.height(tokens.denseSpacing))

    if (showSeedPhrase) {
        Column(modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal)) {
            Text(stringResource(R.string.seed_phrase), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(tokens.itemSpacing))
            for (rowStart in seedPhrase.indices step seedColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
                ) {
                    for (i in rowStart until minOf(rowStart + seedColumns, seedPhrase.size)) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        ) {
                            Text(
                                "${i + 1}. ${seedPhrase[i]}",
                                modifier = Modifier.padding(
                                    horizontal = tokens.itemSpacing,
                                    vertical = tokens.seedWordVerticalPadding
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.height(tokens.denseSpacing))
            }
            Spacer(Modifier.height(tokens.itemSpacing))
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
                TextButton(onClick = onHideSeed) { Text(stringResource(R.string.hide)) }
                FilledTonalButton(onClick = onCopySeed) { Text(stringResource(R.string.copy)) }
            }
        }
    } else {
        ListItem(
            headlineContent = { Text(stringResource(R.string.show_seed_phrase)) },
            supportingContent = { Text(stringResource(R.string.seed_phrase_hint)) },
            leadingContent = {
                Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.cd_show_seed_icon))
            },
            trailingContent = { FilledTonalButton(onClick = onRevealSeed) { Text(stringResource(R.string.reveal)) } }
        )
    }
}
