package com.splitfree.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitfree.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val nsec = viewModel.nsec
    val npub = viewModel.npub
    var showKey by remember { mutableStateOf(false) }
    var showCopyWarning by remember { mutableStateOf(false) }
    var giftWrapEnabled by remember { mutableStateOf(viewModel.giftWrapEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Identity section
            SectionHeader(icon = Icons.Outlined.Person, title = "Identity")

            ListItem(
                headlineContent = { Text("Public Key") },
                supportingContent = {
                    Text(
                        npub.take(16) + "…" + npub.takeLast(8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = { copyToClipboard(context, "npub", npub) }) {
                        Text("Copy")
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Key backup section
            SectionHeader(icon = Icons.Outlined.Key, title = "Key Backup")

            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Your private key is your identity. If you lose it, you lose access to all your groups forever. Back it up now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
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
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    trailingContent = {
                        FilledTonalButton(onClick = { showCopyWarning = true }) {
                            Text("Copy")
                        }
                    }
                )
                TextButton(
                    onClick = { showKey = false },
                    modifier = Modifier.padding(start = 16.dp)
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
                    modifier = Modifier.let { mod ->
                        @Suppress("DEPRECATION")
                        mod
                    },
                    trailingContent = {
                        FilledTonalButton(onClick = { showKey = true }) {
                            Text("Reveal")
                        }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Privacy section
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
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // About section
            SectionHeader(icon = Icons.Outlined.Info, title = "About")

            ListItem(
                headlineContent = { Text("SplitFree v1.0.0") },
                supportingContent = { Text("Decentralized expense splitting over Nostr") }
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
                Text("Your private key will be copied to the clipboard. Other apps may be able to read it. Only do this to back up your key, then clear your clipboard.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyToClipboard(context, "nsec", nsec)
                        showCopyWarning = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Copy Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyWarning = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
