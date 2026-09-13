package com.splitfree.ui.screens.onboarding

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.ui.components.BrandMark
import com.splitfree.ui.components.HintCard
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.SfAccentButton
import com.splitfree.ui.components.SfBottomDock
import com.splitfree.ui.components.SfIconButton
import com.splitfree.ui.components.SfPrimaryButton
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfSheet
import com.splitfree.ui.components.SfSheetFooter
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.theme.SfMotion
import com.splitfree.ui.theme.SplitFreeTheme
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.ImportStatus
import com.splitfree.ui.viewmodels.OnboardingViewModel

/** The three panes of first launch. Held in `rememberSaveable` by the route; enums save as-is. */
enum class OnboardingStep {
    /** Art, pitch and the two entry points. */
    Welcome,

    /** Secret key / recovery phrase entry. Its text is deliberately never saved. */
    RestoreKey,

    /** Optional group-backup import once a key has been imported. */
    RestoreBackup
}

/** Which sheet is open over the welcome pane. */
enum class OnboardingSheet {
    /** "Choose a name" sheet. */
    Name
}

/**
 * Snapshot of [OnboardingViewModel]'s flows for the stateless content.
 *
 * @property error what went wrong with the last key import, or null.
 * @property keyImported true once a key was accepted; the route then shows [OnboardingStep.RestoreBackup].
 * @property importStatus outcome of the last backup import, or null before the first attempt.
 * @property importing true while a backup file is being read and imported.
 */
data class OnboardingUiState(
    val error: UiMessage? = null,
    val keyImported: Boolean = false,
    val importStatus: ImportStatus? = null,
    val importing: Boolean = false
)

/**
 * Everything the onboarding panes can ask the outside world to do. Step and sheet changes stay inside the
 * content via `onStep` / `onSheet`; these are the effects that hit the ViewModel, the document picker or
 * navigation. Defaults are no-ops so tests can pass only what they observe.
 *
 * @property generateIdentity create a fresh key pair with the (possibly blank) display name and finish.
 * @property importKey try to import a hex key / mnemonic; the ViewModel reports the result via state.
 * @property pickBackup open the document picker for a `.splitfree` backup file.
 * @property clearError drop the current key-import error (called when the secret text changes).
 * @property complete leave onboarding.
 */
data class OnboardingActions(
    val generateIdentity: (String) -> Unit = {},
    val importKey: (String) -> Unit = {},
    val pickBackup: () -> Unit = {},
    val clearError: () -> Unit = {},
    val complete: () -> Unit = {}
)

/**
 * First-launch route: collects [OnboardingViewModel], owns the current step and sheet, launches the backup
 * document picker and keeps `FLAG_SECURE` on the window for as long as the screen is shown (a private key or
 * 24-word mnemonic may be typed or pasted into the restore pane).
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    val keyImported by viewModel.keyImported.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
    var sheet by rememberSaveable { mutableStateOf<OnboardingSheet?>(null) }

    // Reading and importing happen in the ViewModel: a composable scope is cancelled by navigation
    // and recomposition, which would abort the import mid-transaction.
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackup(uri)
    }

    // FLAG_SECURE: block screenshots and screen recording while any onboarding pane is on the window.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    OnboardingContent(
        state = OnboardingUiState(
            error = error,
            keyImported = keyImported,
            importStatus = importStatus,
            importing = importing
        ),
        // A successful key import always lands on the backup pane, whatever the saved step says.
        step = if (keyImported) OnboardingStep.RestoreBackup else step,
        onStep = { step = it },
        sheet = sheet,
        onSheet = { sheet = it },
        actions =
        OnboardingActions(
            generateIdentity = { name ->
                viewModel.generateIdentity(name.trim())
                onComplete()
            },
            importKey = { viewModel.importKey(it) },
            pickBackup = { backupLauncher.launch(arrayOf("*/*")) },
            clearError = viewModel::clearError,
            complete = onComplete
        )
    )
}

private val WelcomeMarkSize = 72.dp

/** The ribbon fills about 50 of the mark's 66dp box, so its left edge sits this share of the size inside it. */
private const val MARK_SIDE_INSET = 0.12f
private val WelcomeTextMaxWidth = 360.dp
private const val TEXT_SCRIM_ALPHA = 0.66f
private val TextScrimFade = 96.dp
private const val BOTTOM_SCRIM_START = 0.68f
private const val BOTTOM_SCRIM_ALPHA = 0.8f

