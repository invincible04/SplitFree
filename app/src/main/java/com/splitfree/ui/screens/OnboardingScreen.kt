package com.splitfree.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App icon
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "SplitFree",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Split expenses with anyone.\nNo accounts. No servers. No cost.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(12.dp))
            // Feature chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("🔒 Encrypted", "🌐 Decentralized", "💸 Free").forEach { label ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            AnimatedContent(targetState = showImport, label = "onboarding") { importing ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!importing) {
                        Button(
                            onClick = {
                                viewModel.generateIdentity()
                                onComplete()
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text("Get Started", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showImport = true },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.large
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
                            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (viewModel.importKey(importInput)) onComplete()
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = MaterialTheme.shapes.large,
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
        }
    }
}
