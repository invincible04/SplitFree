package com.splitfree.ui.screens.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.usecase.group.GroupSummary
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.viewmodels.GroupsListUiState

/** Synthetic preview data lives in the debug source set, never in the release app. */
private fun previewGroups(): GroupsListUiState = GroupsListUiState(
    loading = false,
    connection = ConnectionStatus.Connected,
    currencies = listOf("INR", "USD"),
    selectedCurrency = "INR",
    groups = listOf(
        previewSummary("goa", "Goa trip", people = 4, mine = mapOf("INR" to 240000L)),
        previewSummary("flat", "Flatmates", people = 3, mine = mapOf("INR" to 75000L, "USD" to -1500L)),
        previewSummary("club", "Badminton", people = 6, mine = mapOf("INR" to -65000L))
    )
)

private fun previewSummary(id: String, name: String, people: Int, mine: Map<String, Long>) = GroupSummary(
    group = Group(
        id = id,
        name = name,
        createdBy = "preview-self",
        createdAt = 1000L,
        members = List(people) { if (it == 0) "preview-self" else "preview-$it" },
        relays = RelayDefaults.DEFAULT_RELAYS
    ),
    myBalances = mine,
    hasExpenses = true
)

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun GroupsListPreview() {
    SplitFreeTheme { GroupsListContent(state = previewGroups(), actions = GroupsListActions()) }
}

@Preview(name = "Offline + empty", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun GroupsListEmptyPreview() {
    SplitFreeTheme {
        GroupsListContent(
            state = GroupsListUiState(loading = false, connection = ConnectionStatus.Offline),
            actions = GroupsListActions()
        )
    }
}

@Preview(name = "Connecting + loading", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun GroupsListConnectingPreview() {
    SplitFreeTheme {
        GroupsListContent(
            state = GroupsListUiState(loading = true, connection = ConnectionStatus.Connecting),
            actions = GroupsListActions()
        )
    }
}

@Preview(name = "Light", widthDp = 390, heightDp = 844, showBackground = true)
@Preview(name = "Dark", widthDp = 390, heightDp = 844, uiMode = 0x20, showBackground = true)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f, showBackground = true)
@Composable
private fun CreateGroupPreview() {
    SplitFreeTheme {
        CreateGroupContent(
            name = "Weekend away",
            onName = {},
            relays = CreateGroupRelays(relays = RelayDefaults.DEFAULT_RELAYS),
            showRelays = false,
            onToggleRelays = {},
            error = null,
            isCreating = false,
            onCreate = {},
            onBack = {}
        )
    }
}

@Preview(name = "Relays open + error", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun CreateGroupRelaysPreview() {
    SplitFreeTheme {
        CreateGroupContent(
            name = "",
            onName = {},
            relays = CreateGroupRelays(relays = RelayDefaults.DEFAULT_RELAYS),
            showRelays = true,
            onToggleRelays = {},
            error = "Could not create group",
            isCreating = false,
            onCreate = {},
            onBack = {}
        )
    }
}
