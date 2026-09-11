package com.splitfree.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.BuildConfig
import com.splitfree.R
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.theme.ThemePreference
import com.splitfree.ui.theme.ThemeTransitionState
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.RevokeState
import com.splitfree.ui.viewmodels.SettingsViewModel
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.ProcessHealthTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onDebugLog: () -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val nsec by viewModel.nsec.collectAsStateWithLifecycle()
    val npub by viewModel.npub.collectAsStateWithLifecycle()
    val seedPhrase by viewModel.seedPhrase.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    var showSeedPhrase by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val backupExportedMsg = stringResource(R.string.backup_exported)
    val backupFailedMsg = stringResource(R.string.export_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // openOutputStream throws when the picked target is gone or permission was
            // revoked, and the export itself can fail mid-write; both used to escape this
            // scope uncaught, and a null stream reported success without writing anything.
            val exported = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use {
                        viewModel.exportAllGroups(it)
                        true
                    } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Export failed: ${e.message}")
                    false
                }
            }
            snackbarHostState.showSnackbar(if (exported) backupExportedMsg else backupFailedMsg)
        }
    }

    // FLAG_SECURE: prevent screenshots/recording when private key or seed phrase is visible
    val view = LocalView.current
    DisposableEffect(showKey, showSeedPhrase) {
        val window = (view.context as? android.app.Activity)?.window
        if (showKey || showSeedPhrase) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    var showCopyWarning by remember { mutableStateOf(false) }
    var showCopySeedWarning by remember { mutableStateOf(false) }
    var giftWrapEnabled by remember { mutableStateOf(viewModel.giftWrapEnabled) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    val revokeState by viewModel.revokeState.collectAsStateWithLifecycle()
    LaunchedEffect(revokeState) {
        if (revokeState is RevokeState.Done) {
            if (showKey) viewModel.revealPrivateKey()
            if (showSeedPhrase) viewModel.revealSeedPhrase()
        }
    }
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            // Profile — display name at the very top
            ProfileSection(
                displayName = displayName,
                onNameChange = { viewModel.setDisplayName(it.take(50)) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.itemSpacing)
            )

            IdentitySection(npub = npub, onCopy = { copyToClipboard(context, "npub", npub) })

            KeyBackupSection(
                showKey = showKey,
                nsec = nsec,
                showSeedPhrase = showSeedPhrase,
                seedPhrase = seedPhrase,
                onRevealKey = {
                    showKey = true
                    viewModel.revealPrivateKey()
                },
                onHideKey = {
                    showKey = false
                    viewModel.hidePrivateKey()
                },
                onCopyKey = { showCopyWarning = true },
                onRevealSeed = {
                    showSeedPhrase = true
                    viewModel.revealSeedPhrase()
                },
                onHideSeed = {
                    showSeedPhrase = false
                    viewModel.hideSeedPhrase()
                },
                onCopySeed = { showCopySeedWarning = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.itemSpacing)
            )

            // Appearance
            SectionHeader(icon = Icons.Outlined.Palette, title = stringResource(R.string.appearance))
            val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal).fillMaxWidth()
            ) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = {
                            if (themeMode !=
                                mode
                            ) {
                                ThemeTransitionState.captureAndChange(view) { ThemePreference.set(context, mode) }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                    ) {
                        Text(
                            text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(tokens.itemSpacing))

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.itemSpacing)
            )

            PrivacySection(
                giftWrapEnabled = giftWrapEnabled,
                onGiftWrapChange = {
                    giftWrapEnabled = it
                    viewModel.giftWrapEnabled = it
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.itemSpacing)
            )

            BackupSection(onExportAll = {
                exportLauncher.launch("splitfree-backup.splitfree")
            })

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.itemSpacing)
            )

            DangerZoneSection(revokeState = revokeState, onRevoke = { showRevokeDialog = true })

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.itemSpacing)
            )

            // About
            SectionHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_version)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.app_description),
                        style = if (adaptive.isCompact) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.diagnostics_report)) },
                supportingContent = { Text(stringResource(R.string.diagnostics_supporting)) },
                leadingContent = {
                    Icon(Icons.Outlined.BugReport, contentDescription = stringResource(R.string.cd_diagnostics_icon))
                },
                trailingContent = {
                    FilledTonalButton(
                        onClick = {
                            val report = ProcessHealthTracker.buildReport(context)
                            copyToClipboard(context, "diagnostics", report)
                        }
                    ) { Text(stringResource(R.string.copy)) }
                }
            )
            if (BuildConfig.DEBUG) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.debug_logs)) },
                    supportingContent = { Text(stringResource(R.string.debug_logs_supporting)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info_icon))
                    },
                    trailingContent = {
                        FilledTonalButton(onClick = onDebugLog) { Text(stringResource(R.string.open)) }
                    }
                )
            }
            Spacer(Modifier.height(tokens.screenBottomSpacer))
        }
    }

    // Dialogs
    if (showCopyWarning) {
        SecurityWarningDialog(
            text = stringResource(R.string.copy_key_warning),
            onConfirm = {
                copyToClipboard(context, "nsec", nsec)
                showCopyWarning = false
            },
            onDismiss = { showCopyWarning = false }
        )
    }
    if (showCopySeedWarning) {
        SecurityWarningDialog(
            text = stringResource(R.string.copy_seed_warning),
            onConfirm = {
                copyToClipboard(context, "seed", seedPhrase.joinToString(" "))
                showCopySeedWarning = false
            },
            onDismiss = { showCopySeedWarning = false }
        )
    }
    if (showRevokeDialog) {
        SecurityWarningDialog(
            title = stringResource(R.string.revoke_key_title),
            text = stringResource(R.string.revoke_key_warning),
            confirmText = stringResource(R.string.revoke_key),
            onConfirm = {
                showRevokeDialog = false
                viewModel.revokeKey()
            },
            onDismiss = { showRevokeDialog = false }
        )
    }
}

@Composable
private fun SecurityWarningDialog(
    title: String = stringResource(R.string.security_warning),
    text: String,
    confirmText: String = stringResource(R.string.copy_anyway),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = stringResource(R.string.cd_warning_icon),
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.clipboard_copied_30s), Toast.LENGTH_SHORT).show()
    if (label == "nsec" || label == "seed") {
        val copiedText = text
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Only clear if clipboard still contains the sensitive data we copied
                val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == copiedText) {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("", "")
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }, 30_000)
    }
}
