package com.splitfree.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.splitfree.R
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens

/** Live health check status for a relay, driven by [RelayHealthMonitor][com.splitfree.data.nostr.relay.RelayHealthMonitor]. */
enum class RelayCheckStatus { IDLE, CHECKING, ONLINE, OFFLINE, VERIFYING, REJECTED }

/**
 * NIP-11 capability info for a relay, extracted after a successful health check.
 *
 * @property paid `true` if the relay requires payment (`limitation.payment_required`)
 * @property supportsGiftWrap `true` if `59` is in `supported_nips` (NIP-59 gift wrap).
 *   Note: most relays accept kind 1059 events without explicitly advertising NIP-59.
 */
data class RelayInfo(val paid: Boolean = false, val supportsGiftWrap: Boolean = false, val latencyMs: Long = 0)

/**
 * Reusable relay list editor with add/remove and per-relay live status indicators.
 *
 * Each relay row shows:
 * - A colored dot: ✅ green (online), ❌ red (offline), ⏳ spinner (checking), ⚪ grey (idle)
 * - The relay hostname (stripped of `wss://` prefix)
 * - NIP-11 tags when online: `💰 Paid` (red) and/or `🎁 NIP-59` if advertised
 * - A remove button (when [editable] and more than one relay remains)
 *
 * The add field validates `wss://` prefix, minimum length, and deduplication.
 *
 * @param relays current relay URLs (full `wss://` format)
 * @param relayStatuses map of relay URL → live check status
 * @param relayInfo map of relay URL → NIP-11 capability info
 * @param onAdd called when user adds a new relay URL
 * @param onRemove called when user removes a relay URL
 * @param onCheck called to trigger a health check for a relay URL
 * @param editable whether add/remove controls are shown (false for non-creator members)
 */
@Composable
fun RelayEditor(
    relays: List<String>,
    relayStatuses: Map<String, RelayCheckStatus> = emptyMap(),
    relayInfo: Map<String, RelayInfo> = emptyMap(),
    onAdd: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onCheck: (String) -> Unit = {},
    editable: Boolean = true
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    Column(verticalArrangement = Arrangement.spacedBy(tokens.denseSpacing)) {
        relays.forEach { url ->
            RelayRow(
                url = url,
                status = relayStatuses[url] ?: RelayCheckStatus.IDLE,
                info = relayInfo[url],
                onRemove = if (editable && relays.size > 1) ({ onRemove(url) }) else null
            )
        }

        if (editable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = tokens.denseSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(stringResource(R.string.relay_placeholder), style = MaterialTheme.typography.bodySmall)
                    },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(Modifier.width(tokens.itemSpacing))
                val errMustWss = stringResource(R.string.relay_must_start_wss)
                val errTooShort = stringResource(R.string.relay_url_too_short)
                val errDuplicate = stringResource(R.string.relay_already_added)
                TextButton(onClick = {
                    val url = input.trim().lowercase()
                    when {
                        !url.startsWith("wss://") -> error = errMustWss
                        url.length < 10 -> error = errTooShort
                        url in relays -> error = errDuplicate
                        else -> {
                            onAdd(url)
                            onCheck(url)
                            input = ""
                        }
                    }
                }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_relay),
                        modifier = Modifier.size(tokens.iconSmall)
                    )
                    Spacer(Modifier.width(tokens.chipContentSpacing))
                    Text(
                        text = stringResource(R.string.add),
                        style =
                        if (adaptive.isCompact) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        }
                    )
                }
            }
        }
    }
}

/** Single relay row with status dot, hostname, NIP-11 tags, and optional remove button. */
@Composable
private fun RelayRow(url: String, status: RelayCheckStatus, info: RelayInfo?, onRemove: (() -> Unit)?) {
    val tokens = adaptiveSizeTokens()

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.cardPadding, vertical = tokens.itemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(status)
            Spacer(Modifier.width(tokens.itemSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(url.removePrefix("wss://"), style = MaterialTheme.typography.bodyMedium)
                if (status == RelayCheckStatus.VERIFYING) {
                    Text(
                        stringResource(R.string.relay_verifying),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (status == RelayCheckStatus.REJECTED) {
                    Text(
                        stringResource(R.string.relay_rejected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (info != null && status == RelayCheckStatus.ONLINE) {
                    val tags = buildList {
                        if (info.paid) add("💰 Paid")
                        if (info.supportsGiftWrap) add("🎁 NIP-59")
                        if (info.latencyMs > 0) add("${info.latencyMs}ms")
                    }
                    if (tags.isNotEmpty()) {
                        Text(
                            tags.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (info.paid) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(tokens.relayRemoveButtonSize)) {
                    Icon(
                        Icons.Default.Close,
                        stringResource(R.string.relay_remove),
                        modifier = Modifier.size(tokens.iconSmall)
                    )
                }
            }
        }
    }
}

/** Animated status dot: green (online), red (offline/rejected), grey (idle), or a small spinner (checking/verifying). */
@Composable
private fun StatusDot(status: RelayCheckStatus) {
    val tokens = adaptiveSizeTokens()

    when (status) {
        RelayCheckStatus.CHECKING, RelayCheckStatus.VERIFYING -> CircularProgressIndicator(
            modifier = Modifier.size(tokens.relayStatusDotSize),
            strokeWidth = 1.5.dp
        )
        else -> {
            val color by animateColorAsState(
                when (status) {
                    RelayCheckStatus.ONLINE -> Color(0xFF4CAF50)
                    RelayCheckStatus.OFFLINE, RelayCheckStatus.REJECTED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "statusColor"
            )
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = color,
                modifier = Modifier.size(tokens.relayStatusDotSize),
                content = {}
            )
        }
    }
}
