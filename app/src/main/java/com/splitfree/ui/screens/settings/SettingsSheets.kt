@file:OptIn(ExperimentalMaterial3Api::class)

package com.splitfree.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.splitfree.R
import com.splitfree.ui.components.ChoiceRow
import com.splitfree.ui.components.DetailRow
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.SettingsRow
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfPrimaryButton
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfSheet
import com.splitfree.ui.components.SfSheetFooter
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.theme.tabular
import com.splitfree.ui.util.WidthClass
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.viewmodels.ExportState
import com.splitfree.ui.viewmodels.RevokeState

private val PhraseCellShape = RoundedCornerShape(9.dp)
private val PhraseCellGap = 8.dp
private val PhraseCellPadding = 12.dp
private const val PHRASE_WORD_COUNT = 24
private const val PHRASE_COLUMNS = 3
private const val PHRASE_COLUMNS_COMPACT = 2
private const val NEW_PUBKEY_PREFIX = 12

/** Renders whichever [sheet] is open and wires its buttons to [actions] / [onSheet]. */
@Composable
internal fun SettingsSheetHost(
    sheet: SettingsSheet,
    state: SettingsUiState,
    actions: SettingsActions,
    onSheet: (SettingsSheet?) -> Unit
) {
    val dismiss = { onSheet(null) }
    when (sheet) {
        SettingsSheet.Profile ->
            ProfileSheet(
                displayName = state.displayName,
                onSave = {
                    actions.setDisplayName(it)
                    dismiss()
                },
                onDismiss = dismiss
            )
        SettingsSheet.Theme ->
            ThemeSheet(
                mode = state.themeMode,
                onSelect = {
                    actions.setThemeMode(it)
                    dismiss()
                },
                onDismiss = dismiss
            )
        SettingsSheet.Recovery ->
            RecoverySheet(
                onPhrase = { onSheet(SettingsSheet.Phrase) },
                onPrivateKey = { onSheet(SettingsSheet.PrivateKey) },
                onExport = { onSheet(SettingsSheet.Export) },
                onDismiss = dismiss
            )
        SettingsSheet.Phrase ->
            PhraseSheet(
                words = state.seedPhrase,
                onReveal = actions.revealSeed,
                onHide = actions.hideSeed,
                onCopy = actions.copySeed,
                onDismiss = {
                    // Closing the sheet always clears the secret from memory, whichever way it was closed.
                    actions.hideSeed()
                    dismiss()
                }
            )
        SettingsSheet.PrivateKey ->
            PrivateKeySheet(
                nsec = state.nsec,
                onReveal = actions.revealKey,
                onHide = actions.hideKey,
                onCopy = actions.copyKey,
                onDismiss = {
                    actions.hideKey()
                    dismiss()
                }
            )
        SettingsSheet.Export ->
            ExportSheet(
                exportState = state.exportState,
                onExport = actions.exportBackup,
                onDismiss = {
                    actions.clearExportState()
                    dismiss()
                }
            )
        SettingsSheet.Diagnostics ->
            DiagnosticsSheet(
                state = state,
                onCopyReport = actions.copyDiagnostics,
                onDismiss = dismiss
            )
        SettingsSheet.Revoke ->
            RevokeSheet(
                revokeState = state.revokeState,
                onConfirm = actions.revoke,
                onDismiss = {
                    actions.clearRevokeState()
                    dismiss()
                }
            )
    }
}

