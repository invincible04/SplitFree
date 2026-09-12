package com.splitfree.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.BuildConfig
import com.splitfree.R
import com.splitfree.ui.components.DangerRow
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.SettingsRow
import com.splitfree.ui.components.SfDivider
import com.splitfree.ui.components.SfListCard
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.theme.ThemeMode
import com.splitfree.ui.theme.ThemePreference
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.ExportState
import com.splitfree.ui.viewmodels.RevokeState
import com.splitfree.ui.viewmodels.SettingsViewModel
import com.splitfree.util.ProcessHealthTracker
import kotlinx.coroutines.launch

/** Which bottom sheet is open over Settings. Held in `rememberSaveable` via [SettingsSheetSaver]. */
sealed interface SettingsSheet {
    /** Display-name editor; commits only on Save. */
    data object Profile : SettingsSheet

    /** System / light / dark choice. */
    data object Theme : SettingsSheet

    /** Hub for the recovery phrase, the raw private key and the group export. */
    data object Recovery : SettingsSheet

    /** 24-word recovery phrase, masked until revealed. */
    data object Phrase : SettingsSheet

    /** Raw private key, masked until revealed. */
    data object PrivateKey : SettingsSheet

    /** Encrypted group backup export with inline progress and result. */
    data object Export : SettingsSheet

    /** Outbox counts, version and the copyable health report. */
    data object Diagnostics : SettingsSheet

    /** Confirm replacing (revoking) the identity key. */
    data object Revoke : SettingsSheet
}

private val SheetNames: Map<String, SettingsSheet> =
    mapOf(
        "profile" to SettingsSheet.Profile,
        "theme" to SettingsSheet.Theme,
        "recovery" to SettingsSheet.Recovery,
        "phrase" to SettingsSheet.Phrase,
        "key" to SettingsSheet.PrivateKey,
        "export" to SettingsSheet.Export,
        "diagnostics" to SettingsSheet.Diagnostics,
        "revoke" to SettingsSheet.Revoke
    )

/**
 * Flattens a [SettingsSheet] to a name so the open sheet survives process death. Only *which* sheet is open is
 * saved; whether a secret is revealed lives in the ViewModel and is never restored from saved state.
 */
internal val SettingsSheetSaver: Saver<SettingsSheet?, String> =
    Saver(
        save = { sheet -> SheetNames.entries.firstOrNull { it.value == sheet }?.key ?: "" },
        restore = { name -> SheetNames[name] }
    )

/** Everything the settings screen shows, assembled from the ViewModel's flows so tests can render without Hilt. */
data class SettingsUiState(
    val npub: String = "",
    /** Raw private key; empty while hidden. Non-empty means "revealed" and turns on `FLAG_SECURE`. */
    val nsec: String = "",
    /** Recovery words; empty while hidden. Non-empty means "revealed" and turns on `FLAG_SECURE`. */
    val seedPhrase: List<String> = emptyList(),
    val displayName: String = "",
    val pendingOutbox: Int = 0,
    val stuckOutbox: Int = 0,
    val exportState: ExportState = ExportState.Idle,
    val revokeState: RevokeState = RevokeState.Idle,
    val giftWrapEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appVersion: String = "",
    val isDebugBuild: Boolean = false
) {
    /** Upper-cased first character of the display name, or null when there is no name to draw. */
    val avatarInitial: String?
        get() = displayName.trim().firstOrNull()?.uppercase()

    /** True while any secret is on screen; the window must carry `FLAG_SECURE`. */
    val secretVisible: Boolean
        get() = nsec.isNotEmpty() || seedPhrase.isNotEmpty()
}

/**
 * Everything Settings can ask the outside world to do. Sheet transitions are handled inside [SettingsContent]
 * through `onSheet`; these lambdas are the effects that leave the screen (navigation, clipboard, document
 * picker) or hit the ViewModel. Defaults are no-ops so previews and tests can pass only what they observe.
 */
data class SettingsActions(
    val back: () -> Unit = {},
    val setDisplayName: (String) -> Unit = {},
    val setThemeMode: (ThemeMode) -> Unit = {},
    val setGiftWrap: (Boolean) -> Unit = {},
    val revealSeed: () -> Unit = {},
    val hideSeed: () -> Unit = {},
    val copySeed: () -> Unit = {},
    val revealKey: () -> Unit = {},
    val hideKey: () -> Unit = {},
    val copyKey: () -> Unit = {},
    val exportBackup: () -> Unit = {},
    val clearExportState: () -> Unit = {},
    val copyDiagnostics: () -> Unit = {},
    val openDebugLog: () -> Unit = {},
    val openSourceCode: () -> Unit = {},
    val openPrivacyPolicy: () -> Unit = {},
    val revoke: () -> Unit = {},
    val clearRevokeState: () -> Unit = {}
)

