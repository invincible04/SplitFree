package com.splitfree.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.splitfree.ui.util.adaptiveSizeTokens

@Composable
fun BackupSection(onExportAll: () -> Unit) {
    val tokens = adaptiveSizeTokens()
    SectionHeader(icon = Icons.Outlined.CloudDownload, title = "Backup")

    ListItem(
        modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal),
        headlineContent = { Text("Export All Groups") },
        supportingContent = { Text("Save all groups to a .splitfree backup file.") },
        leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
        trailingContent = { FilledTonalButton(onClick = onExportAll) { Text("Export") } }
    )
}
