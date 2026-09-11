package com.splitfree.ui.screens.expense

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.AddExpenseUiState
import com.splitfree.ui.viewmodels.AddExpenseViewModel

/** Route owns lifecycle and navigation; the editor below has no Android/service dependencies. */
@Composable
fun AddExpenseScreen(onExpenseAdded: () -> Unit, onBack: () -> Unit, viewModel: AddExpenseViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onExpenseAdded() }
    AddExpenseContent(
        state = state,
        actions = ExpenseEditorActions(
            amount = viewModel::updateAmount,
            description = viewModel::updateDescription,
            currency = viewModel::updateCurrency,
            payer = viewModel::updatePayer,
            category = viewModel::updateCategory,
            splitType = viewModel::updateSplitType,
            memberInput = viewModel::updateMemberInput,
            participant = viewModel::toggleParticipant,
            save = viewModel::submit,
            retry = viewModel::retryLoad,
            back = onBack
        )
    )
}

/** User intentions only, making screen fixtures and interaction tests independent of Hilt. */
data class ExpenseEditorActions(
    val amount: (String) -> Unit = {},
    val description: (String) -> Unit = {},
    val currency: (String) -> Unit = {},
    val payer: (String) -> Unit = {},
    val category: (String) -> Unit = {},
    val splitType: (SplitType) -> Unit = {},
    val memberInput: (String, String) -> Unit = { _, _ -> },
    val participant: (String) -> Unit = {},
    val save: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseContent(state: AddExpenseUiState, actions: ExpenseEditorActions) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val enabled = state.editable && !state.saving && !state.saved
    val requestBack = {
        if (!state.saving) {
            if (state.dirty && !state.saved) confirmDiscard = true else actions.back()
        }
    }
    BackHandler(enabled = sheet == null) { requestBack() }
    LaunchedEffect(enabled) { if (!enabled) sheet = null }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_expense), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(
                        onClick = requestBack,
                        enabled = !state.saving,
                        modifier = Modifier.testTag("expense_back")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        },
        bottomBar = {
            if (!state.loading && state.loadingError == null) {
                Surface(tonalElevation = 1.dp) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.navigationBarsPadding().widthIn(
                                max = 600.dp
                            ).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (state.error != null) {
                                Column(Modifier.fillMaxWidth().testTag("expense_save_error")) {
                                    EditorError(state.error)
                                    if (!enabled && !state.saving && !state.saved) {
                                        TextButton(onClick = actions.retry) {
                                            Text(stringResource(R.string.expense_retry))
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = actions.save,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("expense_save"),
                                shape = MaterialTheme.shapes.large,
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (state.saving) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            if (state.saving) R.string.expense_saving else R.string.expense_save
                                        )
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.expense_local_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                state.loading -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.expense_loading), Modifier.padding(20.dp))
                }
                state.loadingError != null -> Column(
                    Modifier.widthIn(max = 600.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    EditorError(state.loadingError)
                    Button(onClick = actions.retry) { Text(stringResource(R.string.expense_retry)) }
                }
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().testTag("expense_form"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item {
                        Text(
                            state.groupName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item { ExpenseAmountField(state, enabled, actions.amount) { sheet = "currency" } }
                    item { ExpenseDescriptionField(state, enabled, actions.description) }
                    item {
                        ExpenseSummaryRows(
                            state,
                            enabled,
                            onPayer = { sheet = "payer" },
                            onSplit = { sheet = "split" },
                            onCategory = { sheet = "category" }
                        )
                    }
                    item { ExpenseSplitPreview(state) }
                }
            }
        }
    }
    if (sheet != null && enabled) {
        ExpenseEditorSheet(sheet!!, state, actions, onDismiss = { sheet = null })
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.expense_discard_title)) },
            text = { Text(stringResource(R.string.expense_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    actions.back()
                }) { Text(stringResource(R.string.expense_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.expense_keep_editing)) }
            }
        )
    }
}

@Composable
internal fun EditorError(message: UiMessage) {
    Text(
        message.asString(),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
