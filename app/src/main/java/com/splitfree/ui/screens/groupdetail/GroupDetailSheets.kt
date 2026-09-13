@file:OptIn(ExperimentalMaterial3Api::class)

package com.splitfree.ui.screens.groupdetail

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.ui.components.DetailRow
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MoneyText
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayEditor
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.components.SettingsRow
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfPrimaryButton
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfSheet
import com.splitfree.ui.components.SfSheetFooter
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.util.QrGenerator
import com.splitfree.ui.util.disambiguatedMemberName
import com.splitfree.ui.viewmodels.GroupDetailUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val QrTileSize = 184.dp
private val QrTileShape = RoundedCornerShape(20.dp)
private val QrTilePadding = 15.dp
private const val SECONDS_TO_MILLIS = 1000L

/** Renders whichever [sheet] is open and wires its buttons to [actions] / [onSheet]. */
@Composable
internal fun GroupDetailSheetHost(
    sheet: GroupSheet,
    state: GroupDetailUiState,
    inviteLink: String?,
    relayStatuses: Map<String, RelayCheckStatus>,
    relayInfo: Map<String, RelayInfo>,
    actions: GroupDetailActions,
    onSheet: (GroupSheet?) -> Unit
) {
    val dismiss = { onSheet(null) }
    when (sheet) {
        GroupSheet.Invite ->
            InviteSheet(
                groupName = state.groupName,
                inviteLink = inviteLink,
                onCopy = {
                    actions.copyInvite()
                    // The snackbar lives behind the sheet's scrim, so the sheet closes for the confirmation to show.
                    dismiss()
                },
                onShare = actions.share,
                onDismiss = dismiss
            )
        GroupSheet.Tools ->
            ToolsSheet(
                onNearbySync = {
                    dismiss()
                    actions.nearbySync()
                },
                onSyncStatus = {
                    actions.beginRelayEdit()
                    actions.checkAllRelays()
                    onSheet(GroupSheet.SyncStatus)
                },
                onDismiss = dismiss
            )
        GroupSheet.SyncStatus ->
            RelaysSheet(
                relays = state.draftRelays ?: state.relays,
                relayStatuses = relayStatuses,
                relayInfo = relayInfo,
                isCreator = state.isCreator,
                actions = actions,
                onSave = { actions.saveRelays { onSheet(null) } },
                onDismiss = {
                    actions.cancelRelayEdit()
                    dismiss()
                }
            )
        is GroupSheet.Settle ->
            SettleSheet(
                debt = sheet.debt,
                state = state,
                onConfirm = {
                    dismiss()
                    actions.confirmSettle(sheet.debt)
                },
                onDismiss = dismiss
            )
        is GroupSheet.ExpenseDetail -> {
            val expense = state.expenses.firstOrNull { it.identity == sheet.identity }
            if (expense == null) {
                // The expense left the ledger (excluded or the group refreshed) while the sheet was open.
                LaunchedEffect(sheet) { dismiss() }
            } else {
                ExpenseDetailSheet(
                    authored = expense,
                    state = state,
                    onEdit = {
                        dismiss()
                        actions.editExpense(expense.identity)
                    },
                    onDelete = {
                        dismiss()
                        actions.deleteExpense(expense.identity)
                    },
                    onDismiss = dismiss
                )
            }
        }
        is GroupSheet.RemoveMember ->
            RemoveMemberSheet(
                pubkey = sheet.pubkey,
                state = state,
                onConfirm = {
                    dismiss()
                    actions.removeMember(sheet.pubkey)
                },
                onDismiss = dismiss
            )
    }
}

/**
 * QR code, copy and share in one place. The bearer-key warning is part of the surface
 * rather than a separate confirm dialog; both actions stay disabled until the link exists.
 */
@Composable
private fun InviteSheet(
    groupName: String,
    inviteLink: String?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.group_invite_title, groupName),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_invite"),
        footer = {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SfSecondaryButton(
                    text = stringResource(R.string.group_copy_link),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).testTag("group_copy_invite"),
                    enabled = inviteLink != null,
                    leadingIcon = Icons.Outlined.ContentCopy
                )
                SfSecondaryButton(
                    text = stringResource(R.string.share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f).testTag("group_share_invite"),
                    enabled = inviteLink != null,
                    leadingIcon = Icons.Outlined.Share
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            QrTile(inviteLink)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.group_invite_copy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        WarningCard(text = stringResource(R.string.share_invite_warning))
    }
}

