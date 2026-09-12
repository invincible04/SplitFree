package com.splitfree.ui.screens.nearby

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.R
import com.splitfree.data.ble.NearbyPeer
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.NearbySyncUiState

@Composable
private fun NearbyPreviewHost(state: NearbySyncUiState, permissions: NearbyPermissionState = NearbyPermissionState()) {
    SplitFreeTheme { NearbySyncContent(state = state, permissions = permissions, actions = NearbyActions()) }
}

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun NearbyIdlePreview() {
    NearbyPreviewHost(NearbySyncUiState())
}

@Preview(name = "Peer found", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun NearbyPeerPreview() {
    NearbyPreviewHost(
        NearbySyncUiState(
            scanning = true,
            peers = listOf(NearbyPeer("preview-ep-1", "a1b2c3d4")),
            status = UiMessage.Res(R.string.nearby_scanning)
        )
    )
}

@Preview(name = "Permissions missing", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun NearbyPermissionsPreview() {
    NearbyPreviewHost(NearbySyncUiState(), NearbyPermissionState(granted = false))
}
