package com.splitfree.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.ui.util.HeightClass
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.OnboardingViewModel
import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "OnboardingScreen"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    var showImport by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val keyImported by viewModel.keyImported.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                // A picked file can be unreadable (deleted, permission revoked); that used to
                // throw out of this scope and crash instead of reporting a failed import.
                val json = try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Backup read failed: ${e.message}")
                    null
                }
                if (json != null) viewModel.importBackup(json) else viewModel.reportUnreadableBackup()
            }
        }
    }

    val horizontalPadding = tokens.screenPaddingHorizontal
    val verticalPadding = tokens.screenPaddingVertical
    val buttonHeight = tokens.buttonHeight
    val iconSize = tokens.iconLarge
    val heroSpacing = tokens.sectionSpacing
    val controlsSpacing = tokens.sectionSpacing
    val titleStyle =
        if (adaptive.isCompact) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.headlineLarge
        }
    val subtitleStyle =
        if (adaptive.isCompact) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.bodyLarge
        }
    val chipSpacing = tokens.chipSpacing
    val chipMinHeight = if (adaptive.isCompact) 30.dp else 34.dp
    val chipTextStyle =
        if (adaptive.isCompact) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelMedium
        }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement =
                if (adaptive.heightClass == HeightClass.Compact) Arrangement.Top else Arrangement.Center
            ) {
                // App icon
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(heroSpacing))
                Text(
                    text = stringResource(R.string.app_name),
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(tokens.denseSpacing))
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = subtitleStyle,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(tokens.itemSpacing))
                // Feature chips
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(chipSpacing, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(chipSpacing)
                ) {
                    listOf(
                        stringResource(R.string.chip_encrypted),
                        stringResource(R.string.chip_decentralized),
                        stringResource(R.string.chip_free)
                    ).forEach { label ->
                        SuggestionChip(
                            onClick = {},
                            modifier = Modifier.heightIn(min = chipMinHeight),
                            label = { Text(label, style = chipTextStyle) }
                        )
                    }
                }

                Spacer(Modifier.height(controlsSpacing))

                AnimatedContent(
                    targetState = if (keyImported) {
                        "backup"
                    } else if (showImport) {
                        "key"
                    } else {
                        "welcome"
                    },
                    label = "onboarding"
                ) { step ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (step) {
                            "welcome" -> {
                                OutlinedTextField(
                                    value = nameInput,
                                    onValueChange = { nameInput = it.take(50) },
                                    label = { Text(stringResource(R.string.your_name)) },
                                    placeholder = { Text(stringResource(R.string.your_name_placeholder)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Person, contentDescription = null)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium
                                )
                                Spacer(Modifier.height(tokens.fieldSpacing))
                                Button(
                                    onClick = {
                                        viewModel.generateIdentity(nameInput.trim())
                                        onComplete()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Text(
                                        stringResource(R.string.get_started),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(Modifier.height(tokens.itemSpacing))
                                OutlinedButton(
                                    onClick = { showImport = true },
                                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Text(stringResource(R.string.existing_key))
                                }
                            }
                            "key" -> {
                                OutlinedTextField(
                                    value = importInput,
                                    onValueChange = {
                                        importInput = it
                                        viewModel.clearError()
                                    },
                                    label = { Text(stringResource(R.string.private_key_or_seed)) },
                                    placeholder = { Text(stringResource(R.string.key_placeholder)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3,
                                    maxLines = 5,
                                    isError = error != null,
                                    supportingText =
                                    error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                                        ?: { Text(stringResource(R.string.key_supporting_text)) },
                                    shape = MaterialTheme.shapes.medium
                                )
                                Spacer(Modifier.height(tokens.fieldSpacing))
                                Button(
                                    onClick = { viewModel.importKey(importInput) },
                                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                                    shape = MaterialTheme.shapes.large,
                                    enabled = importInput.isNotBlank()
                                ) {
                                    Text(stringResource(R.string.import_key))
                                }
                                Spacer(Modifier.height(tokens.itemSpacing))
                                TextButton(onClick = {
                                    showImport = false
                                    importInput = ""
                                    viewModel.clearError()
                                }) {
                                    Text(stringResource(R.string.back))
                                }
                            }
                            "backup" -> {
                                Text(
                                    stringResource(R.string.key_imported),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(tokens.fieldSpacing))
                                Text(
                                    stringResource(R.string.import_backup_hint),
                                    style = subtitleStyle,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(tokens.sectionSpacing))
                                OutlinedButton(
                                    onClick = { backupLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Text(stringResource(R.string.import_backup_file))
                                }
                                importStatus?.let {
                                    Spacer(Modifier.height(tokens.itemSpacing))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (it.startsWith("Restored")) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                                Spacer(Modifier.height(tokens.sectionSpacing))
                                Button(
                                    onClick = onComplete,
                                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Text(
                                        stringResource(R.string.continue_button),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(Modifier.height(tokens.itemSpacing))
                                TextButton(onClick = onComplete) {
                                    Text(stringResource(R.string.skip))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
