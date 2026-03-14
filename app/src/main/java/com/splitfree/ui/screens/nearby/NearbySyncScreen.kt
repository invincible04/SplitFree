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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.NearbySyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySyncScreen(onBack: () -> Unit, viewModel: NearbySyncViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
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
    val horizontalPadding = tokens.screenPaddingHorizontal
    val sectionSpacing = tokens.sectionSpacing
    val buttonHeight = tokens.buttonHeight
    val cardTextStyle = if (adaptive.isCompact) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }

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
                title = { Text(stringResource(R.string.nearby_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopScan()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
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
                modifier = Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = tokens.nearbyTopSectionVerticalPadding
                ).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)
            ) {
                // Explanation card
                Card(
                    colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(tokens.cardPadding),
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = stringResource(R.string.cd_bluetooth_icon),
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(tokens.iconMedium)
                        )
                        Text(
                            stringResource(R.string.nearby_explanation),
                            style = cardTextStyle,
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
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        enabled = bluetoothSupported,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(tokens.iconMedium)
                        )
                        Spacer(Modifier.width(tokens.itemSpacing))
                        Text(
                            when {
                                !bluetoothSupported -> stringResource(R.string.bluetooth_unavailable)
                                !permissionsGranted -> stringResource(R.string.permissions_required)
                                !bluetoothEnabled -> stringResource(R.string.enable_bluetooth)
                                else -> stringResource(R.string.scan_for_nearby)
                            },
                            style = if (adaptive.isCompact) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!permissionsGranted) {
                        Text(
                            stringResource(R.string.permissions_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.stopScan() },
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(stringResource(R.string.stop_scanning))
                    }
                }
            }

            // Peers list
            if (uiState.peers.isNotEmpty()) {
                Text(
                    if (uiState.peers.size !=
                        1
                    ) {
                        stringResource(R.string.found_peers_plural, uiState.peers.size)
                    } else {
                        stringResource(R.string.found_peers, uiState.peers.size)
                    },
                    style = if (adaptive.isCompact) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(
                        horizontal = horizontalPadding,
                        vertical = tokens.nearbyPeersHeaderVerticalPadding
                    )
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
                ) {
                    items(uiState.peers) { peer ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(tokens.cardPadding).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.peer_name, peer.name),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.tap_sync),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { viewModel.connectToPeer(peer.endpointId) },
                                    enabled = !uiState.syncing,
                                    modifier = Modifier.heightIn(min = tokens.inlineButtonMinHeight)
                                ) {
                                    Icon(
                                        Icons.Outlined.SyncAlt,
                                        contentDescription = stringResource(R.string.cd_sync_icon),
                                        modifier = Modifier.size(tokens.iconSmall)
                                    )
                                    Spacer(Modifier.width(tokens.denseSpacing))
                                    Text(stringResource(R.string.sync), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            } else if (uiState.scanning) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(tokens.emptyStatePadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(tokens.nearbyProgressIndicatorSize),
                            strokeWidth = tokens.nearbyProgressStrokeWidth
                        )
                        Spacer(Modifier.height(tokens.sectionSpacing))
                        Text(
                            stringResource(R.string.looking_for_nearby),
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