/**
 * Stateless onboarding: one of three panes cross-fading inside one surface (no Scaffold), plus the optional
 * name sheet. The welcome pane paints its backdrop edge to edge and insets only its content; the restore
 * panes inset as a whole. The restore pane keeps [SecretKeyField] and its `onboarding_secret_*` tags.
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    step: OnboardingStep,
    onStep: (OnboardingStep) -> Unit,
    sheet: OnboardingSheet?,
    onSheet: (OnboardingSheet?) -> Unit,
    actions: OnboardingActions,
    modifier: Modifier = Modifier
) {
    // System back on the key pane returns to the welcome pane instead of leaving the app.
    BackHandler(enabled = step == OnboardingStep.RestoreKey) {
        actions.clearError()
        onStep(OnboardingStep.Welcome)
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val enter =
                    fadeIn(tween(SfMotion.Base, easing = SfMotion.Ease)) +
                        slideInVertically(tween(SfMotion.Base, easing = SfMotion.Ease)) { it / PANE_RISE_DIVISOR }
                enter togetherWith fadeOut(tween(SfMotion.Fast, easing = SfMotion.Ease))
            },
            contentAlignment = Alignment.TopCenter,
            label = "onboardingStep"
        ) { current ->
            when (current) {
                OnboardingStep.Welcome ->
                    WelcomePane(
                        onGetStarted = { onSheet(OnboardingSheet.Name) },
                        onRestore = { onStep(OnboardingStep.RestoreKey) }
                    )

                OnboardingStep.RestoreKey ->
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        RestoreKeyPane(
                            state = state,
                            onImport = actions.importKey,
                            onSecretChanged = actions.clearError,
                            onBack = {
                                actions.clearError()
                                onStep(OnboardingStep.Welcome)
                            }
                        )
                    }

                OnboardingStep.RestoreBackup ->
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        RestoreBackupPane(
                            state = state,
                            onPickBackup = actions.pickBackup,
                            onComplete = actions.complete
                        )
                    }
            }
        }
    }

    if (sheet == OnboardingSheet.Name) {
        NameSheet(onContinue = actions.generateIdentity, onDismiss = { onSheet(null) })
    }
}

private const val PANE_RISE_DIVISOR = 40
private const val TRUST_PILL_ALPHA = 0.55f

// --- Welcome ------------------------------------------------------------------------------------------

/**
 * Full-bleed night landscape with the bare app mark, eyebrow, headline and pitch over the sky, and the trust
 * pills, the citron "Get started" and a muted restore link over the hills. The scene is dark in both themes, so
 * the pane always uses the dark palette and asks for light status bar icons while it is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomePane(onGetStarted: () -> Unit, onRestore: () -> Unit) {
    LightSystemBarIcons()
    SplitFreeTheme(darkTheme = true) {
        val tokens = adaptiveSizeTokens()
        val horizontal = tokens.screenPaddingHorizontal
        val scrim = MaterialTheme.colorScheme.scrim
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val reveal by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(SfMotion.Slow, easing = SfMotion.Ease),
            label = "welcomeReveal"
        )

        var copyHeightPx by remember { mutableIntStateOf(0) }
        val topInsetPx = WindowInsets.safeDrawing.getTop(LocalDensity.current)

        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.onboarding_backdrop),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics { testTag = "onboarding_art" },
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomCenter
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = reveal }
                    .drawBehind {
                        val fade = TextScrimFade.toPx()
                        val solid = topInsetPx + copyHeightPx
                        val total = solid + fade
                        drawRect(
                            brush =
                            Brush.verticalGradient(
                                0f to scrim.copy(alpha = TEXT_SCRIM_ALPHA),
                                solid / total to scrim.copy(alpha = TEXT_SCRIM_ALPHA),
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = total
                            ),
                            size = Size(size.width, total)
                        )
                    }
                    .background(
                        Brush.verticalGradient(
                            BOTTOM_SCRIM_START to Color.Transparent,
                            1f to scrim.copy(alpha = BOTTOM_SCRIM_ALPHA)
                        )
                    )
            )
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(
                    modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontal)
                        .graphicsLayer { alpha = reveal }
                        .testTag("onboarding_welcome")
                ) {
                    Column(Modifier.onSizeChanged { copyHeightPx = it.height }) {
                        Spacer(Modifier.height(20.dp))
                        // Pull the mark left by its transparent inset so the ribbon, not its box, lines up with the text.
                        BrandMark(
                            size = WelcomeMarkSize,
                            tile = false,
                            modifier = Modifier.offset(x = -WelcomeMarkSize * MARK_SIDE_INSET)
                        )
                        Spacer(Modifier.height(20.dp))
                        MiniLabel(stringResource(R.string.onboarding_eyebrow))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(max = WelcomeTextMaxWidth).semantics { heading() }
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.onboarding_subtitle),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = WelcomeTextMaxWidth)
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
                // Same padding as the bottom dock, without its surface gradient, so the hills stay visible. The
                // trust pills sit here, over the dark foreground, instead of competing with the sky and the ridge.
                Column(
                    Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = 14.dp, bottom = 18.dp)
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        TrustPill(stringResource(R.string.chip_encrypted))
                        TrustPill(stringResource(R.string.chip_decentralized))
                        TrustPill(stringResource(R.string.chip_free))
                    }
                    Spacer(Modifier.height(16.dp))
                    SfAccentButton(
                        text = stringResource(R.string.get_started),
                        onClick = onGetStarted,
                        modifier = Modifier.testTag("onboarding_get_started")
                    )
                    Spacer(Modifier.height(9.dp))
                    SfTextButton(
                        text = stringResource(R.string.existing_key),
                        onClick = onRestore,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_restore_link")
                    )
                }
            }
        }
    }
}

/** Light status and navigation bar icons while the caller is composed; the previous setting returns after. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val statusBefore = controller.isAppearanceLightStatusBars
        val navBefore = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = statusBefore
            controller.isAppearanceLightNavigationBars = navBefore
        }
    }
}

/**
 * Non-interactive trust badge: translucent surface, hairline border, pill shape. A plain [Surface] rather
 * than a chip so accessibility services do not announce an inert control.
 */
