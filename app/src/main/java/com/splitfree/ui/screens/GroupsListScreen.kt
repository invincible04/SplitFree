package com.splitfree.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.domain.model.Group
import com.splitfree.ui.viewmodels.GroupsListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsListScreen(
    onGroupClick: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onSettings: () -> Unit,
    viewModel: GroupsListViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SplitFree") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateGroup) {
                Icon(Icons.Default.Add, contentDescription = "Create group")
            }
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No groups yet. Tap + to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(groups, key = { it.id }) { group ->
                    GroupItem(group = group, onClick = { onGroupClick(group.id) })
                }
            }
        }
    }
}

@Composable
private fun GroupItem(group: Group, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(group.name) },
        supportingContent = { Text("${group.members.size} members") },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