/** Display-name editor. The draft is local: Save commits, Cancel and dismiss discard. */
@Composable
private fun ProfileSheet(displayName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(displayName) }
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_profile_title),
        scrollable = true,
        modifier = Modifier.testTag("settings_sheet_profile"),
        footer = {
            SfSheetFooter(
                secondary = {
                    SfSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().testTag("settings_name_cancel")
                    )
                },
                primary = {
                    SfPrimaryButton(
                        text = stringResource(R.string.save),
                        onClick = { onSave(draft.trim()) },
                        modifier = Modifier.testTag("settings_name_save")
                    )
                }
            )
        }
    ) {
        MiniLabel(stringResource(R.string.display_name))
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().testTag("settings_name_field"),
            placeholder = {
                Text(
                    stringResource(R.string.display_name_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions =
            KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            shape = MaterialTheme.shapes.medium,
            colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_display_name_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** System / light / dark as one radio group; a choice applies at once and closes. */
@Composable
private fun ThemeSheet(mode: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.appearance),
        scrollable = true,
        modifier = Modifier.testTag("settings_sheet_theme")
    ) {
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEachIndexed { index, option ->
                if (index > 0) SfDivider()
                ChoiceRow(
                    title = themeLabel(option),
                    subtitle = if (option ==
                        ThemeMode.SYSTEM
                    ) {
                        stringResource(R.string.settings_theme_system_subtitle)
                    } else {
                        null
                    },
                    selected = option == mode,
                    onClick = { onSelect(option) },
                    modifier = Modifier.testTag("settings_theme_${option.name}")
                )
            }
        }
    }
}

/** Hub explaining the two halves of recovery, then one row per secret surface. */
@Composable
private fun RecoverySheet(onPhrase: () -> Unit, onPrivateKey: () -> Unit, onExport: () -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_backup_recovery),
        scrollable = true,
        modifier = Modifier.testTag("settings_sheet_recovery")
    ) {
        HintCard(text = stringResource(R.string.settings_recovery_intro), icon = Icons.Outlined.Shield)
        Spacer(Modifier.height(14.dp))
        SfListCard {
            SettingsRow(
                icon = Icons.Outlined.Key,
                title = stringResource(R.string.settings_recovery_phrase),
                subtitle = stringResource(R.string.settings_recovery_phrase_subtitle),
                onClick = onPhrase,
                modifier = Modifier.testTag("settings_recovery_phrase")
            )
            SfDivider()
            SettingsRow(
                icon = Icons.Outlined.Password,
                title = stringResource(R.string.private_key),
                subtitle = stringResource(R.string.settings_private_key_subtitle),
                onClick = onPrivateKey,
                modifier = Modifier.testTag("settings_recovery_key")
            )
            SfDivider()
            SettingsRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.settings_export_group_backup),
                subtitle = stringResource(R.string.settings_export_group_backup_subtitle),
                onClick = onExport,
                modifier = Modifier.testTag("settings_recovery_export")
            )
        }
    }
}

/**
 * The 24 recovery words. Masked cells until Reveal; the warning sits inline so revealing
 * is one explicit step. Copy still asks once more because the clipboard is readable by other apps.
 * The grid scrolls inside the sheet while Hide / Copy stay fixed below it, and the sheet's own window is
 * secure while the words are on screen.
 */
@Composable
private fun PhraseSheet(
    words: List<String>,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val revealed = words.isNotEmpty()
    val columns = phraseColumns()
    val hiddenLabel = stringResource(R.string.settings_phrase_hidden)
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_phrase_title),
        scrollable = true,
        secure = revealed,
        modifier = Modifier.testTag("settings_sheet_phrase"),
        footer = {
            SecretFooter(
                revealed = revealed,
                onReveal = onReveal,
                onHide = onHide,
                onCopy = onCopy,
                copyWarning = stringResource(R.string.copy_seed_warning),
                tagPrefix = "settings_phrase"
            )
        }
    ) {
        WarningCard(text = stringResource(R.string.key_backup_warning))
        Spacer(Modifier.height(14.dp))
        if (revealed) {
            PhraseGrid(
                cells = words.mapIndexed { i, word -> stringResource(R.string.settings_phrase_word, i + 1, word) },
                columns = columns,
                modifier = Modifier.testTag("settings_phrase_words")
            )
        } else {
            PhraseGrid(
                cells = List(PHRASE_WORD_COUNT) { i ->
                    stringResource(R.string.settings_phrase_masked_cell, i + 1)
                },
                columns = columns,
                modifier =
                Modifier
                    .testTag("settings_phrase_masked")
                    .clearAndSetSemantics { contentDescription = hiddenLabel }
            )
        }
    }
}