/** 184dp white tile: scanners need black-on-white in both themes, so the fill is not themed. */
@Composable
private fun QrTile(inviteLink: String?) {
    // Encoding runs off the main thread; the ring covers the short gap.
    val qrBitmap by produceState<Bitmap?>(initialValue = null, inviteLink) {
        value = inviteLink?.let { link -> withContext(Dispatchers.Default) { QrGenerator.encode(link) } }
    }
    val bitmap = qrBitmap
    val generating = stringResource(R.string.generating_invite_link)
    Box(
        modifier = Modifier.size(QrTileSize).background(Color.White, QrTileShape).padding(QrTilePadding),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.invite_qr_content_desc),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp).semantics { contentDescription = generating },
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Secondary tools behind the top-bar "more" button. */
@Composable
private fun ToolsSheet(onNearbySync: () -> Unit, onSyncStatus: () -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.group_tools),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_tools")
    ) {
        SfListCard {
            SettingsRow(
                icon = Icons.Outlined.Bluetooth,
                title = stringResource(R.string.nearby_sync),
                subtitle = stringResource(R.string.group_nearby_sync_subtitle),
                onClick = onNearbySync,
                modifier = Modifier.testTag("group_tool_nearby")
            )
            SfDivider()
            SettingsRow(
                icon = Icons.Outlined.CellTower,
                title = stringResource(R.string.group_sync_status_relays),
                subtitle = stringResource(R.string.group_sync_status_subtitle),
                onClick = onSyncStatus,
                modifier = Modifier.testTag("group_tool_relays")
            )
        }
    }
}

/** Relay list bound to the ViewModel draft: Cancel discards, Save persists; members only get Done. */
@Composable
private fun RelaysSheet(
    relays: List<String>,
    relayStatuses: Map<String, RelayCheckStatus>,
    relayInfo: Map<String, RelayInfo>,
    isCreator: Boolean,
    actions: GroupDetailActions,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.group_relays_title, relays.size),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_relays"),
        footer = {
            SfSheetFooter(
                secondary =
                if (isCreator) {
                    {
                        SfSecondaryButton(
                            text = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    null
                },
                primary = {
                    if (isCreator) {
                        SfPrimaryButton(text = stringResource(R.string.save), onClick = onSave)
                    } else {
                        SfPrimaryButton(text = stringResource(R.string.done), onClick = onDismiss)
                    }
                }
            )
        }
    ) {
        HintCard(
            text =
            stringResource(
                if (isCreator) R.string.relay_editor_hint_creator else R.string.relay_editor_hint_member
            ),
            icon = Icons.Outlined.CellTower
        )
        Spacer(Modifier.height(14.dp))
        RelayEditor(
            relays = relays,
            relayStatuses = relayStatuses,
            relayInfo = relayInfo,
            onAdd = actions.addRelay,
            onRemove = actions.removeRelay,
            onCheck = actions.checkRelay,
            editable = isCreator
        )
    }
}

/** Confirm recording a payment. The amount is the settlement; no money moves. */
@Composable
private fun SettleSheet(
    debt: DebtTransaction,
    state: GroupDetailUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val from = memberLabel(debt.from, state, youLabel = stringResource(R.string.you))
    val to = memberLabel(debt.to, state, youLabel = stringResource(R.string.group_you_object))
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.record_payment),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_settle"),
        footer = {
            SfSheetFooter(
                secondary = {
                    SfSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                primary = {
                    SfPrimaryButton(
                        text = stringResource(R.string.confirm_payment),
                        onClick = onConfirm,
                        modifier = Modifier.testTag("group_confirm_settle")
                    )
                }
            )
        }
    ) {
        CenteredAmount(amountMinor = debt.amount, currency = debt.currency) {
            Text(
                stringResource(R.string.group_settle_path, from, to),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))
        WarningCard(text = stringResource(R.string.group_settle_warning))
    }
}

/**
 * Breakdown of one expense: amount, title, meta line and per-member shares. The author gets Edit and a Delete
 * action that swaps the body for a confirmation step; everyone else reads who can change it.
 */