@Composable
private fun TrustPill(label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = TRUST_PILL_ALPHA),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/** "Choose a name" sheet: optional display name, then a fresh identity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(onContinue: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    SfSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.onboarding_name_sheet_title),
        scrollable = true,
        modifier = Modifier.testTag("onboarding_sheet_name"),
        footer = {
            SfSheetFooter(secondary = null) {
                SfPrimaryButton(
                    text = stringResource(R.string.continue_button),
                    onClick = { onContinue(name) },
                    modifier = Modifier.testTag("onboarding_name_continue")
                )
            }
        }
    ) {
        MiniLabel(stringResource(R.string.your_name))
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME_LENGTH) },
            modifier = Modifier.fillMaxWidth().testTag("onboarding_name_input"),
            placeholder = {
                Text(
                    stringResource(R.string.your_name_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions =
            KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onContinue(name) }),
            shape = MaterialTheme.shapes.medium,
            colors = onboardingFieldColors()
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_name_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val MAX_NAME_LENGTH = 50

// --- Restore: key ---------------------------------------------------------------------------------------

/**
 * Key / recovery-phrase entry. The secret lives in plain `remember` state so it is never written to saved
 * instance state and is dropped as soon as the pane leaves composition.
 */
@Composable
private fun RestoreKeyPane(
    state: OnboardingUiState,
    onImport: (String) -> Unit,
    onSecretChanged: () -> Unit,
    onBack: () -> Unit
) {
    val tokens = adaptiveSizeTokens()
    val horizontal = tokens.screenPaddingHorizontal
    var secret by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontal)
                .testTag("onboarding_restore_key")
        ) {
            Spacer(Modifier.height(4.dp))
            // Pull the 48dp button out by its inner padding so the arrow glyph lines up with the text below.
            SfIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
                modifier = Modifier.offset(x = -HeaderBackOutdent).testTag("onboarding_restore_back")
            )
            Spacer(Modifier.height(10.dp))
            MiniLabel(stringResource(R.string.onboarding_restore_eyebrow))
            Spacer(Modifier.height(5.dp))
            Text(
                stringResource(R.string.onboarding_restore_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_restore_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            SecretKeyField(
                value = secret,
                onValueChange = {
                    secret = it
                    onSecretChanged()
                },
                error = state.error?.asString()
            )
            Spacer(Modifier.height(18.dp))
            HintCard(text = stringResource(R.string.onboarding_restore_private_hint), icon = Icons.Outlined.Shield)
            Spacer(Modifier.height(24.dp))
        }
        SfBottomDock {
            SfPrimaryButton(
                text = stringResource(R.string.import_key),
                onClick = { onImport(secret) },
                enabled = secret.isNotBlank(),
                loading = state.importing,
                modifier = Modifier.testTag("onboarding_import_key")
            )
            Spacer(Modifier.height(9.dp))
            SfTextButton(
                text = stringResource(R.string.back),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_restore_cancel")
            )
        }
    }
}

