package com.splitfree.ui.screens.group

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.model.group.Group
import com.splitfree.ui.util.AdaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.GroupsListViewModel
import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.launch

private const val TAG = "GroupsListScreen"

/**
 * Main screen showing all expense groups the user belongs to.
 *
 * Top bar actions: paste invite link, scan QR code, settings.
 * FAB creates a new group. Empty state shown when no groups exist.
 *
 * @param onGroupClick navigates to group detail
 * @param onCreateGroup navigates to group creation
 * @param onSettings navigates to settings
 * @param onScanResult callback for scanned/pasted invite links
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    onGroupClick: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onSettings: () -> Unit,
    onScanResult: (String) -> Unit = {},
    viewModel: GroupsListViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listHorizontalPadding = tokens.screenPaddingHorizontal
    val listVerticalPadding = tokens.itemSpacing

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(tokens.itemSpacing))
                        ConnectionDot(isConnected)
                    }
                },
                actions = {
                    PasteInviteButton { scope.launch { extractInviteLink(clipboard)?.let(onScanResult) } }
                    ScanQrButton(context, onScanResult)
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateGroup,
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_group)) },
                text = { Text(stringResource(R.string.new_group)) }
            )
        }
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyGroupsState(
                modifier = Modifier.fillMaxSize().padding(padding),
                adaptive = adaptive
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = listHorizontalPadding, vertical = listVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing)
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupCard(group = group, onClick = { onGroupClick(group.id) })
                }
                item { Spacer(Modifier.height(tokens.listBottomSpacer)) }
            }
        }
    }
}

// --- Top bar action helpers ---

/**
 * Reads the clipboard and extracts a SplitFree invite link if present.
 * Handles `splitfree://join` scheme even when the link is embedded in a larger message.
 *
 * @return the invite link, or null if clipboard doesn't contain one
 */
private suspend fun extractInviteLink(clipboard: androidx.compose.ui.platform.Clipboard): String? {
    val text = clipboard.getClipEntry()
        ?.clipData
        ?.getItemAt(0)
        ?.text
        ?.toString()
        ?.trim()
        ?: return null
    val link = text.lines().firstOrNull { it.trimStart().startsWith("splitfree://join") }?.trim()
    // The link is a bearer credential (it carries the group key) — never log its payload.
    if (link != null) Log.i(TAG, "Pasted invite link from clipboard")
    return link
}

/** Clipboard paste button — reads invite link from clipboard and triggers join flow. */
@Composable
private fun PasteInviteButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.paste_invite_link))
    }
}

/** QR scanner button — launches ML Kit barcode scanner for invite QR codes. */
@Composable
private fun ScanQrButton(context: android.content.Context, onScanResult: (String) -> Unit) {
    IconButton(onClick = {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let {
                    Log.i(TAG, "QR scanned (${it.length} chars)")
                    onScanResult(it)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "QR scan failed: ${e.message}")
            }
    }) {
        Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr))
    }
}

// --- List content ---

/** Placeholder shown when the user has no groups yet. */
@Composable
private fun EmptyGroupsState(modifier: Modifier = Modifier, adaptive: AdaptiveLayoutInfo) {
    val tokens = adaptiveSizeTokens()
    val contentPadding = tokens.emptyStatePadding
    val iconSize = tokens.emptyStateIcon
    val titleStyle = if (adaptive.isCompact) {
        MaterialTheme.typography.titleSmall
    } else {
        MaterialTheme.typography.titleMedium
    }
    val bodyStyle = if (adaptive.isCompact) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }

    Column(
        modifier = modifier.padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Group,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(Modifier.height(tokens.fieldSpacing))
        Text(
            stringResource(R.string.no_groups_yet),
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(tokens.denseSpacing))
        Text(
            stringResource(R.string.no_groups_body),
            style = bodyStyle,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

/** Single group row with avatar, name, and member count. */
@Composable
private fun GroupCard(group: Group, onClick: () -> Unit) {
    val tokens = adaptiveSizeTokens()
    val cardPadding = tokens.cardPadding
    val avatarSize = tokens.avatarSize
    val rowSpacing = tokens.fieldSpacing

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rowSpacing)
        ) {
            Box(
                modifier = Modifier.size(avatarSize).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatarInitial(group.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(tokens.denseSpacing))
                Text(
                    text = pluralStringResource(R.plurals.member_count, group.members.size, group.members.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(tokens.iconMedium)
            )
        }
    }
}

/** Animated dot indicating relay connection status (green = connected, grey pulsing = disconnected). */
@Composable
private fun ConnectionDot(connected: Boolean) {
    val tokens = adaptiveSizeTokens()
    val statusText = stringResource(
        if (connected) R.string.cd_status_connected else R.string.cd_status_disconnected
    )
    val color by animateColorAsState(
        targetValue = if (connected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
        animationSpec = tween(600),
        label = "dot"
    )
    val alpha by if (!connected) {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "alpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Box(
        modifier = Modifier.size(tokens.iconTiny).clip(CircleShape)
            .background(color.copy(alpha = alpha))
            .semantics {
                contentDescription = statusText
                role = Role.Image
            }
    )
}

/**
 * First user-perceived character of [name] for an avatar, upper-cased. Uses the first code point so a
 * leading emoji or other supplementary-plane character is not split into a lone surrogate.
 */
internal fun avatarInitial(name: String): String {
    if (name.isEmpty()) return ""
    val codePoint = name.codePointAt(0)
    return String(Character.toChars(codePoint)).uppercase()
}
