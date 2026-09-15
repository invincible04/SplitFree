package com.splitfree.ui.screens.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.sync.nearby.RunPhase
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MemberAvatar
import com.splitfree.ui.components.SectionHead
import com.splitfree.ui.components.SfAccentButton
import com.splitfree.ui.components.SfBottomDock
import com.splitfree.ui.components.SfCard
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.Headline
import com.splitfree.ui.viewmodels.NearbyNotice
import com.splitfree.ui.viewmodels.NearbyPeerRow
import com.splitfree.ui.viewmodels.NearbySyncUiState
import com.splitfree.ui.viewmodels.NearbySyncViewModel
import com.splitfree.ui.viewmodels.NoticeAction
import com.splitfree.ui.viewmodels.RowAction

/**
 * Route-level permission and Bluetooth checks, not a guarantee that Nearby services can start.
 *
 * @property granted every runtime permission in [requiredNearbyPermissions] is granted.
 * @property needsSettings settings recovery selected after repeated denial without a permission rationale.
 * @property bluetoothEnabled the adapter is on and the app has permission to read its state.
 * @property bluetoothAvailable the device has a Bluetooth adapter at all.
 */
data class NearbyPermissionState(
    val granted: Boolean = true,
    val needsSettings: Boolean = false,
    val bluetoothEnabled: Boolean = true,
    val bluetoothAvailable: Boolean = true
) {
    /** Passes the route's startup gate; service and location-setting failures are reported by the controller. */
    val satisfied: Boolean
        get() = granted && bluetoothEnabled && bluetoothAvailable
}

/**
 * Everything the nearby screen can ask its host to do.
 *
 * - Defaults are no-ops so tests can pass only what they observe.
 */
data class NearbyActions(
    val back: () -> Unit = {},
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val connect: (String) -> Unit = {},
    val cancelAttempt: (String) -> Unit = {},
    val requestPermissions: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val enableBluetooth: () -> Unit = {},
    val openLocationSettings: () -> Unit = {}
)

/**
 * Owns platform launchers and forwards the destination lifecycle to the ViewModel.
 *
 * - Missing permissions are requested automatically once per saved screen entry; later requests require a tap.
 * - Permission grants and Bluetooth state are rechecked on resume.
 * - Bluetooth enablement is requested only on a tap.
 */
