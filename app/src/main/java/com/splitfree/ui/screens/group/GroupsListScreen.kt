package com.splitfree.ui.screens.group

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.usecase.group.GroupSummary
import com.splitfree.ui.components.CurrencyLine
import com.splitfree.ui.components.EmptyState
import com.splitfree.ui.components.IdentityTile
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.MoneyText
import com.splitfree.ui.components.PillTone
import com.splitfree.ui.components.SectionHead
import com.splitfree.ui.components.SfAccentButton
import com.splitfree.ui.components.SfBottomDock
import com.splitfree.ui.components.SfCard
import com.splitfree.ui.components.SfIconButton
import com.splitfree.ui.components.SfLargeTitleHeader
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.components.SignedMoneyText
import com.splitfree.ui.components.StatusPill
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.GroupsListUiState
import com.splitfree.ui.viewmodels.GroupsListViewModel
import com.splitfree.util.CurrencyFormatter
import com.splitfree.util.DebugLog as Log
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val TAG = "GroupsListScreen"

private val CardSpacing = 10.dp
private val GroupCardMinHeight = 84.dp
private val GroupSymbolSize = 47.dp
private val GroupMoneyMaxWidth = 125.dp
private val QuickTileMinHeight = 74.dp
private val QuickIconSize = 39.dp
private val HeroMinHeight = 190.dp
private val Tile16 = RoundedCornerShape(16.dp)
private val Tile13 = RoundedCornerShape(13.dp)

/** Large-text threshold above which invite tiles and card balances stack instead of sitting side by side. */
private const val STACK_FONT_SCALE = 1.5f

/**
 * Everything the home screen can ask its host to do. The route wires these to navigation, the QR scanner,
 * the clipboard and the ViewModel; tests pass recording lambdas.
 */
data class GroupsListActions(
    val openGroup: (String) -> Unit = {},
    val createGroup: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val pasteInvite: () -> Unit = {},
    val scanQr: () -> Unit = {},
    val selectCurrency: (String) -> Unit = {},
    val retryBalances: () -> Unit = {}
)

/**
 * Home: every group the user belongs to, with their per-currency balance in each.
 *
 * Top bar actions scan an invite QR, paste an invite link from the clipboard and open Settings. The bottom
 * dock creates a new group. Nothing here decrypts anything: balances arrive pre-computed in
 * [GroupsListUiState] from [GroupsListViewModel].
 *
 * @param onGroupClick navigates to group detail
 * @param onCreateGroup navigates to group creation
 * @param onSettings navigates to settings
 * @param onScanResult callback for scanned/pasted invite links
 */
@Composable
fun GroupsListScreen(
    onGroupClick: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onSettings: () -> Unit,
    onScanResult: (String) -> Unit = {},
    viewModel: GroupsListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val noInviteMessage = stringResource(R.string.clipboard_no_invite)

    val errorText = state.error?.asString()
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.clearError()
        }
    }

    val actions =
        remember(onGroupClick, onCreateGroup, onSettings, onScanResult, clipboard, context, noInviteMessage) {
            GroupsListActions(
                openGroup = onGroupClick,
                createGroup = onCreateGroup,
                openSettings = onSettings,
                pasteInvite = {
                    scope.launch {
                        val link = extractInviteLink(clipboard)
                        if (link != null) onScanResult(link) else snackbarHostState.showSnackbar(noInviteMessage)
                    }
                },
                scanQr = { startQrScan(context, onScanResult) },
                selectCurrency = viewModel::selectCurrency,
                retryBalances = viewModel::retryBalances
            )
        }

    GroupsListContent(state = state, actions = actions, snackbarHostState = snackbarHostState)
}

// --- Route helpers: clipboard and QR scanner ---

/**
 * Reads the clipboard and extracts a SplitFree invite link if present.
 * Handles the `splitfree://join` scheme even when the link is embedded in a larger message.
 *
 * @return the invite link, or null if the clipboard doesn't contain one
 */
private suspend fun extractInviteLink(clipboard: Clipboard): String? {
    val text = clipboard.getClipEntry()
        ?.clipData
        ?.getItemAt(0)
        ?.text
        ?.toString()
        ?.trim()
        ?: return null
    val link = text.lines().firstOrNull { it.trimStart().startsWith("splitfree://join") }?.trim()
    // The link is a bearer credential (it carries the group key); never log its payload.
    if (link != null) Log.i(TAG, "Pasted invite link from clipboard")
    return link
}

/** Launches the ML Kit barcode scanner for invite QR codes and forwards the raw value. */
private fun startQrScan(context: Context, onScanResult: (String) -> Unit) {
    val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
        .build()
    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode ->
            barcode.rawValue?.let {
                Log.i(TAG, "QR scanned (${it.length} chars)")
                onScanResult(it)
            }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "QR scan failed: ${e.message}")
        }
}

