package com.splitfree.ui.screens.groupdetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.ui.components.CurrencyLine
import com.splitfree.ui.components.MemberStack
import com.splitfree.ui.components.MiniLabel
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.ui.components.SegmentedTabs
import com.splitfree.ui.components.SfAccentButton
import com.splitfree.ui.components.SfIconButton
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.components.SignedMoneyText
import com.splitfree.ui.components.WarningCard
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.GroupDetailUiState
import com.splitfree.ui.viewmodels.GroupDetailViewModel
import kotlinx.coroutines.launch

/** Which bottom sheet is open over the group screen. Held in `rememberSaveable` via [GroupSheetSaver]. */
sealed interface GroupSheet {
    /** QR + copy + share in one surface, with the bearer-key warning. */
    data object Invite : GroupSheet

    /** Secondary tools: nearby sync and relays. */
    data object Tools : GroupSheet

    /** Relay list bound to the ViewModel's draft; editable for the creator. */
    data object SyncStatus : GroupSheet

    /** Confirm recording [debt] as paid. */
    data class Settle(val debt: DebtTransaction) : GroupSheet

    /** Breakdown of one expense; its author can edit or delete it from here. */
    data class ExpenseDetail(val identity: ExpenseIdentity) : GroupSheet

    /** Confirm removing [pubkey] (creator only). */
    data class RemoveMember(val pubkey: String) : GroupSheet
}

/** Flattens a [GroupSheet] to primitives so the open sheet survives process death. */
internal val GroupSheetSaver: Saver<GroupSheet?, Any> =
    listSaver(
        save = { sheet ->
            when (sheet) {
                null -> emptyList()
                GroupSheet.Invite -> listOf(SHEET_INVITE)
                GroupSheet.Tools -> listOf(SHEET_TOOLS)
                GroupSheet.SyncStatus -> listOf(SHEET_SYNC)
                is GroupSheet.Settle ->
                    listOf(
                        SHEET_SETTLE,
                        sheet.debt.from,
                        sheet.debt.to,
                        sheet.debt.amount.toString(),
                        sheet.debt.currency
                    )
                is GroupSheet.ExpenseDetail ->
                    listOf(SHEET_EXPENSE, sheet.identity.authorPubkey, sheet.identity.expenseUuid)
                is GroupSheet.RemoveMember -> listOf(SHEET_REMOVE, sheet.pubkey)
            }
        },
        restore = { parts ->
            when (parts.firstOrNull()) {
                SHEET_INVITE -> GroupSheet.Invite
                SHEET_TOOLS -> GroupSheet.Tools
                SHEET_SYNC -> GroupSheet.SyncStatus
                SHEET_SETTLE ->
                    parts.getOrNull(SETTLE_PART_COUNT - 1)?.let {
                        GroupSheet.Settle(DebtTransaction(parts[1], parts[2], parts[3].toLong(), parts[4]))
                    }
                SHEET_EXPENSE -> parts.takeIf { it.size == EXPENSE_PART_COUNT && it.drop(1).all(String::isNotBlank) }
                    ?.let { GroupSheet.ExpenseDetail(ExpenseIdentity(it[1], it[2])) }
                SHEET_REMOVE -> parts.getOrNull(1)?.let { GroupSheet.RemoveMember(it) }
                else -> null
            }
        }
    )

private const val SHEET_INVITE = "invite"
private const val SHEET_TOOLS = "tools"
private const val SHEET_SYNC = "sync"
private const val SHEET_SETTLE = "settle"
private const val SHEET_EXPENSE = "expense"
private const val SHEET_REMOVE = "remove"
private const val SETTLE_PART_COUNT = 5
private const val EXPENSE_PART_COUNT = 3

/**
 * Everything the group screen can ask the outside world to do. Sheet and tab changes are handled inside
 * [GroupDetailContent] through `onSheet` / `onSelectTab`; these lambdas are the effects that leave the
 * screen (navigation, clipboard, share sheet) or hit the ViewModel. Defaults are no-ops so previews and
 * tests can pass only what they observe.
 */
data class GroupDetailActions(
    val addExpense: () -> Unit = {},
    val nearbySync: () -> Unit = {},
    val back: () -> Unit = {},
    val share: () -> Unit = {},
    val copyInvite: () -> Unit = {},
    val confirmSettle: (DebtTransaction) -> Unit = {},
    val editExpense: (ExpenseIdentity) -> Unit = {},
    val deleteExpense: (ExpenseIdentity) -> Unit = {},
    val removeMember: (String) -> Unit = {},
    val beginRelayEdit: () -> Unit = {},
    val cancelRelayEdit: () -> Unit = {},
    val addRelay: (String) -> Unit = {},
    val removeRelay: (String) -> Unit = {},
    val checkRelay: (String) -> Unit = {},
    val checkAllRelays: () -> Unit = {},
    val resetRelays: () -> Unit = {},
    val saveRelays: (onDone: () -> Unit) -> Unit = { it() },
    val selectCurrency: (String) -> Unit = {},
    val retryBalances: () -> Unit = {},
    val retryInvite: () -> Unit = {}
)

