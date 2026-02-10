package com.splitfree.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.ui.viewmodels.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var showImport by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SplitFree",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Split expenses with anyone.\nNo accounts. No servers. No cost.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))

        if (!showImport) {
            Button(
                onClick = {
                    viewModel.generateIdentity()
                    onComplete()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Get Started")
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showImport = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I have an existing key")
            }
        } else {
            OutlinedTextField(
                value = importInput,
                onValueChange = {
                    importInput = it
                    viewModel.clearError()
                },
                label = { Text("nsec, hex key, or seed phrase") },
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (viewModel.importKey(importInput)) onComplete()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = importInput.isNotBlank()
            ) {
                Text("Import Key")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                showImport = false
                importInput = ""
                viewModel.clearError()
            }) {
                Text("Back")
            }
        }
    }
}