// --- Stateless content ---

/**
 * Stateless home layout: top bar, optional offline banner, greeting with a connection pill,
 * currency line, balance hero, group cards, invite tiles and the "New group" dock.
 * Four states: `loading` shows a skeleton, a failed observation with no list shows the unavailable notice, an
 * empty list shows [EmptyState], otherwise the ledger. The hero total appears only while every group's
 * balances are available; otherwise the unavailable notice with its Retry action takes its place.
 */
@Composable
internal fun GroupsListContent(
    state: GroupsListUiState,
    actions: GroupsListActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val tokens = adaptiveSizeTokens()
    val adaptive = adaptiveLayoutInfo()
    val horizontal = tokens.screenPaddingHorizontal
    // Compact width at large text: reflow rows into columns rather than shrink or clip anything.
    val stackForLargeText = adaptive.isCompact && adaptive.fontScale >= STACK_FONT_SCALE

    Scaffold(
        modifier = modifier,
        topBar = { HomeTopBar(actions, endInset = horizontal) },
        bottomBar = {
            SfBottomDock {
                SfAccentButton(
                    text = stringResource(R.string.new_group),
                    onClick = actions.createGroup,
                    leadingIcon = Icons.Outlined.Add,
                    modifier = Modifier.testTag("home_new_group")
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.currencies_stay_separate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.splitFree.faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            // Only the top inset is consumed here: content scrolls beneath the dock's gradient instead.
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).testTag("home_list"),
            contentPadding =
            PaddingValues(start = horizontal, end = horizontal, bottom = padding.calculateBottomPadding() + 12.dp)
        ) {
            item(key = "greeting") {
                // The banner shares the first item with the greeting: the list anchors on its first item, so a
                // banner that appears later grows this item in place instead of landing above the viewport.
                Column {
                    if (state.connection == ConnectionStatus.Offline) {
                        WarningCard(
                            text = stringResource(R.string.offline_banner),
                            icon = Icons.Outlined.WifiOff,
                            modifier = Modifier.padding(bottom = 13.dp).testTag("home_offline")
                        )
                    }
                    SfLargeTitleHeader(
                        eyebrow = stringResource(R.string.home_eyebrow),
                        title = stringResource(R.string.home_title)
                    ) {
                        ConnectionPill(status = state.connection)
                    }
                }
            }
            when {
                state.loading -> item(key = "skeleton") { HomeSkeleton() }
                state.observationUnavailable && state.groups.isEmpty() -> item(key = "unavailable") {
                    BalanceUnavailableNotice(onRetry = actions.retryBalances)
                }
                state.groups.isEmpty() -> item(key = "empty") { HomeEmpty(onCreate = actions.createGroup) }
                else -> readyItems(state, actions, stacked = stackForLargeText)
            }
            item(key = "invite-head") { SectionHead(title = stringResource(R.string.have_an_invite)) }
            item(key = "invite-grid") {
                InviteQuickGrid(stacked = stackForLargeText, onScan = actions.scanQr, onPaste = actions.pasteInvite)
            }
        }
    }
}

private fun LazyListScope.readyItems(state: GroupsListUiState, actions: GroupsListActions, stacked: Boolean) {
    item(key = "currency") {
        CurrencyLine(
            currencies = state.currencies,
            selected = state.selectedCurrency,
            onSelect = actions.selectCurrency,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            chipModifier = Modifier.testTag("home_currency"),
            itemModifier = { Modifier.testTag("home_currency_$it") }
        )
    }
    if (state.balancesAvailable) {
        item(key = "hero") { BalanceHero(state) }
    } else {
        item(key = "unavailable") { BalanceUnavailableNotice(onRetry = actions.retryBalances) }
    }
    item(key = "spaces-head") {
        val count = state.groups.size
        SectionHead(title = stringResource(R.string.your_shared_spaces)) {
            Meta(pluralStringResource(R.plurals.group_count, count, count))
        }
    }
    items(state.groups, key = { "group:${it.group.id}" }) { summary ->
        GroupCard(
            summary = summary,
            currency = state.selectedCurrency,
            net = state.myNet(summary),
            stacked = stacked,
            onClick = { actions.openGroup(summary.group.id) },
            modifier = Modifier.padding(bottom = CardSpacing)
        )
    }
}

/** Takes the hero's place while any balance is unknown: no total, no zero, one Retry action. */
@Composable
private fun BalanceUnavailableNotice(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("home_balances_unavailable")) {
        WarningCard(text = stringResource(R.string.balances_unavailable_body))
        SfTextButton(
            text = stringResource(R.string.retry_balances),
            onClick = onRetry,
            modifier = Modifier.testTag("home_retry_balances")
        )
    }
}