private val HeaderBackOutdent = 12.dp

// --- Restore: backup -------------------------------------------------------------------------------------

/** Optional `.splitfree` backup import after a key was restored; Continue and Skip both leave onboarding. */
@Composable
private fun RestoreBackupPane(state: OnboardingUiState, onPickBackup: () -> Unit, onComplete: () -> Unit) {
    val tokens = adaptiveSizeTokens()
    val horizontal = tokens.screenPaddingHorizontal

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontal)
                .testTag("onboarding_restore_backup")
        ) {
            Spacer(Modifier.height(30.dp))
            MiniLabel(stringResource(R.string.onboarding_restore_eyebrow))
            Spacer(Modifier.height(5.dp))
            Text(
                stringResource(R.string.onboarding_backup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(18.dp))
            HintCard(text = stringResource(R.string.key_imported), icon = Icons.Outlined.Check)
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.import_backup_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            SfSecondaryButton(
                text = stringResource(R.string.import_backup_file),
                onClick = onPickBackup,
                enabled = !state.importing,
                leadingIcon = Icons.Outlined.FileDownload,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_pick_backup")
            )
            if (state.importing) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("onboarding_importing")
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.importing_backup),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            state.importStatus?.let { status ->
                Spacer(Modifier.height(14.dp))
                val statusModifier =
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("onboarding_status")
                when (status) {
                    is ImportStatus.Restored ->
                        HintCard(
                            text = pluralStringResource(R.plurals.backup_restored_events, status.count, status.count),
                            icon = Icons.Outlined.Check,
                            modifier = statusModifier
                        )

                    is ImportStatus.Failed ->
                        WarningCard(text = importFailedText(status.reason), modifier = statusModifier)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // Leaving the screen clears the ViewModel and cancels a running import (the transaction rolls
        // back), so both exits are held until the import settles.
        SfBottomDock {
            SfPrimaryButton(
                text = stringResource(R.string.continue_button),
                onClick = onComplete,
                enabled = !state.importing,
                modifier = Modifier.testTag("onboarding_continue")
            )
            Spacer(Modifier.height(9.dp))
            SfTextButton(
                text = stringResource(R.string.skip),
                onClick = onComplete,
                enabled = !state.importing,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_skip")
            )
        }
    }
}

/** "Import failed: <reason>" when the import path supplied a reason, otherwise the generic failure line. */
@Composable
private fun importFailedText(reason: String?): String =
    reason?.let { stringResource(R.string.backup_import_failed_reason, it) }
        ?: stringResource(R.string.backup_import_failed)

/** Card-fill text field colours shared by the name sheet and [SecretKeyField]. */
@Composable
private fun onboardingFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant
)

/**
 * Entry field for a hex private key or 24-word recovery phrase, labelled by a [MiniLabel] above it.
 *
 * Masked by default with a password keyboard (no autocorrect, no IME learning) so the secret is not
 * echoed to the screen or the keyboard's dictionary; the trailing eye icon reveals it on demand. The
 * supporting line carries the format hint or, when set, [error] (announced politely).
 */
@Composable
internal fun SecretKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    var reveal by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        MiniLabel(stringResource(R.string.private_key_or_seed))
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    stringResource(R.string.key_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.fillMaxWidth().testTag("onboarding_secret_input"),
            minLines = 3,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = { reveal = !reveal },
                    modifier = Modifier.testTag("onboarding_secret_toggle")
                ) {
                    Icon(
                        imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription =
                        stringResource(if (reveal) R.string.cd_hide_secret else R.string.cd_show_secret)
                    )
                }
            },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                        Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("onboarding_secret_error")
                    )
                } else {
                    Text(
                        stringResource(R.string.key_supporting_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            shape = MaterialTheme.shapes.medium,
            colors = onboardingFieldColors()
        )
    }
}
