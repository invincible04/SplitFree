package com.splitfree.ui.screens.groupdetail

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.ui.util.QrGenerator
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.util.CurrencyFormatter

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
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val configuration = LocalConfiguration.current
    val effectiveWidthDp = configuration.screenWidthDp / adaptive.fontScale
    val qrSize =
        ((effectiveWidthDp * if (adaptive.isCompact) 0.62f else 0.7f).dp)
            .coerceIn(180.dp, 320.dp)

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
                        modifier = Modifier.size(qrSize)
                    )
                    Spacer(Modifier.height(tokens.itemSpacing))
                    Text(
                        "Scan to join $groupName",
                        style = if (adaptive.isCompact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.padding(tokens.dialogProgressPadding))
                    Spacer(Modifier.height(tokens.itemSpacing))
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
fun SettleDialog(
    debt: DebtTransaction,
    memberNames: Map<String, String> = emptyMap(),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = adaptiveSizeTokens()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settle Up") },
        text = {
            Column {
                Text("Record payment:")
                Spacer(Modifier.height(tokens.itemSpacing))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PubkeyChip(debt.from, memberNames)
                    Text(" → ", style = MaterialTheme.typography.titleMedium)
                    PubkeyChip(debt.to, memberNames)
                }
                Spacer(Modifier.height(tokens.itemSpacing))
                Text(
                    CurrencyFormatter.format(debt.amount, debt.currency),
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
fun RemoveMemberDialog(
    pubkey: String,
    memberNames: Map<String, String> = emptyMap(),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val name = memberNames[pubkey]?.ifBlank { null } ?: (pubkey.take(8) + "…")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Member") },
        text = {
            Text(
                "Remove $name? This creates a new group without them. All remaining members will be migrated automatically."
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

/** Compact chip showing a member's display name or truncated pubkey with avatar initial. */
@Composable
fun PubkeyChip(pubkey: String, memberNames: Map<String, String> = emptyMap()) {
    val tokens = adaptiveSizeTokens()
    val name = memberNames[pubkey]?.ifBlank { null }
    val label = name ?: (pubkey.take(6) + "…")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.itemSpacing, vertical = tokens.denseSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.denseSpacing)
        ) {
            Box(
                modifier = Modifier
                    .size(tokens.pubkeyChipAvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name?.first()?.uppercase() ?: "#",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun RelayDialog(
    relays: List<String>,
    relayStatuses: Map<String, com.splitfree.ui.components.RelayCheckStatus>,
    relayInfo: Map<String, com.splitfree.ui.components.RelayInfo> = emptyMap(),
    isCreator: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCheck: (String) -> Unit,
    onSave: (() -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = adaptiveSizeTokens()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group Relays (${relays.size})") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (isCreator) {
                        "Manage relays for this group. Changes are broadcast to all members."
                    } else {
                        "Relays this group syncs through. Only the group creator can edit."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(tokens.sectionSpacing))
                com.splitfree.ui.components.RelayEditor(
                    relays = relays,
                    relayStatuses = relayStatuses,
                    relayInfo = relayInfo,
                    onAdd = onAdd,
                    onRemove = onRemove,
                    onCheck = onCheck,
                    editable = isCreator
                )
            }
        },
        confirmButton = {
            if (isCreator) {
                TextButton(onClick = { onSave { onDismiss() } }) { Text("Save") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (isCreator) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