// --- Top bar ---

@Composable
private fun HomeTopBar(actions: GroupsListActions, endInset: Dp) {
    SfTopBar(title = stringResource(R.string.app_name), onBack = null) {
        SfIconButton(
            icon = Icons.Outlined.QrCodeScanner,
            contentDescription = stringResource(R.string.scan_qr),
            onClick = actions.scanQr,
            modifier = Modifier.testTag("home_scan")
        )
        SfIconButton(
            icon = Icons.Outlined.ContentPaste,
            contentDescription = stringResource(R.string.paste_invite_link),
            onClick = actions.pasteInvite,
            modifier = Modifier.testTag("home_paste")
        )
        Spacer(Modifier.width(4.dp))
        // The ink identity tile is the Settings entry point.
        IdentityTile(
            initial = null,
            contentDescription = stringResource(R.string.settings),
            onClick = actions.openSettings,
            modifier = Modifier.testTag("home_settings")
        )
        // TopAppBar keeps 4dp after the last action; pad the rest so the tile lines up with the content edge.
        Spacer(Modifier.width((endInset - 4.dp).coerceAtLeast(0.dp)))
    }
}

// --- Pill ---

/** Connecting, Online or Offline in words; the dot tone only reinforces the word. */
@Composable
private fun ConnectionPill(status: ConnectionStatus) {
    val label =
        stringResource(
            when (status) {
                ConnectionStatus.Connecting -> R.string.status_connecting
                ConnectionStatus.Connected -> R.string.status_online
                ConnectionStatus.Offline -> R.string.status_offline
            }
        )
    val tone =
        when (status) {
            ConnectionStatus.Connecting -> PillTone.Neutral
            ConnectionStatus.Connected -> PillTone.Online
            ConnectionStatus.Offline -> PillTone.Offline
        }
    val description = stringResource(R.string.cd_connection_status, label)
    StatusPill(
        text = label,
        tone = tone,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    )
}

// --- Hero ---

/**
 * Inverted balance card. With no currency anywhere it says so plainly instead of
 * inventing a zero. Read by TalkBack as one node.
 */
@Composable
private fun BalanceHero(state: GroupsListUiState) {
    val palette = MaterialTheme.splitFree
    val currency = state.selectedCurrency
    Surface(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = HeroMinHeight)
            .semantics(mergeDescendants = true) {}
            .testTag("home_hero"),
        shape = MaterialTheme.shapes.extraLarge,
        color = palette.hero,
        contentColor = palette.onHero
    ) {
        Box {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 58.dp, y = (-40).dp)
                    .size(125.dp)
                    .clip(CircleShape)
                    .background(palette.heroAccent.copy(alpha = 0.55f))
            )
            Column(Modifier.padding(24.dp)) {
                if (currency == null) {
                    MiniLabel(text = stringResource(R.string.nothing_to_settle_yet), color = palette.heroMuted)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.nothing_to_settle_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onHero
                    )
                } else {
                    val net = state.netMinor
                    val label = stringResource(if (net >= 0) R.string.net_to_receive else R.string.net_to_pay)
                    MiniLabel(
                        text = stringResource(R.string.dot_separated, label, currency),
                        color = palette.heroMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    MoneyText(
                        amountMinor = abs(net),
                        currency = currency,
                        style = MaterialTheme.typography.displayLarge,
                        color = palette.onHero,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(20.dp))
                    // Stats sit 30dp apart at their natural width; at large text the second
                    // one flows onto its own line instead of breaking a number in half.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HeroStat(
                            label = stringResource(R.string.you_are_owed),
                            amountMinor = state.owedMinor,
                            currency = currency
                        )
                        HeroStat(
                            label = stringResource(R.string.you_owe),
                            amountMinor = state.oweMinor,
                            currency = currency
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, amountMinor: Long, currency: String, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.splitFree
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.heroMuted)
        Spacer(Modifier.height(4.dp))
        MoneyText(
            amountMinor = amountMinor,
            currency = currency,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onHero,
            maxLines = 2
        )
    }
}

// --- Group cards ---

/**
 * One group card: symbol tile, name and meta, then the user's balance in [currency] with the
 * direction spelled out. [net] is null when the user has no balance entry in [currency]. [stacked] puts the
 * balance under the title (large text on compact widths) instead of in a capped trailing column. A group
 * whose balances are unavailable shows no amount and says so in place of the direction.
 */
