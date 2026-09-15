package com.splitfree.ui.screens.nearby

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.R
import com.splitfree.sync.nearby.AttemptState
import com.splitfree.sync.nearby.CapabilityState
import com.splitfree.sync.nearby.PeerPhase
import com.splitfree.sync.nearby.PeerProgress
import com.splitfree.sync.nearby.RunPhase
import com.splitfree.sync.nearby.TransferStats
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.viewmodels.Headline
import com.splitfree.ui.viewmodels.NearbyNotice
import com.splitfree.ui.viewmodels.NearbyPeerRow
import com.splitfree.ui.viewmodels.NearbySyncUiState
import com.splitfree.ui.viewmodels.RowAction

@Composable
private fun NearbyPreviewHost(state: NearbySyncUiState, permissions: NearbyPermissionState = NearbyPermissionState()) {
    SplitFreeTheme { NearbySyncContent(state = state, permissions = permissions, actions = NearbyActions()) }
}

private val searchingState =
    NearbySyncUiState(
        phase = RunPhase.ACTIVE,
        advertising = CapabilityState.Running,
        discovery = CapabilityState.Running,
        headline = Headline.SEARCHING,
        notice = NearbyNotice(UiMessage.Res(R.string.nearby_searching_hint))
    )

private fun previewRow(
    endpointId: String,
    name: String?,
    attempt: AttemptState,
    action: RowAction,
    status: UiMessage? = null,
    progress: PeerProgress? = null
) = NearbyPeerRow(
    endpointId = endpointId,
    displayName = name?.let(UiMessage::Raw) ?: UiMessage.Res(R.string.nearby_phone),
    verifiedPubkey = progress?.peerPubkey,
    discovered = true,
    attempt = attempt,
    progress = progress,
    action = action,
    status = status
)

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun NearbyIdlePreview() {
    NearbyPreviewHost(NearbySyncUiState(enabled = false))
}

@Preview(name = "Searching", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun NearbySearchingPreview() {
    NearbyPreviewHost(searchingState)
}

@Preview(name = "Peer found", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun NearbyPeerPreview() {
    NearbyPreviewHost(
        searchingState.copy(
            headline = Headline.FOUND,
            notice = null,
            rows = listOf(previewRow("preview-ep-1", "a1b2c3d4", AttemptState.None, RowAction.SYNC))
        )
    )
}

@Preview(name = "Syncing", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun NearbySyncingPreview() {
    val progress =
        PeerProgress(
            endpointId = "preview-ep-1",
            peerPubkey = "a1b2c3d4e5f6a7b8",
            phase = PeerPhase.TRANSFERRING,
            groupId = "g1",
            stats = TransferStats(sent = 3, applied = 5)
        )
    NearbyPreviewHost(
        searchingState.copy(
            headline = Headline.SYNCING,
            notice = null,
            progressVisible = true,
            rows =
            listOf(
                previewRow(
                    "preview-ep-1",
                    "a1b2c3d4",
                    AttemptState.Connected(attemptId = 1, incoming = false),
                    RowAction.BUSY,
                    UiMessage.Res(R.string.nearby_transferring, 5, 3),
                    progress
                ),
                previewRow(
                    "preview-ep-2",
                    "e5f6a7b8",
                    AttemptState.Requesting(attemptId = 2),
                    RowAction.CONNECTING,
                    UiMessage.Res(R.string.nearby_connecting)
                )
            )
        )
    )
}

@Preview(name = "Permissions missing", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun NearbyPermissionsPreview() {
    NearbyPreviewHost(NearbySyncUiState(), NearbyPermissionState(granted = false))
}
