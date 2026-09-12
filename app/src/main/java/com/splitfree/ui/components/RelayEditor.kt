package com.splitfree.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.splitfree.R

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

private val RelayRowMinHeight = 56.dp
private val RelaySpinnerSize = 12.dp
private const val RELAY_MIN_URL_LENGTH = 10

/**
 * Relay list editor: one [SfListCard] of rows, each with the shared [StatusDot] (or a small ring while
 * checking), the hostname in `titleSmall`, and a `bodySmall` status line that says the state in words
 * (connected, offline, not checked, verifying, rejected) plus the NIP-11 tags (paid relay, NIP-59, latency)
 * once a relay is online. The remove control is a full 48dp [SfIconButton]. When [editable], an add field
 * validates the `wss://` prefix, a minimum length and duplicates.
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
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (relays.isNotEmpty()) {
            SfListCard {
                relays.forEachIndexed { index, url ->
                    if (index > 0) SfDivider()
                    RelayRow(
                        url = url,
                        status = relayStatuses[url] ?: RelayCheckStatus.IDLE,
                        info = relayInfo[url],
                        onRemove = if (editable && relays.size > 1) ({ onRemove(url) }) else null
                    )
                }
            }
        }

        if (editable) {
            val submit = {
                val url = input.trim().lowercase()
                when {
                    !url.startsWith("wss://") -> error = R.string.relay_must_start_wss
                    url.length < RELAY_MIN_URL_LENGTH -> error = R.string.relay_url_too_short
                    url in relays -> error = R.string.relay_already_added
                    else -> {
                        onAdd(url)
                        onCheck(url)
                        input = ""
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            stringResource(R.string.relay_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(Modifier.width(8.dp))
                // Top-aligned with the field so a supporting error line does not push the button down.
                SfTextButton(
                    text = stringResource(R.string.add),
                    onClick = submit,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** One relay: status indicator, hostname, a status line in words, and an optional 48dp remove button. */
@Composable
private fun RelayRow(url: String, status: RelayCheckStatus, info: RelayInfo?, onRemove: (() -> Unit)?) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = RelayRowMinHeight)
            .padding(start = 14.dp, end = if (onRemove != null) 6.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RelayStatusIndicator(status)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                url.removePrefix("wss://"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val (line, tone) = relayStatusLine(status, info)
            Text(line, style = MaterialTheme.typography.bodySmall, color = tone)
        }
        if (onRemove != null) {
            SfIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.relay_remove),
                onClick = onRemove
            )
        }
    }
}

/** Status in words: the dot only reinforces it. Online relays list their NIP-11 tags instead. */
@Composable
private fun relayStatusLine(status: RelayCheckStatus, info: RelayInfo?): Pair<String, Color> {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    return when (status) {
        RelayCheckStatus.ONLINE -> {
            val tags = buildList {
                if (info?.paid == true) add(stringResource(R.string.relay_tag_paid))
                if (info?.supportsGiftWrap == true) add(stringResource(R.string.relay_tag_gift_wrap))
                if (info != null && info.latencyMs > 0) add(stringResource(R.string.relay_tag_latency, info.latencyMs))
            }
            (tags.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: stringResource(R.string.relay_status_online)) to
                muted
        }
        RelayCheckStatus.OFFLINE -> stringResource(R.string.relay_status_offline) to error
        RelayCheckStatus.REJECTED -> stringResource(R.string.relay_rejected) to error
        RelayCheckStatus.VERIFYING -> stringResource(R.string.relay_verifying) to muted
        RelayCheckStatus.CHECKING -> stringResource(R.string.relay_status_checking) to muted
        RelayCheckStatus.IDLE -> stringResource(R.string.relay_status_idle) to muted
    }
}

/** Shared [StatusDot] for settled states; a 12dp ring while a check or verification is in flight. */
@Composable
private fun RelayStatusIndicator(status: RelayCheckStatus) {
    val description = stringResource(
        when (status) {
            RelayCheckStatus.ONLINE -> R.string.cd_status_connected
            RelayCheckStatus.OFFLINE, RelayCheckStatus.REJECTED -> R.string.cd_status_disconnected
            RelayCheckStatus.CHECKING, RelayCheckStatus.VERIFYING -> R.string.cd_status_connecting
            RelayCheckStatus.IDLE -> R.string.cd_status_unchecked
        }
    )
    when (status) {
        RelayCheckStatus.CHECKING, RelayCheckStatus.VERIFYING ->
            CircularProgressIndicator(
                modifier =
                Modifier.size(RelaySpinnerSize).semantics {
                    contentDescription = description
                    role = Role.Image
                },
                strokeWidth = 1.5.dp
            )
        else -> StatusDot(connected = status == RelayCheckStatus.ONLINE, contentDescription = description)
    }
}
