package com.splitfree.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Identity", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = npub,
                onValueChange = {},
                label = { Text("Public Key (npub)") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                trailingIcon = {
                    TextButton(onClick = {
                        copyToClipboard(context, "npub", npub)
                    }) { Text("Copy") }
                }
            )

            Text("Key Backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your private key is your identity. If you lose it, you lose access to all your groups. Back it up now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            if (showKey) {
                OutlinedTextField(
                    value = nsec,
                    onValueChange = {},
                    label = { Text("Private Key (nsec) — KEEP SECRET") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            showCopyWarning = true
                        }) { Text("Copy") }
                    }
                )
                TextButton(onClick = { showKey = false }) {
                    Text("Hide Key")
                }
            } else {
                Button(
                    onClick = { showKey = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show Private Key")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("NIP-59 Gift Wrap")
                    Text(
                        "Hide your identity from relay operators",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = giftWrapEnabled,
                    onCheckedChange = {
                        giftWrapEnabled = it
                        viewModel.giftWrapEnabled = it
                    }
                )
            }
        }
    }

    if (showCopyWarning) {
        AlertDialog(
            onDismissRequest = { showCopyWarning = false },
            title = { Text("Security Warning") },
            text = {
                Text("Your private key will be copied to the clipboard. Other apps may be able to read it. Only do this to back up your key, then clear your clipboard.")
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, "nsec", nsec)
                    showCopyWarning = false
                }) { Text("Copy Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyWarning = false }) { Text("Cancel") }
            }
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
