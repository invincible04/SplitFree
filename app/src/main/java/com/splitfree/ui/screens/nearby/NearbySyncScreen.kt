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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MemberAvatar
import com.splitfree.ui.components.SfAccentButton
import com.splitfree.ui.components.SfBottomDock
import com.splitfree.ui.components.SfCard
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.NearbySyncUiState
import com.splitfree.ui.viewmodels.NearbySyncViewModel

/**
 * What the platform lets the screen do right now; checked by the route, rendered by the content.
 *
 * @property granted every runtime permission in [requiredNearbyPermissions] is granted.
 * @property bluetoothEnabled the adapter exists and is switched on.
 * @property bluetoothAvailable the device has a Bluetooth adapter at all.
 */
data class NearbyPermissionState(
    val granted: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val bluetoothAvailable: Boolean = true
)

/**
 * Everything the nearby screen can ask its host to do. [startScan] is the dock button's single intent:
 * the route decides whether that means requesting permissions, turning Bluetooth on or actually scanning.
 * Defaults are no-ops so tests can pass only what they observe.
 */
data class NearbyActions(
    val back: () -> Unit = {},
    val startScan: () -> Unit = {},
    val stopScan: () -> Unit = {},
    val connectToPeer: (String) -> Unit = {}
)

/**
 * Nearby-sync route: owns permission and Bluetooth-enable launchers, re-checks both on resume, stops the
 * scan when the screen leaves and renders [NearbySyncContent] from [NearbySyncViewModel.uiState].
 */
@Composable
fun NearbySyncScreen(onBack: () -> Unit, viewModel: NearbySyncViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requiredPermissions = remember { requiredNearbyPermissions() }
    var permissionsGranted by remember { mutableStateOf(hasAllPermissions(context, requiredPermissions)) }
    val btAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    var bluetoothEnabled by remember { mutableStateOf(isBluetoothEnabled(context, btAdapter)) }
    var pendingScanAfterEnable by remember { mutableStateOf(false) }

    val btEnableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
        if (pendingScanAfterEnable && permissionsGranted && bluetoothEnabled) {
            viewModel.startScan()
        }
        pendingScanAfterEnable = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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

    NearbySyncContent(
        state = uiState,
        permissions =
        NearbyPermissionState(
            granted = permissionsGranted,
            bluetoothEnabled = bluetoothEnabled,
            bluetoothAvailable = btAdapter != null
        ),
        actions =
        NearbyActions(
            back = {
                viewModel.stopScan()
                onBack()
            },
            startScan = {
                when {
                    !permissionsGranted -> {
                        pendingScanAfterEnable = true
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    }

                    !bluetoothEnabled -> {
                        pendingScanAfterEnable = true
                        btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }

                    else -> {
                        pendingScanAfterEnable = false
                        viewModel.startScan()
                    }
                }
            },
            stopScan = viewModel::stopScan,
            connectToPeer = viewModel::connectToPeer
        )
    )
}

private val OrbitSize = 185.dp
private val MiddleRingSize = 125.dp
private val InnerRingSize = 65.dp
private val RadarTileSize = 50.dp
private val RadarTileShape = RoundedCornerShape(17.dp)
private val ExplanationMaxWidth = 300.dp
private val DockButtonHeight = 56.dp
private const val RADAR_PERIOD_MS = 1700
private const val RADAR_STAGGER_MS = 450
private const val RING_START_SCALE = 0.6f
private const val RING_END_SCALE = 1.45f
private const val PULSE_MAX_ALPHA = 0.7f
private const val PULSE_STROKE_FACTOR = 1.5f

/** Status resources the ViewModel uses for failures; everything else is progress or guidance. */
private val FailureStatusIds =
    setOf(
        R.string.nearby_peer_data_failed,
        R.string.nearby_peer_auth_failed,
        R.string.nearby_handshake_timeout,
        R.string.nearby_handshake_check_failed,
        R.string.nearby_group_exchange_failed,
        R.string.nearby_sync_request_failed,
        R.string.nearby_ble_error,
        R.string.nearby_scan_failed
    )

/**
 * Stateless nearby-sync layout: top bar, a thin progress line while syncing, the
 * radar orbit (rings pulse only while scanning), a state-dependent headline over the explanation, the
 * ViewModel's status as a hint or warning, permission problems as warnings, one card per peer and the dock
 * with the single primary action.
 */
