package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.ui.util.QrGenerator

@Composable
fun ShareWarningDialog(inviteLink: String?, onShare: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share invite link?") },
        text = {
            Text(
                "This link contains the group encryption key. Anyone with this link can join and read all expenses. Share only via private messages — avoid public channels or group chats where bots may preview the URL."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (inviteLink != null) onShare(inviteLink)
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun QrDialog(inviteLink: String?, groupName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite QR Code") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (inviteLink != null) {
                    val qrBitmap = remember(inviteLink) { QrGenerator.encode(inviteLink) }
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Invite QR code",
                        modifier = Modifier.size(256.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Scan to join $groupName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Generating invite link…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
fun SettleDialog(debt: DebtTransaction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle Up") },
        text = {
            Column {
                Text("Record payment:")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PubkeyChip(debt.from)
                    Text(" → ", style = MaterialTheme.typography.titleMedium)
                    PubkeyChip(debt.to)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    com.splitfree.util.CurrencyFormatter
                        .format(debt.amount, debt.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Confirm Payment") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun RemoveMemberDialog(pubkey: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Member") },
        text = {
            Text(
                "Remove ${pubkey.take(
                    8
                )}…? This creates a new group without them. All remaining members will be migrated automatically."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PubkeyChip(pubkey: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp
    ) {
        Text(
            text = pubkey.take(6) + "…",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
