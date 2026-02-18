package com.splitfree.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PrivacySection(
    giftWrapEnabled: Boolean,
    onGiftWrapChange: (Boolean) -> Unit,
    customRelayCount: Int,
    onEditRelays: () -> Unit
) {
    SectionHeader(icon = Icons.Outlined.Shield, title = "Privacy")

    ListItem(
        headlineContent = { Text("NIP-59 Gift Wrap") },
        supportingContent = { Text("Hide your identity from relay operators. Increases event size.") },
        trailingContent = { Switch(checked = giftWrapEnabled, onCheckedChange = onGiftWrapChange) }
    )

    ListItem(
        headlineContent = { Text("Custom Relays") },
        supportingContent = {
            if (customRelayCount == 0) {
                Text("Using default public relays. Tap to configure your own.")
            } else {
                Text("$customRelayCount custom relay(s) configured")
            }
        },
        trailingContent = { FilledTonalButton(onClick = onEditRelays) { Text("Edit") } }
    )
}