@Composable
internal fun NearbySyncContent(
    state: NearbySyncUiState,
    permissions: NearbyPermissionState,
    actions: NearbyActions,
    modifier: Modifier = Modifier
) {
    val tokens = adaptiveSizeTokens()
    val horizontal = tokens.screenPaddingHorizontal

    Scaffold(
        modifier = modifier,
        topBar = {
            SfTopBar(
                title = stringResource(R.string.nearby_sync_title),
                onBack = actions.back,
                backModifier = Modifier.testTag("nearby_back")
            )
        },
        bottomBar = { NearbyDock(state = state, permissions = permissions, actions = actions) }
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(bottom = padding.calculateBottomPadding() + 12.dp)
                .testTag("nearby_scroll")
        ) {
            if (state.syncing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testTag("nearby_progress"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontal)) {
                NearbyOrbit(
                    scanning = state.scanning,
                    syncing = state.syncing,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 28.dp, bottom = 19.dp)
                )
                Text(
                    nearbyHeadline(state),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        }
                        .testTag("nearby_headline")
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.nearby_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = ExplanationMaxWidth).align(Alignment.CenterHorizontally)
                )

                visibleStatus(state)?.let { status ->
                    Spacer(Modifier.height(20.dp))
                    StatusNotice(status)
                }
                PermissionNotice(permissions)

                if (state.peers.isNotEmpty()) {
                    Spacer(Modifier.height(23.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.peers.forEach { peer ->
                            key(peer.endpointId) {
                                PeerCard(peer = peer, syncing = state.syncing, onSync = actions.connectToPeer)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Headline by state; syncing wins over a found peer, which wins over plain scanning. */
@Composable
private fun nearbyHeadline(state: NearbySyncUiState): String = when {
    state.syncing -> stringResource(R.string.nearby_headline_syncing)
    state.peers.isNotEmpty() ->
        pluralStringResource(R.plurals.nearby_headline_found, state.peers.size, state.peers.size)
    state.scanning -> stringResource(R.string.nearby_headline_scanning)
    else -> stringResource(R.string.nearby_headline_idle)
}

/**
 * The status to show: everything the ViewModel says, except the "ask the other person to open Nearby sync"
 * scanning hint once someone has actually been found, when the headline and peer card already say what to do.
 */
private fun visibleStatus(state: NearbySyncUiState): UiMessage? {
    val status = state.status ?: return null
    val isScanningHint = status is UiMessage.Res && status.id == R.string.nearby_scanning
    return if (isScanningHint && state.peers.isNotEmpty()) null else status
}

/** The ViewModel's status line: a [WarningCard] when the resource is one of its failure messages. */
@Composable
private fun StatusNotice(status: UiMessage) {
    val isFailure = status is UiMessage.Res && status.id in FailureStatusIds
    val text = status.asString()
    val statusModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (isFailure) {
        WarningCard(text = text, modifier = statusModifier.testTag("nearby_status_warning"))
    } else {
        HintCard(text = text, modifier = statusModifier.testTag("nearby_status_hint"))
    }
}

/** Missing hardware or permissions, stated in words under the copy. */
@Composable
private fun PermissionNotice(permissions: NearbyPermissionState) {
    val text =
        when {
            !permissions.bluetoothAvailable -> stringResource(R.string.nearby_bluetooth_unavailable_hint)
            !permissions.granted -> stringResource(R.string.permissions_hint)
            else -> return
        }
    Spacer(Modifier.height(14.dp))
    WarningCard(text = text, modifier = Modifier.testTag("nearby_permission_warning"))
}

/**
 * Radar: three concentric hairline circles (185 / 125 / 65dp) around a 50dp `primary`
 * tile. While [scanning] the two inner rings expand from 0.6× to 1.45× and fade, 1.7 s apart with a 450 ms
 * stagger; when not scanning they are static and no animation exists at all. The tile shows the Bluetooth
 * mark, or the sync arrows while [syncing].
 */
@Composable
private fun NearbyOrbit(scanning: Boolean, syncing: Boolean, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val pulse = MaterialTheme.colorScheme.primary
    val middleProgress: Float?
    val innerProgress: Float?
    if (scanning) {
        val radar = rememberInfiniteTransition(label = "nearbyRadar")
        middleProgress =
            radar.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                infiniteRepeatable(tween(RADAR_PERIOD_MS, easing = SfMotion.Ease), RepeatMode.Restart),
                label = "middleRing"
            ).value
        innerProgress =
            radar.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                infiniteRepeatable(
                    tween(RADAR_PERIOD_MS, easing = SfMotion.Ease),
                    RepeatMode.Restart,
                    initialStartOffset = StartOffset(RADAR_STAGGER_MS)
                ),
                label = "innerRing"
            ).value
    } else {
        middleProgress = null
        innerProgress = null
    }

    Box(
        modifier =
        modifier
            .size(OrbitSize)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawCircle(color = line, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                drawRing(line, pulse, MiddleRingSize.toPx(), middleProgress, stroke)
                drawRing(line, pulse, InnerRingSize.toPx(), innerProgress, stroke)
            }
            .testTag(if (scanning) "nearby_orbit_scanning" else "nearby_orbit_idle"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(RadarTileSize).background(MaterialTheme.colorScheme.primary, RadarTileShape),
            contentAlignment = Alignment.Center
        ) {
            if (syncing) {
                Icon(
                    Icons.Outlined.SyncAlt,
                    contentDescription = stringResource(R.string.cd_sync_icon),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    Icons.Outlined.Bluetooth,
                    contentDescription = stringResource(R.string.cd_bluetooth_icon),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * A ring at rest ([progress] null: hairline in [restColor]) or mid-pulse: drawn in [pulseColor], scaled between
 * 0.6× and 1.45× and faded out as it grows, so a scanning radar reads differently from an idle one.
 */
private fun DrawScope.drawRing(restColor: Color, pulseColor: Color, diameter: Float, progress: Float?, stroke: Float) {
    if (progress == null) {
        drawCircle(color = restColor, radius = diameter / 2 - stroke / 2, style = Stroke(stroke))
        return
    }
    val scale = RING_START_SCALE + (RING_END_SCALE - RING_START_SCALE) * progress
    drawCircle(
        color = pulseColor.copy(alpha = PULSE_MAX_ALPHA * (1f - progress)),
        radius = diameter * scale / 2 - stroke,
        style = Stroke(stroke * PULSE_STROKE_FACTOR)
    )
}

/** One discovered peer: avatar, name, instruction and the Sync button. */
@Composable
private fun PeerCard(peer: NearbyPeer, syncing: Boolean, onSync: (String) -> Unit) {
    SfCard(modifier = Modifier.fillMaxWidth().testTag("nearby_peer_${peer.endpointId}")) {
        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(pubkey = peer.endpointId, name = peer.name, size = 40.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.peer_name, peer.name),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.tap_sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(11.dp))
            SfSecondaryButton(
                text = stringResource(R.string.sync),
                onClick = { onSync(peer.endpointId) },
                enabled = !syncing,
                modifier = Modifier.testTag("nearby_sync_${peer.endpointId}")
            )
        }
    }
}

/** Dock: the one primary action, labelled by what is missing, over a faint reminder. */
@Composable
private fun NearbyDock(state: NearbySyncUiState, permissions: NearbyPermissionState, actions: NearbyActions) {
    SfBottomDock {
        if (state.scanning) {
            SfSecondaryButton(
                text = stringResource(R.string.stop_scanning),
                onClick = actions.stopScan,
                modifier = Modifier.fillMaxWidth().heightIn(min = DockButtonHeight).testTag("nearby_primary")
            )
        } else {
            val label =
                when {
                    !permissions.bluetoothAvailable -> R.string.bluetooth_unavailable
                    !permissions.granted -> R.string.permissions_required
                    !permissions.bluetoothEnabled -> R.string.enable_bluetooth
                    else -> R.string.scan_for_nearby
                }
            SfAccentButton(
                text = stringResource(label),
                onClick = actions.startScan,
                enabled = permissions.bluetoothAvailable,
                modifier = Modifier.testTag("nearby_primary")
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.nearby_footer_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.splitFree.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// --- Route helpers: permissions and Bluetooth -----------------------------------------------------------

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