/**
 * The raw private key as one masked cell, then plain tabular text after Reveal. The revealed key carries no
 * selection handling: the guarded Copy in the footer is the only way it reaches the clipboard.
 */
@Composable
private fun PrivateKeySheet(
    nsec: String,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val revealed = nsec.isNotEmpty()
    val hiddenLabel = stringResource(R.string.settings_private_key_hidden)
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_private_key_title),
        scrollable = true,
        secure = revealed,
        modifier = Modifier.testTag("settings_sheet_key"),
        footer = {
            SecretFooter(
                revealed = revealed,
                onReveal = onReveal,
                onHide = onHide,
                onCopy = onCopy,
                copyWarning = stringResource(R.string.copy_key_warning),
                tagPrefix = "settings_key"
            )
        }
    ) {
        WarningCard(text = stringResource(R.string.key_backup_warning))
        Spacer(Modifier.height(14.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PhraseCellShape,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            if (revealed) {
                Text(
                    nsec,
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(PhraseCellPadding).testTag("settings_key_value")
                )
            } else {
                Text(
                    stringResource(R.string.settings_private_key_masked),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                    Modifier
                        .padding(PhraseCellPadding)
                        .testTag("settings_key_masked")
                        .clearAndSetSemantics { contentDescription = hiddenLabel }
                )
            }
        }
    }
}

/**
 * Reveal while hidden; Hide + Copy (with the clipboard confirmation) once revealed. The confirmation is
 * keyed on [revealed] so hiding the secret also withdraws a pending copy.
 */
@Composable
private fun SecretFooter(
    revealed: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    copyWarning: String,
    tagPrefix: String
) {
    // Plain remember: a rotation or process death drops the confirmation instead of restoring it.
    var confirmCopy by remember(revealed) { mutableStateOf(false) }
    if (revealed) {
        SfSheetFooter(
            secondary = {
                SfSecondaryButton(
                    text = stringResource(R.string.hide),
                    onClick = onHide,
                    modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_hide")
                )
            },
            primary = {
                SfPrimaryButton(
                    text = stringResource(R.string.copy),
                    onClick = { confirmCopy = true },
                    modifier = Modifier.testTag("${tagPrefix}_copy")
                )
            }
        )
    } else {
        SfSheetFooter(secondary = null, primary = {
            SfPrimaryButton(
                text = stringResource(R.string.reveal),
                onClick = onReveal,
                modifier = Modifier.testTag("${tagPrefix}_reveal")
            )
        })
    }
    if (confirmCopy) {
        SecurityWarningDialog(
            text = copyWarning,
            onConfirm = {
                confirmCopy = false
                onCopy()
            },
            onDismiss = { confirmCopy = false }
        )
    }
}

