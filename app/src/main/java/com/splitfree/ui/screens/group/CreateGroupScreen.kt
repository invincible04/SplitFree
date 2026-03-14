package com.splitfree.ui.screens.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.ui.components.RelayEditor
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.CreateGroupViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onGroupCreated: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: CreateGroupViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var showRelays by remember { mutableStateOf(false) }
    val relays by viewModel.relays.collectAsStateWithLifecycle()
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val relayInfo by viewModel.relayInfo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    LaunchedEffect(error) {
        error?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearError()
        }
    }
    LaunchedEffect(showRelays) { if (showRelays) viewModel.checkAllRelays() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_group)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = tokens.screenPaddingHorizontal, vertical = tokens.screenPaddingVertical)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing)
        ) {
            Text(
                stringResource(R.string.create_group_hint),
                style = if (adaptive.isCompact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.group_name)) },
                placeholder = { Text(stringResource(R.string.group_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // Expandable relay section
            TextButton(onClick = { showRelays = !showRelays }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.relays_count, relays.size))
                    Icon(
                        if (showRelays) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(tokens.iconMedium)
                    )
                }
            }

            AnimatedVisibility(visible = showRelays) {
                Column {
                    Text(
                        stringResource(R.string.relay_section_hint),
                        style = if (adaptive.isCompact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(tokens.itemSpacing))
                    RelayEditor(
                        relays = relays,
                        relayStatuses = relayStatuses,
                        relayInfo = relayInfo,
                        onAdd = viewModel::addRelay,
                        onRemove = viewModel::removeRelay,
                        onCheck = viewModel::checkRelay
                    )
                }
            }

            Spacer(Modifier.height(tokens.denseSpacing))
            Button(
                onClick = { viewModel.createGroup(name) { onGroupCreated(it) } },
                modifier = Modifier.fillMaxWidth().height(tokens.buttonHeight),
                enabled = name.isNotBlank(),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = stringResource(R.string.create_group),
                    style = if (adaptive.isCompact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    }
                )
            }
        }
    }
}
