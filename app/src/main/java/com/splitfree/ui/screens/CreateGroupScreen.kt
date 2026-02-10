package com.splitfree.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.splitfree.ui.viewmodels.CreateGroupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onGroupCreated: (String) -> Unit,
    viewModel: CreateGroupViewModel = hiltViewModel()
) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("INR") }
    var currencyExpanded by remember { mutableStateOf(false) }
    val currencies = listOf("INR", "USD", "EUR", "GBP", "JPY", "AUD", "CAD")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Group") },
                navigationIcon = {
                    IconButton(onClick = { /* handled by nav */ }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Create a group to start splitting expenses with friends.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                placeholder = { Text("e.g., Goa Trip 2026") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // Currency picker
            ExposedDropdownMenuBox(
                expanded = currencyExpanded,
                onExpandedChange = { currencyExpanded = it }
            ) {
                OutlinedTextField(
                    value = currency,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default currency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = MaterialTheme.shapes.medium
                )
                ExposedDropdownMenu(
                    expanded = currencyExpanded,
                    onDismissRequest = { currencyExpanded = false }
                ) {
                    currencies.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c) },
                            onClick = {
                                currency = c
                                currencyExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    viewModel.createGroup(name) { groupId ->
                        onGroupCreated(groupId)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = name.isNotBlank(),
                shape = MaterialTheme.shapes.large
            ) {
                Text("Create Group", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