/** Grid of equal-width phrase cells: `surfaceContainer`, 9dp corners, 12dp padding. */
@Composable
private fun PhraseGrid(cells: List<String>, columns: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PhraseCellGap)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PhraseCellGap)) {
                row.forEach { cell ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = PhraseCellShape,
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            cell,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(PhraseCellPadding)
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun phraseColumns(): Int =
    if (adaptiveLayoutInfo().widthClass == WidthClass.Compact) PHRASE_COLUMNS_COMPACT else PHRASE_COLUMNS

/**
 * Encrypted group export. The primary shows progress while the ViewModel
 * writes; success and failure are rendered inline so the user sees them before leaving the sheet.
 */
@Composable
private fun ExportSheet(exportState: ExportState, onExport: () -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_export_title),
        scrollable = true,
        modifier = Modifier.testTag("settings_sheet_export"),
        footer = {
            if (exportState == ExportState.Done) {
                SfSheetFooter(secondary = null, primary = {
                    SfPrimaryButton(text = stringResource(R.string.done), onClick = onDismiss)
                })
            } else {
                ExportFooter(loading = exportState is ExportState.InProgress, onExport = onExport, onCancel = onDismiss)
            }
        }
    ) {
        HintCard(text = stringResource(R.string.export_hint), icon = Icons.Outlined.Description)
        when (exportState) {
            ExportState.Done -> {
                Spacer(Modifier.height(10.dp))
                HintCard(
                    text = stringResource(R.string.backup_exported),
                    icon = Icons.Outlined.Check,
                    modifier = Modifier.testTag("settings_export_done")
                )
            }
            is ExportState.Error -> {
                Spacer(Modifier.height(10.dp))
                WarningCard(
                    text = stringResource(R.string.export_failed) + "\n" + exportState.message,
                    modifier = Modifier.testTag("settings_export_error")
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun ExportFooter(loading: Boolean, onExport: () -> Unit, onCancel: () -> Unit) {
    SfSheetFooter(
        secondary = {
            SfSecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            )
        },
        primary = {
            SfPrimaryButton(
                text = stringResource(R.string.export),
                onClick = onExport,
                loading = loading,
                modifier = Modifier.testTag("settings_export_button")
            )
        }
    )
}

/** Outbox counts and version as detail rows, plus the copyable health report. */
@Composable
private fun DiagnosticsSheet(state: SettingsUiState, onCopyReport: () -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_diagnostics),
        scrollable = true,
        modifier = Modifier.testTag("settings_sheet_diagnostics"),
        footer = {
            SfSheetFooter(
                secondary = {
                    SfSecondaryButton(
                        text = stringResource(R.string.settings_copy_report),
                        onClick = onCopyReport,
                        modifier = Modifier.fillMaxWidth().testTag("settings_copy_report")
                    )
                },
                primary = { SfPrimaryButton(text = stringResource(R.string.done), onClick = onDismiss) }
            )
        }
    ) {
        SfListCard {
            DetailRow(label = stringResource(R.string.settings_pending_events), value = state.pendingOutbox.toString())
            SfDivider()
            DetailRow(label = stringResource(R.string.settings_stuck_events), value = state.stuckOutbox.toString())
            SfDivider()
            DetailRow(label = stringResource(R.string.settings_version), value = state.appVersion)
        }
    }
}

/**
 * Identity replacement: the warning and the consequences stay on screen until the user
 * confirms with the `error`-tinted primary. Success and failure are both rendered inline so neither
 * outcome is lost.
 */
@Composable
private fun RevokeSheet(revokeState: RevokeState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.revoke_key_title),
        scrollable = true,
        modifier = Modifier.testTag("settings_sheet_revoke"),
        footer = {
            if (revokeState is RevokeState.Done) {
                SfSheetFooter(secondary = null, primary = {
                    SfPrimaryButton(text = stringResource(R.string.done), onClick = onDismiss)
                })
            } else {
                val inProgress = revokeState is RevokeState.InProgress
                SfSheetFooter(
                    secondary = {
                        SfSecondaryButton(
                            text = stringResource(R.string.cancel),
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !inProgress
                        )
                    },
                    primary = {
                        SfPrimaryButton(
                            text = stringResource(R.string.settings_replace_identity),
                            onClick = onConfirm,
                            loading = inProgress,
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.testTag("settings_revoke_confirm")
                        )
                    }
                )
            }
        }
    ) {
        if (revokeState is RevokeState.Done) {
            HintCard(
                text =
                stringResource(R.string.key_revoked) + "\n" +
                    stringResource(R.string.new_pubkey, revokeState.newPubkey.take(NEW_PUBKEY_PREFIX)),
                icon = Icons.Outlined.Check,
                modifier = Modifier.testTag("settings_revoke_done")
            )
        } else {
            WarningCard(text = stringResource(R.string.revoke_key_warning))
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.settings_revoke_consequences),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (revokeState is RevokeState.Error) {
                Spacer(Modifier.height(14.dp))
                WarningCard(text = revokeState.message, modifier = Modifier.testTag("settings_revoke_error"))
            }
        }
    }
}

/**
 * The clipboard confirmation for secrets: a dialog above the sheet, destructive confirm, Cancel escapes.
 * Its window is secure because it is shown over a revealed secret.
 */
@Composable
private fun SecurityWarningDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = stringResource(R.string.cd_warning_icon),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.security_warning)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("settings_copy_confirm")) {
                Text(stringResource(R.string.copy_anyway), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.extraLarge
    )
}
