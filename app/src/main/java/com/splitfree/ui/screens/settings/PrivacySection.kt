package com.splitfree.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.splitfree.R
import com.splitfree.ui.util.adaptiveSizeTokens

@Composable
fun PrivacySection(giftWrapEnabled: Boolean, onGiftWrapChange: (Boolean) -> Unit) {
    val tokens = adaptiveSizeTokens()
    SectionHeader(icon = Icons.Outlined.Shield, title = stringResource(R.string.privacy))

    ListItem(
        modifier = Modifier.padding(horizontal = tokens.screenPaddingHorizontal),
        headlineContent = { Text(stringResource(R.string.nip59_gift_wrap)) },
        supportingContent = { Text(stringResource(R.string.gift_wrap_hint)) },
        trailingContent = { Switch(checked = giftWrapEnabled, onCheckedChange = onGiftWrapChange) }
    )
}