@Composable
fun GroupDetailScreen(
    onAddExpense: (String) -> Unit,
    onEditExpense: (ExpenseIdentity) -> Unit = {},
    onNearbySync: (String) -> Unit = {},
    onBack: () -> Unit,
    expenseSaved: Boolean = false,
    onExpenseSavedConsumed: () -> Unit = {},
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val inviteLink by viewModel.inviteLink.collectAsStateWithLifecycle()
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val relayInfo by viewModel.relayInfo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sheet by rememberSaveable(stateSaver = GroupSheetSaver) { mutableStateOf<GroupSheet?>(null) }
    var selectedCurrency by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_SUMMARY) }

    ExpenseSavedEffect(
        expenseSaved = expenseSaved,
        onConsumed = onExpenseSavedConsumed,
        onShowExpenses = { selectedTab = TAB_EXPENSES },
        snackbarHostState = snackbarHostState
    )

    val errorText = error?.asString()
    LaunchedEffect(error) {
        errorText?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearError()
        }
    }
    val messageText = message?.asString()
    LaunchedEffect(message) {
        messageText?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearMessage()
        }
    }

    InviteRefreshEffect(sheet, viewModel::retryInviteLink)

    val linkCopied = stringResource(R.string.group_link_copied)
    GroupDetailContent(
        state = uiState,
        inviteLink = inviteLink,
        relayStatuses = relayStatuses,
        relayInfo = relayInfo,
        selectedCurrency = selectedCurrency,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        actions =
        GroupDetailActions(
            addExpense = { onAddExpense(uiState.groupId) },
            nearbySync = { onNearbySync(uiState.groupId) },
            back = onBack,
            share = { viewModel.inviteLink.value?.let { shareInvite(context, it) } },
            copyInvite = {
                viewModel.inviteLink.value?.let {
                    copyInvite(context, it)
                    scope.launch { snackbarHostState.showSnackbar(linkCopied) }
                }
            },
            confirmSettle = viewModel::recordSettlement,
            editExpense = onEditExpense,
            deleteExpense = viewModel::deleteExpense,
            removeMember = viewModel::removeMember,
            beginRelayEdit = viewModel::beginRelayEdit,
            cancelRelayEdit = viewModel::cancelRelayEdit,
            addRelay = viewModel::addRelay,
            removeRelay = viewModel::removeRelay,
            checkRelay = viewModel::checkRelay,
            checkAllRelays = viewModel::checkAllRelays,
            resetRelays = viewModel::resetRelays,
            saveRelays = viewModel::saveRelays,
            selectCurrency = { selectedCurrency = it },
            retryBalances = viewModel::retryBalances,
            retryInvite = viewModel::retryInviteLink
        ),
        sheet = sheet,
        onSheet = { sheet = it },
        snackbarHostState = snackbarHostState
    )
}

/** Renew on opening and foreground return, including an Invite sheet restored after process death. */
@Composable
internal fun InviteRefreshEffect(sheet: GroupSheet?, refresh: () -> Unit) {
    LifecycleResumeEffect(sheet) {
        if (sheet == GroupSheet.Invite) refresh()
        onPauseOrDispose { }
    }
}

internal const val TAB_SUMMARY = 0
internal const val TAB_EXPENSES = 1
internal const val TAB_PEOPLE = 2

private val SummaryCardShape = RoundedCornerShape(25.dp)
private val FabHeight = 58.dp
private val FabElevation = 6.dp

/**
 * Stateless body of the group screen: top bar, member header, per-currency summary card, segmented tabs over
 * one of three panes (summary / expenses / people) that swap in place, the Add expense button and
 * whichever [sheet] is open. The scrolling content is one lazy list: header, tabs, then the pane, where the
 * expenses pane contributes one item per row so long histories compose only what is on screen.
 * [selectedCurrency] `null` means "not chosen yet" and falls back to [defaultBalanceCurrency]; [selectedTab]
 * is one of [TAB_SUMMARY], [TAB_EXPENSES], [TAB_PEOPLE].
 */
