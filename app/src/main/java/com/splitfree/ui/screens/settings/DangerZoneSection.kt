package com.splitfree.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.splitfree.ui.viewmodels.RevokeState

@Composable
fun DangerZoneSection(revokeState: RevokeState, onRevoke: () -> Unit) {
    SectionHeader(icon = Icons.Outlined.Warning, title = "Danger Zone")

    ListItem(
        headlineContent = { Text("Revoke Key", color = MaterialTheme.colorScheme.error) },
        supportingContent = {
            Text("If your key is compromised, revoke it and generate a new identity. All groups will be updated.")
        },
        trailingContent = {
            FilledTonalButton(
                onClick = onRevoke,
                colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                enabled = revokeState !is RevokeState.InProgress
            ) {
                Text(if (revokeState is RevokeState.InProgress) "Revoking…" else "Revoke")
            }
        }
    )

    if (revokeState is RevokeState.Done) {
        ListItem(
            headlineContent = { Text("Key revoked successfully") },
            supportingContent = { Text("New pubkey: ${revokeState.newPubkey.take(12)}…") },
            leadingContent = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
        )
    }
}
