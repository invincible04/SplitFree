package com.splitfree.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.theme.ThemeMode

/** Synthetic preview data lives in the debug source set, never in the release app. */
private fun previewSettings() = SettingsUiState(
    npub = "npub1previewa1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8",
    displayName = "Priya",
    pendingOutbox = 3,
    stuckOutbox = 1,
    giftWrapEnabled = true,
    themeMode = ThemeMode.LIGHT,
    appVersion = "1.0.0",
    isDebugBuild = true
)

@Composable
private fun SettingsPreviewHost(state: SettingsUiState, sheet: SettingsSheet? = null) {
    SplitFreeTheme { SettingsContent(state = state, actions = SettingsActions(), sheet = sheet, onSheet = {}) }
}

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun SettingsPreview() {
    SettingsPreviewHost(previewSettings())
}

@Preview(name = "No identity yet", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun SettingsNoIdentityPreview() {
    SettingsPreviewHost(previewSettings().copy(npub = "", displayName = "", pendingOutbox = 0, isDebugBuild = false))
}