private const val DISPLAY_NAME_MAX_LENGTH = 50

@Composable
fun SettingsScreen(onBack: () -> Unit, onDebugLog: () -> Unit = {}, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val nsec by viewModel.nsec.collectAsStateWithLifecycle()
    val npub by viewModel.npub.collectAsStateWithLifecycle()
    val seedPhrase by viewModel.seedPhrase.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val outboxStatus by viewModel.outboxStatus.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val revokeState by viewModel.revokeState.collectAsStateWithLifecycle()
    val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
    var giftWrapEnabled by remember { mutableStateOf(viewModel.giftWrapEnabled) }
    var sheet by rememberSaveable(stateSaver = SettingsSheetSaver) { mutableStateOf<SettingsSheet?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The write happens in the ViewModel: a composable scope is cancelled by navigation and
    // recomposition, which would leave a half-written backup behind.
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) viewModel.exportAllGroups(uri)
        }

    // The Export sheet shows the result inline while it is open; a result that lands after the sheet was
    // closed is reported once through the snackbar. Consuming first keeps a recomposition from repeating it.
    val backupExportedMsg = stringResource(R.string.backup_exported)
    val backupFailedMsg = stringResource(R.string.export_failed)
    LaunchedEffect(exportState) {
        if (sheet == SettingsSheet.Export) return@LaunchedEffect
        val message =
            when (exportState) {
                ExportState.Done -> backupExportedMsg
                is ExportState.Error -> backupFailedMsg
                else -> return@LaunchedEffect
            }
        viewModel.clearExportState()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // A rotation that finishes while a secret is on screen swaps it for the new identity's secret.
    LaunchedEffect(revokeState) {
        if (revokeState is RevokeState.Done) {
            if (nsec.isNotEmpty()) viewModel.revealPrivateKey()
            if (seedPhrase.isNotEmpty()) viewModel.revealSeedPhrase()
        }
    }

    // Leaving the foreground clears revealed secrets; the sheet stays open in its masked state.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.hidePrivateKey()
        viewModel.hideSeedPhrase()
    }

    SettingsContent(
        state =
        SettingsUiState(
            npub = npub,
            nsec = nsec,
            seedPhrase = seedPhrase,
            displayName = displayName,
            pendingOutbox = outboxStatus.first,
            stuckOutbox = outboxStatus.second,
            exportState = exportState,
            revokeState = revokeState,
            giftWrapEnabled = giftWrapEnabled,
            themeMode = themeMode,
            appVersion = BuildConfig.VERSION_NAME,
            isDebugBuild = BuildConfig.DEBUG
        ),
        actions =
        SettingsActions(
            back = onBack,
            setDisplayName = { viewModel.setDisplayName(it.take(DISPLAY_NAME_MAX_LENGTH)) },
            // Plain recomposition: the bitmap reveal was dropped for reliability (see the UI review).
            setThemeMode = { ThemePreference.set(context, it) },
            setGiftWrap = {
                giftWrapEnabled = it
                viewModel.giftWrapEnabled = it
            },
            revealSeed = viewModel::revealSeedPhrase,
            hideSeed = viewModel::hideSeedPhrase,
            copySeed = { copyToClipboard(context, CLIP_LABEL_SEED, seedPhrase.joinToString(" ")) },
            revealKey = viewModel::revealPrivateKey,
            hideKey = viewModel::hidePrivateKey,
            copyKey = { copyToClipboard(context, CLIP_LABEL_NSEC, nsec) },
            exportBackup = { exportLauncher.launch(EXPORT_FILE_NAME) },
            clearExportState = viewModel::clearExportState,
            copyDiagnostics = {
                copyToClipboard(context, CLIP_LABEL_DIAGNOSTICS, ProcessHealthTracker.buildReport(context))
            },
            openDebugLog = onDebugLog,
            openSourceCode = { openUrl(context, SOURCE_URL) },
            openPrivacyPolicy = { openUrl(context, PRIVACY_URL) },
            revoke = viewModel::revokeKey,
            clearRevokeState = viewModel::clearRevokeState
        ),
        sheet = sheet,
        onSheet = { sheet = it },
        snackbarHostState = snackbarHostState
    )
}

