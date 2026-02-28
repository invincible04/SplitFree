package com.splitfree.ui.screens.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.ui.viewmodels.NearbySyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySyncScreen(onBack: () -> Unit, viewModel: NearbySyncViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requiredPermissions = remember { requiredNearbyPermissions() }
    var permissionsGranted by remember {
        mutableStateOf(hasAllPermissions(context, requiredPermissions))
    }
    val btAdapter = remember {
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    var bluetoothEnabled by remember {
        mutableStateOf(isBluetoothEnabled(context, btAdapter))
    }
    var pendingScanAfterEnable by remember { mutableStateOf(false) }

    val btEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
        if (pendingScanAfterEnable && permissionsGranted && bluetoothEnabled) {
            viewModel.startScan()
        }
        pendingScanAfterEnable = false
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            permissionsGranted = hasAllPermissions(context, requiredPermissions)
            bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
            if (permissionsGranted && pendingScanAfterEnable) {
                if (!bluetoothEnabled) {
                    btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    viewModel.startScan()
                    pendingScanAfterEnable = false
                }
            }
        }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    LifecycleResumeEffect(Unit) {
        permissionsGranted = hasAllPermissions(context, requiredPermissions)
        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted && requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

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
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            // Status area
            if (uiState.syncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Explanation card
                Card(
                    colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            "Sync expenses with nearby group members over Bluetooth — no internet needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                if (uiState.status.isNotBlank()) {
                    Text(
                        uiState.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!uiState.scanning) {
                    val bluetoothSupported = btAdapter != null
                    Button(
                        onClick = {
                            if (!permissionsGranted) {
                                pendingScanAfterEnable = true
                                permissionLauncher.launch(requiredPermissions.toTypedArray())
                            } else if (!bluetoothEnabled) {
                                pendingScanAfterEnable = true
                                btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            } else {
                                pendingScanAfterEnable = false
                                viewModel.startScan()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = bluetoothSupported,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                !bluetoothSupported -> "Bluetooth unavailable"
                                !permissionsGranted -> "Permissions required"
                                !bluetoothEnabled -> "Enable Bluetooth"
                                else -> "Scan for nearby members"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    if (!permissionsGranted) {
                        Text(
                            "Bluetooth permissions are required for nearby sync. Tap the button to grant access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.stopScan() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("Stop scanning")
                    }
                }
            }

            // Peers list
            if (uiState.peers.isNotEmpty()) {
                Text(
                    "Found ${uiState.peers.size} peer${if (uiState.peers.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.peers) { peer ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Peer ${peer.name}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Tap Sync to exchange expenses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.connectToPeer(peer.endpointId) },
                                    enabled = !uiState.syncing
                                ) {
                                    Icon(
                                        Icons.Outlined.SyncAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sync")
                                }
                            }
                        }
                    }
                }
            } else if (uiState.scanning) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Looking for nearby SplitFree users…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

private fun requiredNearbyPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

private fun hasAllPermissions(context: Context, permissions: List<String>): Boolean = permissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private fun isBluetoothEnabled(context: Context, adapter: BluetoothAdapter?): Boolean {
    if (adapter == null) return false
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return adapter.isEnabled
}