@Composable
fun NearbySyncScreen(onBack: () -> Unit, viewModel: NearbySyncViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val requiredPermissions = remember { requiredNearbyPermissions() }
    var permissionsGranted by remember { mutableStateOf(hasAllPermissions(context, requiredPermissions)) }
    var needsSettings by rememberSaveable { mutableStateOf(false) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    var requestCount by rememberSaveable { mutableIntStateOf(0) }
    val btAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    var bluetoothEnabled by remember { mutableStateOf(isBluetoothEnabled(context, btAdapter)) }

    val btEnableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = hasAllPermissions(context, requiredPermissions)
        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
        if (permissionsGranted) {
            needsSettings = false
        } else if (requestCount > 1 && activity != null) {
            val denied = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) needsSettings = true
        }
    }
    val requestPermissions = {
        requestCount++
        permissionLauncher.launch(requiredPermissions.toTypedArray())
    }

    // Bluetooth broadcasts can originate outside the system UID, so this receiver must be exported.
    // It listens only for the protected state-change action and re-reads the adapter rather than trusting extras.
    DisposableEffect(context, btAdapter) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
                    }
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val permissions =
        NearbyPermissionState(
            granted = permissionsGranted,
            needsSettings = needsSettings,
            bluetoothEnabled = bluetoothEnabled,
            bluetoothAvailable = btAdapter != null
        )
    LaunchedEffect(permissions.satisfied) { viewModel.onPrerequisites(permissions.satisfied) }

    // - STARTED, not RESUMED, owns the run: a consent dialog can pause the destination without stopping it.
    // - Stop or disposal requests cleanup; a later start may request a new run if intent and prerequisites allow.
    // - Pause only suspends connection-attempt timeouts, not the protocol session's handshake/transfer watchdog.
    LifecycleStartEffect(Unit) {
        viewModel.onScreenStarted()
        onStopOrDispose { viewModel.onScreenStopped() }
    }

    LifecycleResumeEffect(Unit) {
        permissionsGranted = hasAllPermissions(context, requiredPermissions)
        bluetoothEnabled = isBluetoothEnabled(context, btAdapter)
        viewModel.setPaused(false)
        onPauseOrDispose { viewModel.setPaused(true) }
    }

    LaunchedEffect(Unit) {
        if (!autoRequested && !permissionsGranted && requiredPermissions.isNotEmpty()) {
            autoRequested = true
            requestPermissions()
        }
    }

    NearbySyncContent(
        state = uiState,
        permissions = permissions,
        actions =
        NearbyActions(
            back = {
                viewModel.leave()
                onBack()
            },
            start = viewModel::start,
            stop = viewModel::stop,
            connect = viewModel::connect,
            cancelAttempt = viewModel::cancelAttempt,
            requestPermissions = requestPermissions,
            openAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            },
            enableBluetooth = { btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            openLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
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

/**
 * Renders projected state and forwards actions without owning platform or protocol work, so previews and content tests
 * need no ViewModel.
 *
 * - Retained results are separated from the run's peer rows.
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
    val busy = state.headline == Headline.SYNCING || state.headline == Headline.CONNECTING

    Scaffold(
        modifier = modifier,
        topBar = {
            SfTopBar(
                title = stringResource(R.string.nearby_sync),
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
            if (state.progressVisible) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().testTag("nearby_progress"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = horizontal)) {
                NearbyOrbit(
                    scanning = state.searching,
                    syncing = busy,
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

                state.notice?.let { notice ->
                    Spacer(Modifier.height(20.dp))
                    StatusNotice(notice, state.phase, permissions, actions)
                }
                PermissionNotice(permissions)

                val live = state.rows.filter { !it.recent }
                if (live.isNotEmpty()) {
                    Spacer(Modifier.height(23.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        live.forEach { row -> key(row.endpointId) { PeerCard(row = row, actions = actions) } }
                    }
                }
                val recent = state.rows.filter { it.recent }
                if (recent.isNotEmpty()) {
                    SectionHead(stringResource(R.string.nearby_recent), modifier = Modifier.testTag("nearby_recent"))
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        recent.forEach { row -> key(row.endpointId) { PeerCard(row = row, actions = actions) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun nearbyHeadline(state: NearbySyncUiState): String = when (state.headline) {
    Headline.SYNCING -> stringResource(R.string.nearby_headline_syncing)
    Headline.CONNECTING -> stringResource(R.string.nearby_headline_connecting)
    Headline.FOUND -> pluralStringResource(R.plurals.nearby_headline_found, state.peerCount, state.peerCount)
    Headline.SEARCHING -> stringResource(R.string.nearby_headline_searching)
    Headline.STARTING -> stringResource(R.string.nearby_headline_starting)
    Headline.FAILED -> stringResource(R.string.nearby_headline_failed)
    Headline.IDLE -> stringResource(R.string.nearby_headline_idle)
}

/**
 * Renders run-level guidance or failure.
 *
 * - Suppresses the notice's retry action in FAILED because the dock already offers it; permission and location
 *   recovery actions remain available.
 */
@Composable
private fun StatusNotice(
    notice: NearbyNotice,
    phase: RunPhase,
    permissions: NearbyPermissionState,
    actions: NearbyActions
) {
    val text = notice.message.asString()
    val statusModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (notice.warning) {
        WarningCard(text = text, modifier = statusModifier.testTag("nearby_status_warning"))
    } else {
        HintCard(text = text, modifier = statusModifier.testTag("nearby_status_hint"))
    }
    val (label, onClick) =
        when (notice.action) {
            NoticeAction.NONE -> return
            NoticeAction.GRANT_PERMISSION ->
                if (permissions.needsSettings) {
                    R.string.nearby_open_settings to actions.openAppSettings
                } else {
                    R.string.permissions_required to actions.requestPermissions
                }
            NoticeAction.OPEN_LOCATION_SETTINGS ->
                R.string.nearby_notice_location_action to actions.openLocationSettings
            NoticeAction.RETRY ->
                if (phase == RunPhase.FAILED) return else R.string.nearby_try_again to actions.start
        }
    Spacer(Modifier.height(10.dp))
    SfSecondaryButton(
        text = stringResource(label),
        onClick = onClick,
        modifier = Modifier.testTag("nearby_notice_action")
    )
}

/** Missing hardware, permissions or Bluetooth, stated in words under the copy. */
@Composable
private fun PermissionNotice(permissions: NearbyPermissionState) {
    val text =
        when {
            !permissions.bluetoothAvailable -> stringResource(R.string.nearby_bluetooth_unavailable_hint)
            !permissions.granted && permissions.needsSettings ->
                stringResource(R.string.nearby_permission_settings_hint)
            !permissions.granted -> stringResource(R.string.permissions_hint)
            !permissions.bluetoothEnabled -> stringResource(R.string.nearby_bluetooth_off_hint)
            else -> return
        }
    Spacer(Modifier.height(14.dp))
    WarningCard(text = text, modifier = Modifier.testTag("nearby_permission_warning"))
}

/**
 * Creates the infinite transition only while [scanning], avoiding animation work for an idle radar.
 *
 * - [syncing] changes the icon independently, since discovery can continue during a connection or transfer.
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
 * Null [progress] draws a static outline; a value draws an expanding, fading pulse to distinguish searching.
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

/**
 * Renders one peer row.
 *
 * - Shows an avatar, the unverified name (or "Phone"), a status line and one button.
 * - Shows "Verified identity" after authenticating the peer's key.
 * - Group membership is checked separately, later; the caption never claims membership.
 * - Buttons carry the name in their description so a screen reader can tell rows apart.
 */
@Composable
private fun PeerCard(row: NearbyPeerRow, actions: NearbyActions) {
    val name = row.displayName.asString()
    SfCard(modifier = Modifier.fillMaxWidth().testTag("nearby_peer_${row.endpointId}")) {
        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(pubkey = row.verifiedPubkey ?: row.endpointId, name = name, size = 40.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.verifiedPubkey != null) {
                    Text(
                        stringResource(R.string.nearby_verified_identity),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("nearby_verified_${row.endpointId}")
                    )
                }
                val status =
                    row.status?.asString()
                        ?: if (row.action == RowAction.SYNC) stringResource(R.string.tap_sync) else null
                if (status != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                        Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("nearby_row_status_${row.endpointId}")
                    )
                }
            }
            when (row.action) {
                RowAction.SYNC, RowAction.RETRY -> {
                    val retry = row.action == RowAction.RETRY
                    val description =
                        stringResource(if (retry) R.string.nearby_cd_retry_with else R.string.nearby_cd_sync_with, name)
                    Spacer(Modifier.width(11.dp))
                    SfSecondaryButton(
                        text = stringResource(if (retry) R.string.nearby_retry else R.string.sync),
                        onClick = { actions.connect(row.endpointId) },
                        modifier =
                        Modifier
                            .semantics { contentDescription = description }
                            .testTag("nearby_sync_${row.endpointId}")
                    )
                }

                RowAction.CONNECTING -> {
                    val description = stringResource(R.string.nearby_cd_cancel_for, name)
                    Spacer(Modifier.width(11.dp))
                    SfSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = { actions.cancelAttempt(row.endpointId) },
                        modifier =
                        Modifier
                            .semantics { contentDescription = description }
                            .testTag("nearby_cancel_${row.endpointId}")
                    )
                }

                RowAction.BUSY, RowAction.DONE, RowAction.NONE -> Unit
            }
        }
    }
}