private const val EXPORT_FILE_NAME = "splitfree-backup.splitfree"
private const val SOURCE_URL = "https://github.com/invincible04/SplitFree"
private const val PRIVACY_URL = "https://github.com/invincible04/SplitFree/blob/mainline/PRIVACY.md"

private val HeroTileSize = 55.dp
private val HeroTileShape = RoundedCornerShape(18.dp)
private val HeroIconSize = 26.dp
private val EditButtonMaxWidth = 132.dp
private val SectionGap = 22.dp
private const val NPUB_HEAD = 12
private const val NPUB_TAIL = 6

/**
 * Stateless body of Settings: top bar, profile hero, three eyebrow-labelled cards (appearance;
 * backup/recovery + gift wrap; export, diagnostics, debug logs (debug builds only) and the identity
 * replacement), the version line with source and privacy links, and whichever [sheet] is open. Holds `FLAG_SECURE` on the window
 * while [SettingsUiState.secretVisible].
 */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    sheet: SettingsSheet?,
    onSheet: (SettingsSheet?) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val tokens = adaptiveSizeTokens()
    SecureWindowEffect(secure = state.secretVisible)

    Scaffold(
        modifier = modifier,
        topBar = { SfTopBar(title = stringResource(R.string.settings), onBack = actions.back) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = tokens.screenPaddingHorizontal)
                .testTag("settings_scroll")
        ) {
            ProfileHero(state = state, onEdit = { onSheet(SettingsSheet.Profile) })

            SettingsSection(label = stringResource(R.string.settings_section_make_it_yours)) {
                SfListCard {
                    SettingsRow(
                        icon = Icons.Outlined.WbSunny,
                        title = stringResource(R.string.appearance),
                        subtitle = themeLabel(state.themeMode),
                        onClick = { onSheet(SettingsSheet.Theme) },
                        modifier = Modifier.testTag("settings_appearance")
                    )
                }
            }

            SettingsSection(label = stringResource(R.string.settings_section_keep_it_yours)) {
                SfListCard {
                    SettingsRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.settings_backup_recovery),
                        subtitle = stringResource(R.string.settings_backup_recovery_subtitle),
                        onClick = { onSheet(SettingsSheet.Recovery) },
                        modifier = Modifier.testTag("settings_recovery")
                    )
                    SfDivider()
                    ToggleRow(
                        title = stringResource(R.string.settings_hide_sender_metadata),
                        subtitle = stringResource(R.string.gift_wrap_hint),
                        checked = state.giftWrapEnabled,
                        onToggle = actions.setGiftWrap,
                        modifier = Modifier.testTag("settings_gift_wrap")
                    )
                }
            }

            SettingsSection(label = stringResource(R.string.settings_section_tools_support)) {
                SfListCard {
                    SettingsRow(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.settings_export_group_backup),
                        subtitle = stringResource(R.string.settings_export_group_backup_subtitle),
                        onClick = { onSheet(SettingsSheet.Export) },
                        modifier = Modifier.testTag("settings_export")
                    )
                    SfDivider()
                    SettingsRow(
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.settings_diagnostics),
                        subtitle = outboxSummary(state),
                        onClick = { onSheet(SettingsSheet.Diagnostics) },
                        modifier = Modifier.testTag("settings_diagnostics")
                    )
                    if (state.isDebugBuild) {
                        SfDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Terminal,
                            title = stringResource(R.string.debug_logs),
                            subtitle = stringResource(R.string.debug_logs_supporting),
                            onClick = actions.openDebugLog,
                            modifier = Modifier.testTag("settings_debug_logs")
                        )
                    }
                    SfDivider()
                    DangerRow(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.settings_replace_identity),
                        subtitle = stringResource(R.string.settings_replace_identity_subtitle),
                        onClick = { onSheet(SettingsSheet.Revoke) },
                        modifier = Modifier.testTag("settings_revoke")
                    )
                }
            }

            Text(
                stringResource(R.string.settings_about_version, state.appVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.splitFree.faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("settings_version")
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SfTextButton(
                    text = stringResource(R.string.settings_source_code),
                    onClick = actions.openSourceCode,
                    color = MaterialTheme.splitFree.faint,
                    modifier = Modifier.testTag("settings_source_code")
                )
                SfTextButton(
                    text = stringResource(R.string.settings_privacy_policy),
                    onClick = actions.openPrivacyPolicy,
                    color = MaterialTheme.splitFree.faint,
                    modifier = Modifier.testTag("settings_privacy_policy")
                )
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + tokens.screenBottomSpacer))
        }
    }

    if (sheet != null) {
        SettingsSheetHost(sheet = sheet, state = state, actions = actions, onSheet = onSheet)
    }
}

