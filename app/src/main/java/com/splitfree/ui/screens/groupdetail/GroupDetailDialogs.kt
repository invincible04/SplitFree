package com.splitfree.ui.screens.groupdetail

import android.graphics.Bitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.ui.util.QrGenerator
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.util.CurrencyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ShareWarningDialog(inviteLink: String?, onShare: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_invite_title)) },
        text = {
            Text(stringResource(R.string.share_invite_warning))
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (inviteLink != null) onShare(inviteLink)
            }) { Text(stringResource(R.string.share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
        title = { Text(stringResource(R.string.invite_qr_code)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // Encoding runs off the main thread; the spinner below covers the short gap.
                val qrBitmap by produceState<Bitmap?>(initialValue = null, inviteLink) {
                    value = inviteLink?.let { link -> withContext(Dispatchers.Default) { QrGenerator.encode(link) } }
                }
                val bitmap = qrBitmap
                if (inviteLink != null && bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.invite_qr_content_desc),
                        modifier = Modifier.size(qrSize)
                    )
                    Spacer(Modifier.height(tokens.itemSpacing))
                    Text(
                        stringResource(R.string.scan_to_join, groupName),
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
                        stringResource(R.string.generating_invite_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    )
}

@Composable
fun SettleDialog(
    debt: DebtTransaction,
    memberNames: Map<String, String> = emptyMap(),
    members: Collection<String> = emptyList(),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = adaptiveSizeTokens()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settle_up)) },
        text = {
            Column {
                Text(stringResource(R.string.record_payment))
                Spacer(Modifier.height(tokens.itemSpacing))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PubkeyChip(debt.from, memberNames, members)
                    Text(" → ", style = MaterialTheme.typography.titleMedium)
                    PubkeyChip(debt.to, memberNames, members)
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
            Button(onClick = onConfirm) { Text(stringResource(R.string.confirm_payment)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
        title = { Text(stringResource(R.string.remove_member_title)) },
        text = {
            Text(stringResource(R.string.remove_member_body, name))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Compact chip showing a member's display name or truncated pubkey with avatar initial.
 *
 * @param everyone every pubkey that can appear next to this chip; when another member shares the same
 *   display name, a pubkey suffix is appended so the two stay distinguishable.
 */
@Composable
fun PubkeyChip(
    pubkey: String,
    memberNames: Map<String, String> = emptyMap(),
    everyone: Collection<String> = emptyList()
) {
    val tokens = adaptiveSizeTokens()
    val name = memberNames[pubkey]?.ifBlank { null }
    val label = if (name != null) {
        disambiguatedMemberName(pubkey, memberNames, everyone)
    } else {
        pubkey.take(6) + "…"
    }
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
        title = { Text(stringResource(R.string.group_relays_title, relays.size)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (isCreator) {
                        stringResource(R.string.relay_editor_hint_creator)
                    } else {
                        stringResource(R.string.relay_editor_hint_member)
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
                TextButton(onClick = { onSave { onDismiss() } }) { Text(stringResource(R.string.save)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
        dismissButton = {
            if (isCreator) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
