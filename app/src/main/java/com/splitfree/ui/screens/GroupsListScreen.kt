package com.splitfree.ui.screens

import androidx.compose.animation.animateColorAsState
import com.splitfree.util.DebugLog as Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.domain.model.Group
import com.splitfree.ui.viewmodels.GroupsListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    onGroupClick: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onSettings: () -> Unit,
    onScanResult: (String) -> Unit = {},
    viewModel: GroupsListViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SplitFree")
                        Spacer(Modifier.width(8.dp))
                        ConnectionDot(isConnected)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                            .build()
                        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let {
                                    Log.i("GroupsListScreen", "QR scanned: ${it.take(60)}...")
                                    onScanResult(it)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.w("GroupsListScreen", "QR scan failed: ${e.message}")
                            }
                    }) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan QR")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateGroup,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New Group") },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyGroupsState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupCard(group = group, onClick = { onGroupClick(group.id) })
                }
                // Bottom spacer for FAB
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyGroupsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Group,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No groups yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Create a group to start splitting expenses\nwith friends, family, or roommates.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GroupCard(
    group: Group,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Avatar with first letter
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = group.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${group.members.size} member${if (group.members.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ConnectionDot(connected: Boolean) {
    val color by animateColorAsState(
        targetValue = if (connected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
        animationSpec = tween(600),
        label = "dot",
    )
    val alpha by if (!connected) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "alpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}