/** Ink avatar tile with the name initial, display name, shortened public key and the Edit action. */
@Composable
private fun ProfileHero(state: SettingsUiState, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(HeroTileSize).background(MaterialTheme.colorScheme.inverseSurface, HeroTileShape),
            contentAlignment = Alignment.Center
        ) {
            val initial = state.avatarInitial
            if (initial != null) {
                Text(
                    initial,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.testTag("settings_hero_initial")
                )
            } else {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = stringResource(R.string.cd_person_icon),
                    modifier = Modifier.size(HeroIconSize),
                    tint = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.displayName.ifBlank { stringResource(R.string.settings_your_profile) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("settings_hero_name")
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.npub.isBlank()) stringResource(R.string.settings_no_identity) else shortPublicKey(state.npub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Already shortened; at large text it may wrap once but must never clip.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("settings_hero_npub")
            )
        }
        Spacer(Modifier.width(8.dp))
        SfTextButton(
            text = stringResource(R.string.settings_edit),
            onClick = onEdit,
            modifier = Modifier.widthIn(max = EditButtonMaxWidth).testTag("settings_edit")
        )
    }
}

/** Eyebrow + card with a 7dp label gap and a 22dp gap to the next section. */
@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = SectionGap)) {
        MiniLabel(text = label, modifier = Modifier.padding(start = 3.dp, bottom = 7.dp))
        content()
    }
}

/**
 * [SettingsRow] that behaves as one switch: the whole row toggles, exposes `Role.Switch` with the checked state,
 * and the trailing [Switch] is purely visual so TalkBack does not announce two controls.
 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsRow(
        icon = Icons.Outlined.Shield,
        title = title,
        subtitle = subtitle,
        onClick = { onToggle(!checked) },
        modifier =
        modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        trailing = { Switch(checked = checked, onCheckedChange = null) }
    )
}

/** Localised label for the current [ThemeMode]; doubles as the Appearance row subtitle and the choice title. */
@Composable
internal fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }
)

/** Outbox health in one line when something is waiting; otherwise the generic description. */
@Composable
private fun outboxSummary(state: SettingsUiState): String = if (state.pendingOutbox > 0) {
    pluralStringResource(
        R.plurals.outbox_pending_status,
        state.pendingOutbox,
        state.pendingOutbox,
        state.stuckOutbox
    )
} else {
    stringResource(R.string.settings_diagnostics_subtitle)
}

/** `first 12…last 6` of a public key so the hero line never wraps; short keys are shown whole. */
internal fun shortPublicKey(key: String): String =
    if (key.length > NPUB_HEAD + NPUB_TAIL + 1) key.take(NPUB_HEAD) + "…" + key.takeLast(NPUB_TAIL) else key

/**
 * Adds `FLAG_SECURE` to the hosting window while [secure] so a revealed private key or recovery phrase cannot be
 * screenshotted or recorded; the flag is cleared when the secret hides and when the screen leaves composition.
 */
@Composable
private fun SecureWindowEffect(secure: Boolean) {
    val view = LocalView.current
    DisposableEffect(secure) {
        val window = view.context.findActivity()?.window
        if (secure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Clipboard labels whose contents are secrets and are auto-cleared after [CLIPBOARD_CLEAR_MS]. */
private const val CLIP_LABEL_NSEC = "nsec"
private const val CLIP_LABEL_SEED = "seed"
private const val CLIP_LABEL_DIAGNOSTICS = "diagnostics"
private val SENSITIVE_CLIP_LABELS = setOf(CLIP_LABEL_NSEC, CLIP_LABEL_SEED)
private const val CLIPBOARD_CLEAR_MS = 30_000L
private const val CLIP_EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private const val CLEAR_PRIMARY_CLIP_MIN_SDK = 28

/** Opens [url] in the user's browser; a toast if no app can handle it. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.open_link_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val sensitive = label in SENSITIVE_CLIP_LABELS
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(CLIP_EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
    // Only the secret copies are actually cleared below, so only they may promise it.
    val toastRes = if (sensitive) R.string.clipboard_copied_30s else R.string.clipboard_copied
    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
    if (sensitive) {
        val copiedText = text
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Only clear if the clipboard still holds the secret we copied.
                val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == copiedText) {
                    if (android.os.Build.VERSION.SDK_INT >= CLEAR_PRIMARY_CLIP_MIN_SDK) {
                        clipboard.clearPrimaryClip()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            } catch (_: Exception) {
            }
        }, CLIPBOARD_CLEAR_MS)
    }
}
