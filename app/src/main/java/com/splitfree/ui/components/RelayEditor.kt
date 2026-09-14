package com.splitfree.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.util.RelayDefaults

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
 * Relay list editor. The current relays sit in one [SfListCard] of rows, each with the shared [StatusDot] (or
 * a small ring while checking), the hostname in `titleSmall`, a `bodySmall` status line in words plus the
 * NIP-11 tags once online, and a 48dp remove button when [editable] and more than one relay remains.
 *
 * When [editable], a "Reset to default relays" text action appears whenever the list differs from
 * [RelayDefaults.DEFAULT_RELAYS] as a set, and an "Add relay" [SfExpandableRow] discloses the known relays
 * not yet in the list (each tappable, with its live status) followed by a "Custom relay URL" row that reveals
 * a text field for self-hosted or other public relays. At [InviteLinkCodec.MAX_RELAYS] relays the add row
 * gives way to a one-line cap hint. Expanding the suggestions triggers [onCheck] for every suggestion not yet
 * checked, so the user sees which are up before choosing.
 *
 * @param relays current relay URLs (full `wss://` format)
 * @param relayStatuses map of relay URL → live check status
 * @param relayInfo map of relay URL → NIP-11 capability info
 * @param onAdd called when user adds a relay URL, from a suggestion or the custom field
 * @param onRemove called when user removes a relay URL
 * @param onCheck called to trigger a health check for a relay URL
 * @param onReset called when user asks for the default relays
 * @param editable whether add/remove/reset controls are shown (false for non-creator members)
 */
@Composable
fun RelayEditor(
    relays: List<String>,
    relayStatuses: Map<String, RelayCheckStatus> = emptyMap(),
    relayInfo: Map<String, RelayInfo> = emptyMap(),
    onAdd: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onCheck: (String) -> Unit = {},
    onReset: () -> Unit = {},
    editable: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (relays.isNotEmpty()) {
            SfListCard {
                relays.forEachIndexed { index, url ->
                    key(url) {
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
        }

        if (editable) {
            if (relays.toSet() != RelayDefaults.DEFAULT_RELAYS.toSet()) {
                SfTextButton(
                    text = stringResource(R.string.relay_reset_defaults),
                    onClick = onReset,
                    modifier = Modifier.testTag("relay_reset")
                )
            }
            Spacer(Modifier.height(10.dp))
            if (relays.size >= InviteLinkCodec.MAX_RELAYS) {
                val max = InviteLinkCodec.MAX_RELAYS
                Text(
                    pluralStringResource(R.plurals.relay_max_reached, max, max),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("relay_max_hint")
                )
            } else {
                AddRelaySection(
                    relays = relays,
                    relayStatuses = relayStatuses,
                    relayInfo = relayInfo,
                    onAdd = onAdd,
                    onCheck = onCheck
                )
            }
        }
    }
}

/**
 * "Add relay" [SfExpandableRow] between two hairlines, disclosing one [SfListCard] of the known relays not yet
 * in [relays] (in [RelayDefaults.KNOWN_RELAYS] order) and, last, the custom URL row. The subtitle counts the
 * suggestions and is omitted when there are none.
 */
@Composable
private fun AddRelaySection(
    relays: List<String>,
    relayStatuses: Map<String, RelayCheckStatus>,
    relayInfo: Map<String, RelayInfo>,
    onAdd: (String) -> Unit,
    onCheck: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val suggestions = RelayDefaults.KNOWN_RELAYS - relays
    val subtitle =
        if (suggestions.isEmpty()) {
            null
        } else {
            pluralStringResource(R.plurals.relay_suggestions_count, suggestions.size, suggestions.size)
        }

    LaunchedEffect(expanded, suggestions) {
        if (expanded) {
            suggestions
                .filter { (relayStatuses[it] ?: RelayCheckStatus.IDLE) == RelayCheckStatus.IDLE }
                .forEach(onCheck)
        }
    }

    SfExpandableRow(
        icon = Icons.Outlined.Add,
        title = stringResource(R.string.relay_add),
        subtitle = subtitle,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = Modifier.testTag("relay_add_toggle"),
        framed = true
    ) {
        SfListCard(modifier = Modifier.padding(top = 10.dp).testTag("relay_suggestions")) {
            suggestions.forEach { url ->
                key(url) {
                    RelaySuggestionRow(
                        url = url,
                        status = relayStatuses[url] ?: RelayCheckStatus.IDLE,
                        info = relayInfo[url],
                        onClick = {
                            onAdd(url)
                            onCheck(url)
                        }
                    )
                    SfDivider()
                }
            }
            CustomRelayRow(relays = relays, onAdd = onAdd, onCheck = onCheck)
        }
    }
}

/**
 * One known relay on offer: hostname, live status line and indicator. The whole row is a button whose click
 * label is "Add", so TalkBack reads the host and status and then offers the action.
 */
@Composable
private fun RelaySuggestionRow(url: String, status: RelayCheckStatus, info: RelayInfo?, onClick: () -> Unit) {
    val host = url.removePrefix("wss://")
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = RelayRowMinHeight)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.add), onClick = onClick)
            .testTag("relay_suggestion_$host")
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                host,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val (line, tone) = relayStatusLine(status, info)
            Text(line, style = MaterialTheme.typography.bodySmall, color = tone)
        }
        Spacer(Modifier.width(11.dp))
        RelayStatusIndicator(status)
    }
}

/**
 * "Custom relay URL" [SfExpandableRow] that reveals a URL field and an Add action. The field validates the
 * `wss://` prefix, a minimum length, duplicates (after lowercasing and dropping a trailing slash) and the
 * invite-link budget ([InviteLinkCodec.fitsInviteLink]) before calling [onAdd] and [onCheck].
 */
@Composable
private fun CustomRelayRow(relays: List<String>, onAdd: (String) -> Unit, onCheck: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    val submit = {
        val url = input.trim().lowercase().trimEnd('/')
        when {
            !url.startsWith("wss://") -> error = R.string.relay_must_start_wss
            url.length < RELAY_MIN_URL_LENGTH -> error = R.string.relay_url_too_short
            url in relays -> error = R.string.relay_already_added
            !InviteLinkCodec.fitsInviteLink(relays + url) -> error = R.string.relay_invite_too_long
            else -> {
                onAdd(url)
                onCheck(url)
                input = ""
            }
        }
    }

    SfExpandableRow(
        icon = Icons.Outlined.Link,
        title = stringResource(R.string.relay_custom_url),
        subtitle = stringResource(R.string.relay_custom_hint),
        expanded = open,
        onToggle = { open = !open },
        modifier = Modifier.testTag("relay_custom_toggle")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                modifier = Modifier.weight(1f).testTag("relay_custom_input"),
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
                modifier = Modifier.padding(top = 4.dp).testTag("relay_custom_add")
            )
        }
    }
}

/** One relay: status indicator, hostname, a status line in words, and an optional 48dp remove button. */
@Composable
private fun RelayRow(url: String, status: RelayCheckStatus, info: RelayInfo?, onRemove: (() -> Unit)?) {
    val host = url.removePrefix("wss://")
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
                host,
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
                onClick = onRemove,
                modifier = Modifier.testTag("relay_remove_$host")
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
