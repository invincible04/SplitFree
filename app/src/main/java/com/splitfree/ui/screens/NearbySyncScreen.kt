package com.splitfree.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.ui.viewmodels.NearbySyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySyncScreen(
    onBack: () -> Unit,
    viewModel: NearbySyncViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby Sync") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopScan()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.status.isNotBlank()) {
                Text(uiState.status, style = MaterialTheme.typography.bodyMedium)
            }

            if (!uiState.scanning) {
                Button(onClick = { viewModel.startScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan for nearby members")
                }
            } else {
                OutlinedButton(onClick = { viewModel.stopScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop scanning")
                }
                if (uiState.syncing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (uiState.peers.isNotEmpty()) {
                Text("Found ${uiState.peers.size} peer(s)", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.peers) { peer ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Peer ${peer.name}")
                                Button(
                                    onClick = { viewModel.connectToPeer(peer.endpointId) },
                                    enabled = !uiState.syncing
                                ) { Text("Sync") }
                            }
                        }
                    }
                }
            } else if (uiState.scanning) {
                Text(
                    "Looking for nearby SplitFree users…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