@Composable
internal fun GroupDetailContent(
    state: GroupDetailUiState,
    inviteLink: String?,
    relayStatuses: Map<String, RelayCheckStatus>,
    relayInfo: Map<String, RelayInfo>,
    selectedCurrency: String?,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    actions: GroupDetailActions,
    sheet: GroupSheet?,
    onSheet: (GroupSheet?) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val tokens = adaptiveSizeTokens()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { 3 })

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.scrollToPage(selectedTab)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectedTab) {
            onSelectTab(pagerState.currentPage)
        }
    }

    val summaryListState = rememberLazyListState()
    val expensesListState = rememberLazyListState()
    val peopleListState = rememberLazyListState()

    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var tabsHeightPx by remember { mutableFloatStateOf(0f) }
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < 0f && headerHeightPx > 0f) {
                    val newOffset = (headerOffsetPx + delta).coerceIn(-headerHeightPx, 0f)
                    val consumed = newOffset - headerOffsetPx
                    headerOffsetPx = newOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta > 0f && headerHeightPx > 0f) {
                    val newOffset = (headerOffsetPx + delta).coerceIn(-headerHeightPx, 0f)
                    val consumed = newOffset - headerOffsetPx
                    headerOffsetPx = newOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
        }
    }

    val currencies =
        remember(state.debts, state.expenses) { groupCurrencies(state.debts, state.expenses.map { it.expense }) }
    val defaultCurrency = remember(currencies, state.debts, state.myPubkey) {
        defaultBalanceCurrency(currencies, state.debts, state.myPubkey)
    }
    val currency = selectedCurrency?.takeIf { it in currencies } ?: defaultCurrency
    val expenses = rememberCurrencyExpenses(state, currency)

    val currentHeaderOffset = if (headerHeightPx > 0f) headerOffsetPx.coerceIn(-headerHeightPx, 0f) else 0f

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SfTopBar(
                title = state.groupName,
                onBack = actions.back,
                actions = {
                    SfTextButton(
                        text = stringResource(R.string.group_invite),
                        onClick = { onSheet(GroupSheet.Invite) },
                        modifier = Modifier.testTag("group_invite")
                    )
                    SfIconButton(
                        icon = Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(R.string.group_tools),
                        onClick = { onSheet(GroupSheet.Tools) },
                        modifier = Modifier.testTag("group_tools")
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val bottomInset = padding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .nestedScroll(nestedScrollConnection)
        ) {
            val totalHeaderDp = with(density) { (headerHeightPx + tabsHeightPx).toDp() }
            val listPadding = PaddingValues(
                top = totalHeaderDp,
                bottom = bottomInset + FabHeight + tokens.screenPaddingHorizontal * 2
            )

            HorizontalPager(
                state = pagerState,
                key = { it },
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    TAB_SUMMARY -> {
                        LazyColumn(
                            state = summaryListState,
                            contentPadding = listPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (pagerState.currentPage ==
                                        TAB_SUMMARY
                                    ) {
                                        Modifier.testTag("group_scroll")
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            item(key = "summary_content") {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = tokens.screenPaddingHorizontal)
                                ) {
                                    SummaryPane(
                                        state = state,
                                        currency = currency,
                                        onSettle = { onSheet(GroupSheet.Settle(it)) },
                                        onOpenExpense = { onSheet(GroupSheet.ExpenseDetail(it)) },
                                        onSeeAll = { scope.launch { pagerState.animateScrollToPage(TAB_EXPENSES) } }
                                    )
                                }
                            }
                        }
                    }
                    TAB_EXPENSES -> {
                        LazyColumn(
                            state = expensesListState,
                            contentPadding = listPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (pagerState.currentPage ==
                                        TAB_EXPENSES
                                    ) {
                                        Modifier.testTag("group_scroll")
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            expenseItems(
                                state = state,
                                expenses = expenses,
                                currency = currency,
                                horizontalPadding = tokens.screenPaddingHorizontal,
                                rowModifier = Modifier,
                                onOpenExpense = { onSheet(GroupSheet.ExpenseDetail(it)) }
                            )
                        }
                    }
                    TAB_PEOPLE -> {
                        LazyColumn(
                            state = peopleListState,
                            contentPadding = listPadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (pagerState.currentPage ==
                                        TAB_PEOPLE
                                    ) {
                                        Modifier.testTag("group_scroll")
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            item(key = "people_content") {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = tokens.screenPaddingHorizontal)
                                ) {
                                    PeoplePane(
                                        state = state,
                                        onInvite = { onSheet(GroupSheet.Invite) },
                                        onRemove = { onSheet(GroupSheet.RemoveMember(it)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Shared Collapsing Header & Sticky Tabs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .graphicsLayer {
                        translationY = currentHeaderOffset
                    }
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { headerHeightPx = it.height.toFloat() }
                ) {
                    Column(Modifier.padding(horizontal = tokens.screenPaddingHorizontal)) {
                        GroupHeader(state)
                        CurrencyLine(
                            currencies = currencies,
                            selected = currency,
                            onSelect = actions.selectCurrency,
                            modifier = Modifier.padding(top = 14.dp),
                            chipModifier = Modifier.testTag("group_currency"),
                            itemModifier = { Modifier.testTag("group_currency_$it") }
                        )
                        SummaryCard(state = state, currency = currency, onRetry = actions.retryBalances)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { tabsHeightPx = it.height.toFloat() }
                ) {
                    SegmentedTabs(
                        options = listOf(
                            stringResource(R.string.tab_balances),
                            stringResource(R.string.tab_expenses),
                            stringResource(R.string.tab_members)
                        ),
                        selectedIndex = pagerState.currentPage,
                        onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab) } },
                        positionProvider = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                        modifier = Modifier
                            .padding(horizontal = tokens.screenPaddingHorizontal)
                            .padding(top = 18.dp, bottom = 10.dp)
                            .testTag("group_tabs")
                    )
                }
            }

            SfAccentButton(
                text = stringResource(R.string.add_expense),
                onClick = actions.addExpense,
                leadingIcon = Icons.Outlined.Add,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(2f)
                    .padding(bottom = bottomInset)
                    .padding(end = tokens.screenPaddingHorizontal, bottom = tokens.screenPaddingHorizontal)
                    .shadow(FabElevation, MaterialTheme.shapes.large)
                    .width(IntrinsicSize.Max)
                    .heightIn(min = FabHeight)
                    .testTag("group_add_expense")
            )
        }
    }

    if (sheet != null) {
        GroupDetailSheetHost(
            sheet = sheet,
            state = state,
            inviteLink = inviteLink,
            relayStatuses = relayStatuses,
            relayInfo = relayInfo,
            actions = actions,
            onSheet = onSheet
        )
    }
}

/** Member stack + "N people · private group". */
@Composable
private fun GroupHeader(state: GroupDetailUiState) {
    val peopleCount = state.members.size.takeIf { it > 0 } ?: state.memberCount
    Row(modifier = Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberStack(pubkeys = state.members, names = state.memberNames, max = 4, size = 28.dp)
        if (state.members.isNotEmpty()) Spacer(Modifier.width(7.dp))
        Text(
            pluralStringResource(R.plurals.group_people_private, peopleCount, peopleCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * `primaryContainer` wash with eyebrow, signed net balance and an honest footnote. While balances are
 * unavailable the card gives way to a warning with a Retry action: no amount, no zero, no "settled".
 */
@Composable
private fun SummaryCard(state: GroupDetailUiState, currency: String?, onRetry: () -> Unit) {
    if (!state.balancesAvailable) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("group_summary_unavailable")) {
            WarningCard(text = stringResource(R.string.group_balances_unavailable_body))
            SfTextButton(
                text = stringResource(R.string.retry_balances),
                onClick = onRetry,
                modifier = Modifier.testTag("group_retry_balances")
            )
        }
        return
    }
    val net = currency?.let { myNetBalance(state.debts, it, state.myPubkey) } ?: 0L
    val direction =
        stringResource(
            when {
                net > 0 -> R.string.you_are_owed
                net < 0 -> R.string.you_owe
                else -> R.string.group_your_balance
            }
        )
    val note = summaryNote(state.debts, state.expenses.map { it.expense }, currency, state.myPubkey)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("group_summary"),
        shape = SummaryCardShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(Modifier.padding(22.dp)) {
            MiniLabel(
                text =
                if (currency != null) stringResource(R.string.dot_separated, direction, currency) else direction,
                color = MaterialTheme.colorScheme.primary
            )
            if (currency != null) {
                Spacer(Modifier.height(9.dp))
                SignedMoneyText(
                    amountMinor = net,
                    currency = currency,
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.testTag("group_summary_amount")
                )
                Spacer(Modifier.height(3.dp))
            } else {
                Spacer(Modifier.height(9.dp))
            }
            Text(
                summaryNoteText(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun summaryNoteText(note: SummaryNote): String = when (note) {
    SummaryNote.NoExpenses -> stringResource(R.string.no_expenses_yet)
    is SummaryNote.SettledIn -> stringResource(R.string.group_summary_settled_in, note.currency)
    is SummaryNote.NoCurrencyExpenses -> stringResource(R.string.group_summary_no_currency_expenses, note.currency)
    SummaryNote.OthersOpen -> stringResource(R.string.group_summary_others_open)
    is SummaryNote.Across ->
        pluralStringResource(R.plurals.group_summary_across, note.expenseCount, note.expenseCount)
}

/** Puts the invite link on the clipboard flagged sensitive, the same way Settings copies secrets. */
private fun copyInvite(context: Context, link: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(context.getString(R.string.group_invite), link)
    clip.description.extras = PersistableBundle().apply { putBoolean(CLIP_EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
}

private const val CLIP_EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

private fun shareInvite(context: Context, link: String) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.group_share_text, link))
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.group_share_chooser)))
}