@Composable
private fun ExpenseDetailSheet(
    authored: AuthoredExpense,
    state: GroupDetailUiState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val expense = authored.expense
    var confirmingDelete by rememberSaveable(authored.identity) { mutableStateOf(false) }
    if (confirmingDelete) {
        DeleteExpenseConfirmation(onConfirm = onDelete, onCancel = { confirmingDelete = false }, onDismiss = onDismiss)
        return
    }
    val payer = memberLabel(expense.paidBy, state, youLabel = stringResource(R.string.you))
    val category = categoryLabel(expense.category)
    val date = remember(expense.timestamp) {
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(
            Date(
                expense.timestamp * SECONDS_TO_MILLIS
            )
        )
    }
    val mine = state.authoredByMe(authored.identity)
    val author = authored.authorPubkey
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.group_expense_details),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_expense"),
        footer = {
            SfSheetFooter(
                secondary =
                if (mine) {
                    {
                        SfTextButton(
                            text = stringResource(R.string.group_expense_delete),
                            onClick = { confirmingDelete = true },
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().testTag("group_expense_delete")
                        )
                    }
                } else {
                    null
                },
                primary = {
                    if (mine) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            SfSecondaryButton(
                                text = stringResource(R.string.edit),
                                onClick = onEdit,
                                modifier = Modifier.weight(1f).fillMaxHeight().testTag("group_expense_edit")
                            )
                            SfPrimaryButton(
                                text = stringResource(R.string.done),
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    } else {
                        SfPrimaryButton(text = stringResource(R.string.done), onClick = onDismiss)
                    }
                }
            )
        }
    ) {
        CenteredAmount(amountMinor = expense.amount, currency = expense.currency) {
            Text(
                expense.description,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.group_expense_detail_meta, payer, category, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(18.dp))
        SfListCard {
            expense.splitAmong.forEachIndexed { index, entry ->
                if (index > 0) SfDivider()
                DetailRow(label = memberLabel(entry.pubkey, state, youLabel = stringResource(R.string.you))) {
                    MoneyText(
                        amountMinor = entry.share,
                        currency = expense.currency,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (!mine && author.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.group_expense_only_author,
                    memberLabel(author, state, youLabel = stringResource(R.string.you))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("group_expense_only_author")
            )
        }
    }
}

/** Second step of deleting an expense: the question, its effect on balances, Cancel / Delete in `error`. */
@Composable
private fun DeleteExpenseConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.group_expense_delete_title),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_delete_expense"),
        footer = {
            SfSheetFooter(
                secondary = {
                    SfSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().testTag("group_cancel_delete")
                    )
                },
                primary = {
                    SfPrimaryButton(
                        text = stringResource(R.string.group_expense_delete),
                        onClick = onConfirm,
                        modifier = Modifier.testTag("group_confirm_delete"),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                }
            )
        }
    ) {
        Text(
            stringResource(R.string.group_expense_delete_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Confirm a key rotation that drops [pubkey]; the destructive primary is tinted `error`. */
@Composable
private fun RemoveMemberSheet(pubkey: String, state: GroupDetailUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = state.memberNames[pubkey]?.takeIf { it.isNotBlank() } ?: shortPubkey(pubkey)
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.remove_member),
        scrollable = true,
        modifier = Modifier.testTag("group_sheet_remove"),
        footer = {
            SfSheetFooter(
                secondary = {
                    SfSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                primary = {
                    SfPrimaryButton(
                        text = stringResource(R.string.remove),
                        onClick = onConfirm,
                        modifier = Modifier.testTag("group_confirm_remove"),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                }
            )
        }
    ) {
        Text(
            stringResource(R.string.remove_member_body, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** `displayMedium` money centred with whatever [below] it. */
@Composable
private fun CenteredAmount(amountMinor: Long, currency: String, below: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        MoneyText(
            amountMinor = amountMinor,
            currency = currency,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("group_sheet_amount")
        )
        Spacer(Modifier.height(5.dp))
        below()
    }
}

/** Display name (disambiguated) or short key; [youLabel] replaces my own entry. */
private fun memberLabel(pubkey: String, state: GroupDetailUiState, youLabel: String): String =
    if (pubkey == state.myPubkey) {
        youLabel
    } else if (state.memberNames[pubkey].isNullOrBlank()) {
        shortPubkey(pubkey)
    } else {
        disambiguatedMemberName(pubkey, state.memberNames, state.members)
    }

/** Localised label for the stable category keys the editor writes; unknown keys are shown as typed. */
@Composable
private fun categoryLabel(category: String): String {
    val resource =
        when (category.trim().lowercase(Locale.ROOT)) {
            "" -> R.string.expense_category_none
            "food" -> R.string.expense_category_food
            "transport" -> R.string.expense_category_transport
            "shopping" -> R.string.expense_category_shopping
            "entertainment" -> R.string.expense_category_entertainment
            "utilities" -> R.string.expense_category_utilities
            "rent" -> R.string.expense_category_rent
            "health" -> R.string.expense_category_health
            "other" -> R.string.expense_category_other
            else -> null
        }
    return if (resource != null) stringResource(resource) else category
}
