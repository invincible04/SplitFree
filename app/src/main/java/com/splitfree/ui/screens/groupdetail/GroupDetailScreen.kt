package com.splitfree.ui.screens.groupdetail

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.ui.util.adaptiveLayoutInfo
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.viewmodels.GroupDetailViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onAddExpense: (String) -> Unit,
    onNearbySync: (String) -> Unit = {},
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adaptive = adaptiveLayoutInfo()
    val tokens = adaptiveSizeTokens()
    val context = LocalContext.current
    var showSettleDialog by remember { mutableStateOf<DebtTransaction?>(null) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    var showQrDialog by remember { mutableStateOf(false) }
    val inviteLink by viewModel.inviteLink.collectAsStateWithLifecycle()
    var showShareWarning by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf<String?>(null) }
    var showRelayDialog by remember { mutableStateOf(false) }
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val relayInfo by viewModel.relayInfo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()

    LaunchedEffect(error) {
        error?.let {
            snackScope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.groupName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    val scope = rememberCoroutineScope()
                    IconButton(onClick = {
                        showRelayDialog = true
                        viewModel.checkAllRelays()
                    }) {
                        Icon(Icons.Default.CellTower, stringResource(R.string.relays))
                    }
                    IconButton(onClick = { onNearbySync(uiState.groupId) }) {
                        Icon(Icons.Default.Bluetooth, stringResource(R.string.nearby_sync))
                    }
                    IconButton(onClick = { showQrDialog = true }) {
                        Icon(Icons.Outlined.QrCode2, stringResource(R.string.show_qr))
                    }
                    IconButton(onClick = { showShareWarning = true }) {
                        Icon(Icons.Default.Share, stringResource(R.string.share_invite))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddExpense(uiState.groupId) },
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_expense)) },
                text = { Text(stringResource(R.string.add_expense)) }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val scope = rememberCoroutineScope()
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    listOf(
                        stringResource(R.string.tab_balances),
                        stringResource(R.string.tab_expenses),
                        stringResource(R.string.tab_members)
                    ).forEachIndexed { i, title ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = {
                                Text(
                                    text = title,
                                    style = if (adaptive.isCompact) {
                                        MaterialTheme.typography.labelLarge
                                    } else {
                                        MaterialTheme.typography.titleSmall
                                    }
                                )
                            }
                        )
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> {
                            BalancesTab(
                                uiState.debts,
                                hasExpenses = uiState.expenses.isNotEmpty(),
                                myPubkey = uiState.myPubkey,
                                memberNames = uiState.memberNames,
                                onSettle = { showSettleDialog = it }
                            )
                        }

                        1 -> {
                            ExpensesTab(uiState.expenses, memberNames = uiState.memberNames)
                        }

                        2 -> {
                            MembersTab(
                                members = uiState.members,
                                createdBy = uiState.createdBy,
                                isCreator =
                                uiState.myPubkey == uiState.createdBy,
                                myPubkey = uiState.myPubkey,
                                memberNames = uiState.memberNames,
                                onRemove = { showRemoveDialog = it }
                            )
                        }
                    }
                }
            }
            SmallFloatingActionButton(
                onClick = { showShareWarning = true },
                modifier = Modifier.align(Alignment.BottomStart).padding(tokens.screenPaddingHorizontal),
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) { Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.invite_members)) }
        }
    }

    // Dialogs
    if (showShareWarning) {
        ShareWarningDialog(
            inviteLink = inviteLink,
            onShare = { link ->
                val shareText = "Join my SplitFree group!\n\n" +
                    "1. Install SplitFree (if you haven't already)\n" +
                    "2. Copy the link below\n" +
                    "3. Open SplitFree → tap the 📋 clipboard icon (top right)\n\n" +
                    link
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                context.startActivity(Intent.createChooser(intent, "Share invite"))
            },
            onDismiss = { showShareWarning = false }
        )
    }
    if (showQrDialog) {
        QrDialog(inviteLink = inviteLink, groupName = uiState.groupName, onDismiss = { showQrDialog = false })
    }
    showSettleDialog?.let { debt ->
        SettleDialog(
            debt = debt,
            memberNames = uiState.memberNames,
            onConfirm = {
                viewModel.recordSettlement(debt)
                showSettleDialog = null
            },
            onDismiss = { showSettleDialog = null }
        )
    }
    showRemoveDialog?.let { pubkey ->
        RemoveMemberDialog(
            pubkey = pubkey,
            memberNames = uiState.memberNames,
            onConfirm = {
                showRemoveDialog = null
                viewModel.removeMember(pubkey)
            },
            onDismiss = { showRemoveDialog = null }
        )
    }
    if (showRelayDialog) {
        RelayDialog(
            relays = uiState.relays,
            relayStatuses = relayStatuses,
            relayInfo = relayInfo,
            isCreator = uiState.myPubkey == uiState.createdBy,
            onAdd = viewModel::addRelay,
            onRemove = viewModel::removeRelay,
            onCheck = viewModel::checkRelay,
            onSave = viewModel::saveRelays,
            onDismiss = { showRelayDialog = false }
        )
    }
}
