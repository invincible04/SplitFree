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
import androidx.compose.ui.unit.dp

enum class RelayCheckStatus { IDLE, CHECKING, ONLINE, OFFLINE }

/**
 * Reusable relay list editor with add/remove and per-relay status indicators.
 *
 * @param relays current relay URLs
 * @param relayStatuses map of relay URL → check status
 * @param onAdd called when user adds a new relay URL
 * @param onRemove called when user removes a relay URL
 * @param onCheck called to trigger a health check for a relay URL
 * @param editable whether add/remove controls are shown
 */
@Composable
fun RelayEditor(
    relays: List<String>,
    relayStatuses: Map<String, RelayCheckStatus> = emptyMap(),
    onAdd: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onCheck: (String) -> Unit = {},
    editable: Boolean = true
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        relays.forEach { url ->
            RelayRow(
                url = url,
                status = relayStatuses[url] ?: RelayCheckStatus.IDLE,
                onRemove = if (editable && relays.size > 1) ({ onRemove(url) }) else null
            )
        }

        if (editable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("wss://relay.example.com") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val url = input.trim().lowercase()
                    when {
                        !url.startsWith("wss://") -> error = "Must start with wss://"
                        url.length < 10 -> error = "URL too short"
                        url in relays -> error = "Already added"
                        else -> {
                            onAdd(url)
                            onCheck(url)
                            input = ""
                        }
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun RelayRow(url: String, status: RelayCheckStatus, onRemove: (() -> Unit)?) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(status)
            Spacer(Modifier.width(8.dp))
            Text(
                url.removePrefix("wss://"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: RelayCheckStatus) {
    when (status) {
        RelayCheckStatus.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.5.dp
        )
        else -> {
            val color by animateColorAsState(
                when (status) {
                    RelayCheckStatus.ONLINE -> Color(0xFF4CAF50)
                    RelayCheckStatus.OFFLINE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "statusColor"
            )
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = color,
                modifier = Modifier.size(10.dp),
                content = {}
            )
        }
    }
}
