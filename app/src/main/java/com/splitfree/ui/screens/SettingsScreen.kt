package com.splitfree.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.ui.viewmodels.RevokeState
import com.splitfree.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDebugLog: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val nsec by viewModel.nsec.collectAsStateWithLifecycle()
    val npub by viewModel.npub.collectAsStateWithLifecycle()
    val seedPhrase by viewModel.seedPhrase.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    var showSeedPhrase by remember { mutableStateOf(false) }

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
    // Refresh revealed secrets after key revocation (keep visible, show new key)
    LaunchedEffect(revokeState) {
        if (revokeState is RevokeState.Done) {
            if (showKey) viewModel.revealPrivateKey()
            if (showSeedPhrase) viewModel.revealSeedPhrase()
        }
    }
    val customRelays by viewModel.customRelays.collectAsStateWithLifecycle()
    var showRelayEditor by remember { mutableStateOf(false) }
    var relayInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            // Identity section
            SectionHeader(icon = Icons.Outlined.Person, title = "Identity")

            ListItem(
                headlineContent = { Text("Public Key") },
                supportingContent = {
                    Text(
                        npub.take(16) + "…" + npub.takeLast(8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = { copyToClipboard(context, "npub", npub) }) {
                        Text("Copy")
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Key backup section
            SectionHeader(icon = Icons.Outlined.Key, title = "Key Backup")

            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Your private key is your identity. If you lose it, you lose access to all your groups forever. Back it up now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (showKey) {
                ListItem(
                    headlineContent = { Text("Private Key") },
                    supportingContent = {
                        Text(
                            nsec,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = {
                        FilledTonalButton(onClick = { showCopyWarning = true }) {
                            Text("Copy")
                        }
                    },
                )
                TextButton(
                    onClick = {
                        showKey = false
                        viewModel.hidePrivateKey()
                    },
                    modifier = Modifier.padding(start = 16.dp),
                ) {
                    Text("Hide Key")
                }
            } else {
                ListItem(
                    headlineContent = { Text("Show Private Key") },
                    supportingContent = { Text("Tap to reveal your secret key") },
                    leadingContent = {
                        Icon(Icons.Outlined.Visibility, contentDescription = null)
                    },
                    modifier =
                        Modifier.let { mod ->
                            @Suppress("DEPRECATION")
                            mod
                        },
                    trailingContent = {
                        FilledTonalButton(onClick = {
                            showKey = true
                            viewModel.revealPrivateKey()
                        }) {
                            Text("Reveal")
                        }
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            if (showSeedPhrase) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Seed Phrase", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    // 3-column grid of numbered words
                    for (rowStart in seedPhrase.indices step 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (i in rowStart until minOf(rowStart + 3, seedPhrase.size)) {
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                ) {
                                    Text(
                                        "${i + 1}. ${seedPhrase[i]}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            showSeedPhrase = false
                            viewModel.hideSeedPhrase()
                        }) {
                            Text("Hide")
                        }
                        FilledTonalButton(onClick = { showCopySeedWarning = true }) {
                            Text("Copy")
                        }
                    }
                }
            } else {
                ListItem(
                    headlineContent = { Text("Show Seed Phrase") },
                    supportingContent = { Text("24 words to recover your identity on any device") },
                    leadingContent = {
                        Icon(Icons.Outlined.GridView, contentDescription = null)
                    },
                    trailingContent = {
                        FilledTonalButton(onClick = {
                            showSeedPhrase = true
                            viewModel.revealSeedPhrase()
                        }) {
                            Text("Reveal")
                        }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SectionHeader(icon = Icons.Outlined.Shield, title = "Privacy")

            ListItem(
                headlineContent = { Text("NIP-59 Gift Wrap") },
                supportingContent = {
                    Text("Hide your identity from relay operators. Increases event size.")
                },
                trailingContent = {
                    Switch(
                        checked = giftWrapEnabled,
                        onCheckedChange = {
                            giftWrapEnabled = it
                            viewModel.giftWrapEnabled = it
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("Custom Relays") },
                supportingContent = {
                    if (customRelays.isEmpty()) {
                        Text("Using default public relays. Tap to configure your own.")
                    } else {
                        Text("${customRelays.size} custom relay(s) configured")
                    }
                },
                trailingContent = {
                    FilledTonalButton(onClick = {
                        relayInput = customRelays.joinToString("\n")
                        showRelayEditor = true
                    }) {
                        Text("Edit")
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Danger zone
            SectionHeader(icon = Icons.Outlined.Warning, title = "Danger Zone")

            ListItem(
                headlineContent = { Text("Revoke Key", color = MaterialTheme.colorScheme.error) },
                supportingContent = {
                    Text("If your key is compromised, revoke it and generate a new identity. All groups will be updated.")
                },
                trailingContent = {
                    FilledTonalButton(
                        onClick = { showRevokeDialog = true },
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        enabled = revokeState !is RevokeState.InProgress,
                    ) {
                        Text(if (revokeState is RevokeState.InProgress) "Revoking…" else "Revoke")
                    }
                },
            )

            if (revokeState is RevokeState.Done) {
                ListItem(
                    headlineContent = { Text("Key revoked successfully") },
                    supportingContent = { Text("New pubkey: ${(revokeState as RevokeState.Done).newPubkey.take(12)}…") },
                    leadingContent = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // About section
            SectionHeader(icon = Icons.Outlined.Info, title = "About")

            ListItem(
                headlineContent = { Text("SplitFree v1.0.0") },
                supportingContent = { Text("Decentralized expense splitting over Nostr") },
            )

            ListItem(
                headlineContent = { Text("Debug Logs") },
                supportingContent = { Text("View live app logs for troubleshooting") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                trailingContent = {
                    FilledTonalButton(onClick = onDebugLog) { Text("Open") }
                },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showCopyWarning) {
        AlertDialog(
            onDismissRequest = { showCopyWarning = false },
            icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Security Warning") },
            text = {
                Text(
                    "Your private key will be copied to the clipboard. Other apps may be able to read it. Only do this to back up your key, then clear your clipboard.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyToClipboard(context, "nsec", nsec)
                        showCopyWarning = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Copy Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyWarning = false }) { Text("Cancel") }
            },
        )
    }

    if (showCopySeedWarning) {
        AlertDialog(
            onDismissRequest = { showCopySeedWarning = false },
            icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Security Warning") },
            text = {
                Text(
                    "Your seed phrase will be copied to the clipboard. Anyone with these 24 words can access your identity. Write them down on paper instead if possible.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyToClipboard(context, "seed", seedPhrase.joinToString(" "))
                        showCopySeedWarning = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Copy Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showCopySeedWarning = false }) { Text("Cancel") }
            },
        )
    }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Revoke Key?") },
            text = {
                Text(
                    "This will generate a new identity and notify all your groups. Your old key will be invalidated. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRevokeDialog = false
                        viewModel.revokeKey()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Revoke Key") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRelayEditor) {
        AlertDialog(
            onDismissRequest = { showRelayEditor = false },
            title = { Text("Custom Relays") },
            text = {
                Column {
                    Text(
                        "Enter one wss:// relay URL per line. Leave empty to use defaults.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = relayInput,
                        onValueChange = { relayInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("wss://relay.example.com") },
                        minLines = 3,
                        maxLines = 6,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val relays = relayInput.lines().map { it.trim() }.filter { it.startsWith("wss://") }
                    viewModel.setCustomRelays(relays)
                    showRelayEditor = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRelayEditor = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    // Mark as sensitive (Android 13+) — prevents clipboard content from appearing in previews
    clip.description.extras =
        PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied — clipboard will auto-clear in 30s", Toast.LENGTH_SHORT).show()
    // Auto-clear clipboard after 30 seconds to limit exposure window
    if (label == "nsec" || label == "seed") {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                clipboard.clearPrimaryClip()
            } catch (_: Exception) {
            }
        }, 30_000)
    }
}
