package com.splitfree.ui.screens.expense

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitfree.R
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.ui.components.EmptyState
import com.splitfree.ui.components.SfBottomDock
import com.splitfree.ui.components.SfPrimaryButton
import com.splitfree.ui.components.SfSecondaryButton
import com.splitfree.ui.components.SfSheet
import com.splitfree.ui.components.SfSheetFooter
import com.splitfree.ui.components.SfTextButton
import com.splitfree.ui.components.SfTopBar
import com.splitfree.ui.theme.splitFree
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.adaptiveSizeTokens
import com.splitfree.ui.util.asString
import com.splitfree.ui.viewmodels.AddExpenseUiState
import com.splitfree.ui.viewmodels.AddExpenseViewModel

/** Widest the form and the dock's controls grow on tablets; both are centred within the window. */
internal val ExpenseFormMaxWidth: Dp = 600.dp

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
    val horizontal = adaptiveSizeTokens().screenPaddingHorizontal

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SfTopBar(
                title = stringResource(if (state.editing) R.string.expense_edit_title else R.string.add_expense),
                onBack = requestBack,
                backEnabled = !state.saving,
                backModifier = Modifier.testTag("expense_back")
            )
        },
        bottomBar = {
            if (!state.loading && state.loadingError == null) {
                SfBottomDock {
                    // The dock already pads by the screen token; cap the controls to the form's field width.
                    Column(
                        Modifier
                            .widthIn(max = ExpenseFormMaxWidth - horizontal * 2)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExpenseDockContent(state, enabled, actions)
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
                state.loading -> ExpenseLoading()
                state.loadingError != null -> ExpenseLoadingError(state.loadingError, actions.retry)
                else -> ExpenseForm(state, enabled, actions, horizontal) { sheet = it }
            }
        }
    }
    if (sheet != null && enabled) {
        ExpenseEditorSheet(sheet!!, state, actions, onDismiss = { sheet = null })
    }
    if (confirmDiscard) {
        ExpenseDiscardSheet(
            editing = state.editing,
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                actions.back()
            }
        )
    }
}

@Composable
private fun ColumnScope.ExpenseDockContent(state: AddExpenseUiState, enabled: Boolean, actions: ExpenseEditorActions) {
    if (state.error != null) {
        Column(Modifier.fillMaxWidth().testTag("expense_save_error")) {
            EditorError(state.error)
            if (!enabled && !state.saving && !state.saved) {
                SfTextButton(text = stringResource(R.string.expense_retry), onClick = actions.retry)
            }
        }
    }
    SfPrimaryButton(
        // While saving the label is hidden behind the ring but stays in the button's semantics.
        text =
        stringResource(
            when {
                state.saving -> R.string.expense_saving
                state.editing -> R.string.expense_save_changes
                else -> R.string.expense_save
            }
        ),
        onClick = actions.save,
        enabled = enabled,
        loading = state.saving,
        modifier = Modifier.testTag("expense_save")
    )
    Text(
        stringResource(R.string.expense_local_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.splitFree.faint,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ExpenseLoading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.expense_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = adaptiveSizeTokens().screenPaddingHorizontal)
        )
    }
}

@Composable
private fun ExpenseLoadingError(error: UiMessage, onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = error.asString(),
        body = stringResource(R.string.expense_load_error_hint),
        modifier = Modifier.widthIn(max = ExpenseFormMaxWidth)
    ) {
        SfSecondaryButton(text = stringResource(R.string.expense_retry), onClick = onRetry)
    }
}

@Composable
private fun ExpenseForm(
    state: AddExpenseUiState,
    enabled: Boolean,
    actions: ExpenseEditorActions,
    horizontal: Dp,
    onOpenSheet: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = ExpenseFormMaxWidth).fillMaxSize().testTag("expense_form"),
        contentPadding = PaddingValues(start = horizontal, end = horizontal, top = 2.dp, bottom = 24.dp)
    ) {
        item {
            ExpenseEditorContext(state)
            Spacer(Modifier.height(22.dp))
        }
        item {
            ExpenseAmountField(state, enabled, actions.amount) { onOpenSheet("currency") }
            Spacer(Modifier.height(20.dp))
        }
        item {
            ExpenseDescriptionField(state, enabled, actions.description)
            Spacer(Modifier.height(18.dp))
        }
        item {
            ExpenseSummaryRows(
                state,
                enabled,
                onPayer = { onOpenSheet("payer") },
                onSplit = { onOpenSheet("split") },
                onCategory = { onOpenSheet("category") }
            )
            Spacer(Modifier.height(15.dp))
        }
        item { ExpenseSplitPreviewCard(state, enabled) { onOpenSheet("split") } }
    }
}

/** The discard question, one line of consequence copy, Keep editing / Discard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDiscardSheet(editing: Boolean, onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    SfSheet(
        onDismiss = onKeepEditing,
        title = stringResource(if (editing) R.string.expense_discard_changes_title else R.string.expense_discard_title)
    ) {
        Text(
            stringResource(R.string.expense_discard_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SfSheetFooter(
            secondary = {
                SfSecondaryButton(
                    text = stringResource(R.string.expense_keep_editing),
                    onClick = onKeepEditing,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            primary = { SfPrimaryButton(text = stringResource(R.string.expense_discard), onClick = onDiscard) }
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
