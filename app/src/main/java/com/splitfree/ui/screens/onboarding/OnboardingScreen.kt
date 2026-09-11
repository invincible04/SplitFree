package com.splitfree.ui.screens.onboarding

import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.ui.util.HeightClass
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.ImportStatus
import com.splitfree.ui.viewmodels.OnboardingViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    var showImport by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val keyImported by viewModel.keyImported.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()

    // Reading and importing happen in the ViewModel: a composable scope is cancelled by navigation
    // and recomposition, which would abort the import mid-transaction.
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importBackup(uri)
    }

    // FLAG_SECURE: a private key / 24-word mnemonic may be typed or pasted here, so block
    // screenshots and screen recording for as long as this screen is on the window.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
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
                // Feature chips — decorative, so plain surfaces rather than inert clickable chips
                // (TalkBack would otherwise announce a button that does nothing).
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
                        FeatureChip(label = label, minHeight = chipMinHeight, textStyle = chipTextStyle)
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
                                SecretKeyField(
                                    value = importInput,
                                    onValueChange = {
                                        importInput = it
                                        viewModel.clearError()
                                    },
                                    error = error?.asString()
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
                                    shape = MaterialTheme.shapes.large,
                                    enabled = !importing
                                ) {
                                    Text(stringResource(R.string.import_backup_file))
                                }
                                if (importing) {
                                    Spacer(Modifier.height(tokens.itemSpacing))
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.height(tokens.denseSpacing))
                                    Text(
                                        stringResource(R.string.importing_backup),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                importStatus?.let { status ->
                                    Spacer(Modifier.height(tokens.itemSpacing))
                                    val (statusText, statusColor) = when (status) {
                                        is ImportStatus.Restored -> pluralStringResource(
                                            R.plurals.backup_restored_events,
                                            status.count,
                                            status.count
                                        ) to MaterialTheme.colorScheme.primary
                                        is ImportStatus.Failed -> importFailedText(status.reason) to
                                            MaterialTheme.colorScheme.error
                                    }
                                    Text(
                                        statusText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = statusColor
                                    )
                                }
                                Spacer(Modifier.height(tokens.sectionSpacing))
                                // Leaving the screen clears the ViewModel and cancels a running import
                                // (the transaction rolls back), so hold the user here until it settles.
                                Button(
                                    onClick = onComplete,
                                    modifier = Modifier.fillMaxWidth().height(buttonHeight),
                                    shape = MaterialTheme.shapes.large,
                                    enabled = !importing
                                ) {
                                    Text(
                                        stringResource(R.string.continue_button),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(Modifier.height(tokens.itemSpacing))
                                TextButton(onClick = onComplete, enabled = !importing) {
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

/** "Import failed: <reason>" when the import path supplied a reason, otherwise the generic failure line. */
@Composable
private fun importFailedText(reason: String?): String =
    reason?.let { stringResource(R.string.backup_import_failed_reason, it) }
        ?: stringResource(R.string.backup_import_failed)

/**
 * Non-interactive feature badge styled like an outlined chip. It is a plain [Surface], not a
 * `SuggestionChip`, so accessibility services do not announce an inert control.
 */
@Composable
private fun FeatureChip(label: String, minHeight: Dp, textStyle: TextStyle) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.heightIn(min = minHeight)
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = textStyle)
        }
    }
}

/**
 * Entry field for a hex private key or 24-word seed phrase.
 *
 * Masked by default with a password keyboard (no autocorrect, no IME learning) so the secret is not
 * echoed to the screen or the keyboard's dictionary; the trailing eye icon reveals it on demand.
 */
@Composable
internal fun SecretKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.private_key_or_seed)) },
        placeholder = { Text(stringResource(R.string.key_placeholder)) },
        modifier = modifier.fillMaxWidth().testTag("onboarding_secret_input"),
        minLines = 3,
        maxLines = 5,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(
                onClick = { reveal = !reveal },
                modifier = Modifier.testTag("onboarding_secret_toggle")
            ) {
                Icon(
                    imageVector = if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription =
                    stringResource(if (reveal) R.string.cd_hide_secret else R.string.cd_show_secret)
                )
            }
        },
        isError = error != null,
        supportingText =
        error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
            ?: { Text(stringResource(R.string.key_supporting_text)) },
        shape = MaterialTheme.shapes.medium
    )
}