@Composable
private fun GroupCard(
    summary: GroupSummary,
    currency: String?,
    net: Long?,
    stacked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val people = summary.group.members.size
    val peopleText = pluralStringResource(R.plurals.people_count, people, people)
    val meta = if (currency == null) peopleText else stringResource(R.string.dot_separated, peopleText, currency)
    val showAmount = summary.balancesAvailable && currency != null && (net != null || currency in summary.currencies)
    val shownNet = net ?: 0L
    val direction =
        when {
            !summary.balancesAvailable -> stringResource(R.string.balance_unavailable)
            currency == null -> stringResource(R.string.direction_no_expenses_yet)
            shownNet > 0 -> stringResource(R.string.direction_owed_to_you)
            shownNet < 0 -> stringResource(R.string.direction_you_owe)
            currency in summary.currencies -> stringResource(R.string.direction_settled)
            else -> stringResource(R.string.direction_no_expenses, currency)
        }
    val description =
        if (showAmount) {
            stringResource(
                R.string.cd_group_card,
                summary.group.name,
                peopleText,
                CurrencyFormatter.formatMagnitude(shownNet, currency),
                direction
            )
        } else {
            stringResource(R.string.cd_group_card_no_amount, summary.group.name, peopleText, direction)
        }

    SfCard(
        onClick = onClick,
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = GroupCardMinHeight)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .testTag("home_group_${summary.group.id}")
    ) {
        if (stacked) {
            // Large text on a narrow screen: the balance moves under the name so neither has to be cut short.
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupSymbol()
                    Spacer(Modifier.width(12.dp))
                    GroupTitle(summary.group.name, meta, maxLines = 2, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(CardSpacing))
                GroupBalance(
                    currency = currency,
                    net = shownNet,
                    showAmount = showAmount,
                    direction = direction,
                    alignment = Alignment.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = GroupCardMinHeight).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupSymbol()
                Spacer(Modifier.width(12.dp))
                GroupTitle(summary.group.name, meta, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                GroupBalance(
                    currency = currency,
                    net = shownNet,
                    showAmount = showAmount,
                    direction = direction,
                    alignment = Alignment.End,
                    modifier = Modifier.widthIn(max = GroupMoneyMaxWidth)
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(name: String, meta: String, maxLines: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Meta(meta)
    }
}

@Composable
private fun GroupBalance(
    currency: String?,
    net: Long,
    showAmount: Boolean,
    direction: String,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = alignment) {
        if (showAmount && currency != null) {
            SignedMoneyText(
                amountMinor = net,
                currency = currency,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            direction,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (alignment == Alignment.End) TextAlign.End else TextAlign.Start
        )
    }
}

/** 47dp `surfaceContainer` tile with the group glyph. Decorative. */
@Composable
private fun GroupSymbol() {
    Box(
        modifier = Modifier.size(GroupSymbolSize).background(MaterialTheme.colorScheme.surfaceContainer, Tile16),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Group,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

// --- Loading, empty, invite ---

/** Three quiet placeholders where the group cards will be. No shimmer, no numbers. */
@Composable
private fun HomeSkeleton() {
    val loading = stringResource(R.string.cd_loading)
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(top = 27.dp)
            .semantics(mergeDescendants = true) { contentDescription = loading }
            .testTag("home_skeleton"),
        verticalArrangement = Arrangement.spacedBy(CardSpacing)
    ) {
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(GroupCardMinHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            )
        }
    }
}

@Composable
private fun HomeEmpty(onCreate: () -> Unit) {
    SfCard(modifier = Modifier.fillMaxWidth().testTag("home_empty")) {
        EmptyState(
            icon = Icons.Outlined.Group,
            title = stringResource(R.string.no_groups_yet),
            body = stringResource(R.string.no_groups_body)
        ) {
            SfAccentButton(
                text = stringResource(R.string.new_group),
                onClick = onCreate,
                leadingIcon = Icons.Outlined.Add,
                modifier = Modifier.testTag("home_empty_new_group")
            )
        }
    }
}

/** Two invite shortcuts; stacked when large text leaves no room side by side. */
@Composable
private fun InviteQuickGrid(stacked: Boolean, onScan: () -> Unit, onPaste: () -> Unit) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
            QuickTile(Icons.Outlined.QrCodeScanner, stringResource(R.string.quick_scan_qr), onScan, "home_quick_scan")
            QuickTile(
                Icons.Outlined.ContentPaste,
                stringResource(R.string.quick_paste_link),
                onPaste,
                "home_quick_paste"
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(CardSpacing)) {
            QuickTile(
                Icons.Outlined.QrCodeScanner,
                stringResource(R.string.quick_scan_qr),
                onScan,
                "home_quick_scan",
                Modifier.weight(1f)
            )
            QuickTile(
                Icons.Outlined.ContentPaste,
                stringResource(R.string.quick_paste_link),
                onPaste,
                "home_quick_paste",
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    SfCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = QuickTileMinHeight).semantics { role = Role.Button }.testTag(tag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(
                min = QuickTileMinHeight
            ).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(QuickIconSize).background(MaterialTheme.colorScheme.primaryContainer, Tile13),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Meta(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}
