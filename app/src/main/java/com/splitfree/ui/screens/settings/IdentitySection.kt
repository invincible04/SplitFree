package com.splitfree.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.splitfree.ui.util.adaptiveSizeTokens

@Composable
fun IdentitySection(npub: String, onCopy: () -> Unit) {
    val tokens = adaptiveSizeTokens()

    SectionHeader(icon = Icons.Outlined.Person, title = "Identity")
    ListItem(
        headlineContent = { Text("Public Key") },
        supportingContent = {
            Text(
                npub.take(16) + "…" + npub.takeLast(8),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        },
        trailingContent = {
            FilledTonalButton(onClick = onCopy) { Text("Copy") }
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal))
}
