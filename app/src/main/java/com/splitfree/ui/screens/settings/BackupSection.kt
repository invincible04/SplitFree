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
import androidx.compose.ui.res.stringResource
import com.splitfree.R
import com.splitfree.ui.util.adaptiveSizeTokens

/**
 * @param exporting true while a backup is being written; disables the button so a second export
 *   cannot race the first one on the same document
 */
@Composable
fun BackupSection(onExportAll: () -> Unit, exporting: Boolean = false) {
    val tokens = adaptiveSizeTokens()
    SectionHeader(icon = Icons.Outlined.CloudDownload, title = stringResource(R.string.backup))

    ListItem(
        modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal),
        headlineContent = { Text(stringResource(R.string.export_all_groups)) },
        supportingContent = { Text(stringResource(R.string.export_hint)) },
        leadingContent = {
            Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.cd_export_icon))
        },
        trailingContent = {
            FilledTonalButton(onClick = onExportAll, enabled = !exporting) { Text(stringResource(R.string.export)) }
        }
    )
}
