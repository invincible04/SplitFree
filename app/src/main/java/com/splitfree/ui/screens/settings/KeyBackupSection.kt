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
import androidx.compose.ui.unit.dp

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
    SectionHeader(icon = Icons.Outlined.Key, title = "Key Backup")

    Card(
        modifier = Modifier.padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "Your private key is your identity. If you lose it, you lose access to all your groups forever. Back it up now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    if (showKey) {
        ListItem(
            headlineContent = { Text("Private Key") },
            supportingContent = {
                Text(nsec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            },
            trailingContent = { FilledTonalButton(onClick = onCopyKey) { Text("Copy") } }
        )
        TextButton(onClick = onHideKey, modifier = Modifier.padding(start = 16.dp)) { Text("Hide Key") }
    } else {
        ListItem(
            headlineContent = { Text("Show Private Key") },
            supportingContent = { Text("Tap to reveal your secret key") },
            leadingContent = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
            trailingContent = { FilledTonalButton(onClick = onRevealKey) { Text("Reveal") } }
        )
    }

    Spacer(Modifier.height(4.dp))

    if (showSeedPhrase) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Seed Phrase", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            for (rowStart in seedPhrase.indices step 3) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in rowStart until minOf(rowStart + 3, seedPhrase.size)) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        ) {
                            Text(
                                "${i + 1}. ${seedPhrase[i]}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onHideSeed) { Text("Hide") }
                FilledTonalButton(onClick = onCopySeed) { Text("Copy") }
            }
        }
    } else {
        ListItem(
            headlineContent = { Text("Show Seed Phrase") },
            supportingContent = { Text("24 words to recover your identity on any device") },
            leadingContent = { Icon(Icons.Outlined.GridView, contentDescription = null) },
            trailingContent = { FilledTonalButton(onClick = onRevealSeed) { Text("Reveal") } }
        )
    }
}