/**
 * Dock: the one primary action for the run state, over a faint reminder.
 *
 * - A running or starting run offers Stop; a stopping run waits; a failed run offers Try again; otherwise Start, or
 *   whichever prerequisite is missing.
 */
@Composable
private fun NearbyDock(state: NearbySyncUiState, permissions: NearbyPermissionState, actions: NearbyActions) {
    SfBottomDock {
        val tag = Modifier.testTag("nearby_primary")
        when (state.phase) {
            RunPhase.STARTING, RunPhase.ACTIVE, RunPhase.WAITING_FOR_CLEANUP ->
                SfSecondaryButton(
                    text = stringResource(R.string.nearby_stop),
                    onClick = actions.stop,
                    modifier = tag.fillMaxWidth().heightIn(min = DockButtonHeight)
                )

            RunPhase.STOPPING ->
                SfAccentButton(
                    text = stringResource(R.string.nearby_stopping),
                    onClick = {},
                    enabled = false,
                    modifier = tag
                )

            RunPhase.FAILED ->
                SfAccentButton(
                    text = stringResource(R.string.nearby_try_again),
                    onClick = actions.start,
                    modifier = tag
                )

            RunPhase.IDLE -> {
                val (label, onClick) =
                    when {
                        !state.enabled -> R.string.nearby_start to actions.start
                        !permissions.bluetoothAvailable -> R.string.bluetooth_unavailable to actions.start
                        !permissions.granted && permissions.needsSettings ->
                            R.string.nearby_open_settings to actions.openAppSettings
                        !permissions.granted -> R.string.permissions_required to actions.requestPermissions
                        !permissions.bluetoothEnabled -> R.string.enable_bluetooth to actions.enableBluetooth
                        else -> R.string.nearby_start to actions.start
                    }
                SfAccentButton(
                    text = stringResource(label),
                    onClick = onClick,
                    enabled = permissions.bluetoothAvailable || !state.enabled,
                    modifier = tag
                )
            }
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

/**
 * Permissions required by this route's startup gate for the device API level.
 *
 * - API 26–28 requests coarse location; API 29–32 requests fine and coarse together.
 * - Android 12 requires that pairing when requesting fine location.
 * - API 33+ uses Nearby Wi-Fi devices instead of location.
 * - API 37 adds local-network access for the Wi-Fi LAN path.
 * - The route requires every listed permission; it does not start a Bluetooth-only run when local-network access is
 *   denied.
 * - The permission names are compile-time string constants, so they are safe to reference on any API level; the [sdk]
 *   parameter (a test seam) is the guard lint's InlinedApi check cannot follow.
 */
@SuppressLint("InlinedApi")
internal fun requiredNearbyPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (sdk >= LOCAL_NETWORK_PERMISSION_API) add(ACCESS_LOCAL_NETWORK)
    } else if (sdk >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    } else if (sdk >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    } else {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

/** `android.permission.ACCESS_LOCAL_NETWORK`, a runtime permission from API 37 for apps targeting 37. */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_PERMISSION_API = 37

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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
