package com.splitfree.ui.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.money.ExpenseCurrencyCatalog
import com.splitfree.domain.money.ExpenseInputParser
import com.splitfree.domain.money.ExpenseSplitCalculator
import com.splitfree.domain.money.ExpenseSplitPreview
import com.splitfree.domain.repository.ExpenseCorrectionCommand
import com.splitfree.domain.repository.ExpenseRevisionConflictException
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.domain.usecase.expense.CorrectExpenseUseCase
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.toUiMessage
import com.splitfree.ui.viewmodels.expense.ExpenseDraft
import com.splitfree.ui.viewmodels.expense.ExpenseDraftSeeder
import com.splitfree.ui.viewmodels.expense.ExpenseDraftStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Screen state for the expense editor. Every message field is a [UiMessage]: ViewModel-produced text
 * is a string resource, while domain validation messages pass through as [UiMessage.Raw].
 *
 * @property editing true when the editor corrects an existing expense instead of adding a new one.
 */
data class AddExpenseUiState(
    val editing: Boolean = false,
    val loading: Boolean = true,
    val loadingError: UiMessage? = null,
    val groupName: String = "",
    val members: List<String> = emptyList(),
    val memberNames: Map<String, String> = emptyMap(),
    val myPubkey: String = "",
    val amount: String = "",
    val description: String = "",
    val currency: String = "INR",
    val paidBy: String = "",
    val category: String = "",
    val splitType: SplitType = SplitType.EQUAL,
    val memberInputs: Map<String, String> = emptyMap(),
    val participants: Set<String> = emptySet(),
    val previewSplits: List<SplitEntry> = emptyList(),
    val remaining: Long? = null,
    val amountError: UiMessage? = null,
    val descriptionError: UiMessage? = null,
    val splitError: UiMessage? = null,
    val error: UiMessage? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val dirty: Boolean = false,
    val editable: Boolean = false
)

/**
 * Editor for a new expense or a correction of an existing one.
 *
 * Every save is a durable command: the draft carries the command id (and, for an edit, the revision it was
 * seeded from) in saved state, so a save interrupted by process death is recognised on restore instead of
 * being issued twice, and an edit whose expense changed underneath fails closed.
 */
@HiltViewModel
class AddExpenseViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val addExpense: AddExpenseUseCase,
    private val correctExpense: CorrectExpenseUseCase,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""

    private val editingExpenseId: String? = savedStateHandle["expenseId"]
    private val editingAuthorPubkey: String? = savedStateHandle["authorPubkey"]
    private val editing: Boolean get() = editingExpenseId != null || editingAuthorPubkey != null
    private val editingIdentity: ExpenseIdentity? = editingExpenseId?.takeIf { it.isNotBlank() }?.let { uuid ->
        editingAuthorPubkey?.takeIf { it.isNotBlank() }?.let { ExpenseIdentity(it, uuid) }
    }
    private val store = ExpenseDraftStore(savedStateHandle)
    private var draftLoadError: UiMessage? = null
    private var recoveryError: UiMessage? = null
    private var draft = try {
        store.read(editingExpenseId = editingIdentity?.expenseUuid)
    } catch (_: IllegalArgumentException) {
        draftLoadError = UiMessage.Res(R.string.expense_draft_unrestorable)
        ExpenseDraft(expenseId = "", createdAt = 0, localeTag = Locale.getDefault().toLanguageTag(), commandId = "")
    }
    private val parser = ExpenseInputParser(Locale.forLanguageTag(draft.localeTag))
    private val calculator = ExpenseSplitCalculator(parser)
    private val seeder = ExpenseDraftSeeder(parser, calculator, Locale.forLanguageTag(draft.localeTag))
    private var mustRecover = store.restored || draft.needsRecovery
    private var group: Group? = null
    private var attempted = false
    private var loadJob: Job? = null
    private var observing: Job? = null
    private val _uiState = MutableStateFlow(AddExpenseUiState(editing = editing))
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        if (draftLoadError == null) store.write(draft)
        render()
        retryLoad()
    }

    fun updateAmount(value: String) = edit { copy(amount = value.take(ExpenseInputParser.MAX_INPUT_LENGTH + 1)) }

    fun updateDescription(value: String) = edit { copy(description = value.take(MAX_DESCRIPTION_LENGTH + 1)) }

    /** ISO 4217 codes are exactly three letters; anything longer is a typo that can never validate. */
    fun updateCurrency(value: String) = edit {
        copy(currency = value.take(ExpenseCurrencyCatalog.CODE_LENGTH).uppercase(Locale.ROOT))
    }

    fun updatePayer(value: String) {
        if (value in _uiState.value.members) edit { copy(paidBy = value) }
    }

    fun updateCategory(value: String) = edit { copy(category = value.take(100)) }

    fun updateSplitType(value: SplitType) = edit { copy(splitType = value) }

    fun updateMemberInput(pubkey: String, value: String) {
        if (pubkey !in _uiState.value.members && pubkey !in draft.participants) return
        edit {
            val values = inputs[splitType].orEmpty() + (pubkey to value.take(ExpenseInputParser.MAX_INPUT_LENGTH + 1))
            copy(inputs = inputs + (splitType to values), automaticInputModes = automaticInputModes - splitType)
        }
    }

    fun toggleParticipant(pubkey: String) {
        if (pubkey !in _uiState.value.members && pubkey !in draft.participants) return
        edit {
            copy(participants = if (pubkey in participants) participants - pubkey else participants + pubkey)
        }
    }

    private fun edit(update: ExpenseDraft.() -> ExpenseDraft) {
        if (!_uiState.value.editable) return
        val updated = draft.update()
        if (updated == draft) return
        draft = seeder.seedDefaults(updated).copy(dirty = true)
        store.write(draft)
        render(_uiState.value.copy(error = null))
    }

    fun retryLoad() {
        if (draftLoadError != null) {
            render(_uiState.value.copy(loading = false, loadingError = draftLoadError))
            return
        }
        if (_uiState.value.saving || _uiState.value.saved || loadJob?.isActive == true) return
        observing?.cancel()
        render(_uiState.value.copy(loading = true, loadingError = null, editable = false))
        loadJob = viewModelScope.launch {
            try {
                val pubkey = currentAuthor()
                if (!recoverIfNeeded()) return@launch
                val current = groupRepo.getById(groupId) ?: throw groupUnavailable()
                if (editing && !draft.initialized) seedFromExpense(pubkey)
                applyGroup(current, pubkey)
                observeGroup()
            } catch (e: CancellationException) {
                render(
                    _uiState.value.copy(
                        loading = false,
                        loadingError =
                        recoveryError ?: UiMessage.Res(R.string.expense_load_interrupted)
                    )
                )
                throw e
            } catch (e: Exception) {
                render(
                    _uiState.value.copy(
                        loading = false,
                        loadingError = e.uiMessage(R.string.expense_group_load_failed),
                        editable = false
                    )
                )
            }
        }
    }

    private fun applyGroup(current: Group, pubkey: String = _uiState.value.myPubkey) {
        group = current
        if (!draft.initialized) {
            draft =
                draft.copy(
                    paidBy = pubkey,
                    authorPubkey = pubkey,
                    participants = current.members.toSet(),
                    initialized = true
                )
        }
        draft = seeder.seedDefaults(draft)
        store.write(draft)
        render(
            _uiState.value.copy(
                loading = false,
                loadingError = null,
                groupName = current.name,
                members = current.members.distinct(),
                memberNames = _uiState.value.memberNames + current.memberNames,
                myPubkey = pubkey
            )
        )
    }

    /**
     * Fills the draft from the current revision of the expense being edited and pins that revision; only its
     * author may open it, mirroring the protocol.
     */
    private suspend fun seedFromExpense(pubkey: String) {
        val selected = editingIdentity ?: throw UiMessageException(UiMessage.Res(R.string.expense_edit_missing))
        val authored = try {
            correctExpense.getEditableExpense(groupId, selected.expenseUuid, selected.authorPubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UiMessageException(e.toUiMessage(R.string.expense_edit_load_failed))
        } ?: throw UiMessageException(UiMessage.Res(R.string.expense_edit_missing))
        if (authored.identity != selected) throw UiMessageException(UiMessage.Res(R.string.expense_edit_missing))
        if (authored.authorPubkey != pubkey) throw UiMessageException(UiMessage.Res(R.string.expense_edit_not_author))
        draft = try {
            seeder.seed(draft, authored.expense, pubkey).copy(expectedRevisionId = authored.revisionId)
        } catch (e: IllegalArgumentException) {
            throw UiMessageException(e.toUiMessage(R.string.expense_edit_load_failed))
        }
        store.write(draft)
    }

    private fun observeGroup() {
        observing = viewModelScope.launch {
            try {
                groupRepo.observeById(groupId).collect { current ->
                    if (current == null) {
                        group = null
                        render(
                            _uiState.value.copy(
                                loadingError = UiMessage.Res(R.string.expense_group_unavailable),
                                editable = false
                            )
                        )
                    } else {
                        applyGroup(current)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                render(
                    _uiState.value.copy(
                        loadingError = e.uiMessage(R.string.expense_group_update_failed),
                        editable = false
                    )
                )
            }
        }
    }

    fun submit() {
        if (!_uiState.value.editable) return
        attempted = true
        render()
        if (!valid()) return
        render(_uiState.value.copy(saving = true, error = null, editable = false))
        viewModelScope.launch {
            try {
                val current = groupRepo.getById(groupId) ?: throw groupUnavailable()
                applyGroup(current, currentAuthor())
                if (!valid()) return@launch
                // The command is durable before it is issued, so an interruption anywhere below is recovered.
                draft = draft.copy(needsRecovery = true)
                mustRecover = true
                store.write(draft)
                val selected = editingIdentity
                if (selected != null) {
                    correctExpense(
                        groupId = groupId,
                        originalId = selected.expenseUuid,
                        amount = parser.money(draft.amount, draft.currency),
                        currency = draft.currency,
                        description = draft.description.trim(),
                        paidBy = draft.paidBy,
                        splitType = draft.splitType,
                        splitAmong = _uiState.value.previewSplits,
                        timestamp = draft.createdAt,
                        category = draft.category,
                        expectedAuthorPubkey = selected.authorPubkey,
                        command = correctionCommand()
                    )
                } else {
                    addExpense(
                        groupId = groupId,
                        amount = parser.money(draft.amount, draft.currency),
                        currency = draft.currency,
                        description = draft.description.trim(),
                        paidBy = draft.paidBy,
                        splitType = draft.splitType,
                        splitAmong = _uiState.value.previewSplits,
                        category = draft.category,
                        expenseId = draft.expenseId,
                        createdAt = draft.createdAt,
                        expectedAuthorPubkey = draft.authorPubkey
                    )
                }
                markSaved()
            } catch (e: CancellationException) {
                if (mustRecover) {
                    recoveryError = UiMessage.Res(R.string.expense_save_interrupted)
                }
                throw e
            } catch (e: Exception) {
                render(_uiState.value.copy(error = e.uiMessage(R.string.expense_save_failed)))
                recoverIfNeeded()
            } finally {
                render(_uiState.value.copy(saving = false))
            }
        }
    }

    /**
     * Settles an outstanding command before the editor opens: a saved command marks the draft saved, a
     * command that never committed releases the draft, and an edit whose expense moved to another revision
     * stays locked. Returns true when editing may proceed.
     */
    private suspend fun recoverIfNeeded(): Boolean {
        if (!mustRecover) return true
        return try {
            currentAuthor()
            val saved = if (editing) {
                if (!draft.initialized) {
                    // The draft was never seeded, so no edit command can have been issued.
                    mustRecover = false
                    return true
                }
                val selected = checkNotNull(editingIdentity)
                correctExpense.getSavedCorrection(
                    groupId,
                    selected.expenseUuid,
                    draft.authorPubkey,
                    correctionCommand()
                )
            } else {
                addExpense.getSavedExpense(groupId, draft.expenseId, draft.authorPubkey.takeIf { draft.initialized })
            }
            if (saved != null) {
                val expected = expectedExpense()
                if (expected == null || saved.copy(splitAmong = saved.splitAmong.sortedBy { it.pubkey }) != expected) {
                    recoveryError = UiMessage.Res(R.string.expense_saved_differently)
                    render(_uiState.value.copy(loading = false))
                } else {
                    markSaved()
                }
                false
            } else {
                if (editing) requireDraftRevision()
                mustRecover = false
                recoveryError = null
                draft = draft.copy(needsRecovery = false)
                store.write(draft)
                true
            }
        } catch (e: CancellationException) {
            recoveryError = UiMessage.Res(R.string.expense_save_check_interrupted)
            render(_uiState.value.copy(loading = false))
            throw e
        } catch (e: Exception) {
            recoveryError = when (e) {
                is UiMessageException -> e.uiMessage
                is ExpenseRevisionConflictException -> UiMessage.Res(R.string.expense_edit_stale)
                else -> UiMessage.Res(R.string.expense_save_check_failed)
            }
            render(
                _uiState.value.copy(
                    loading = false,
                    loadingError = recoveryError,
                    editable = false
                )
            )
            false
        }
    }

    /** An unsaved edit may only continue while its expense is still at the revision it was seeded from. */
    private suspend fun requireDraftRevision() {
        val selected = checkNotNull(editingIdentity)
        val current = correctExpense.getEditableExpense(groupId, selected.expenseUuid, draft.authorPubkey)
        if (current?.revisionId != draft.expectedRevisionId) throw ExpenseRevisionConflictException()
    }

    private fun correctionCommand() = ExpenseCorrectionCommand(
        draft.commandId,
        draft.expectedRevisionId ?: throw ExpenseRevisionConflictException()
    )

    private fun currentAuthor(): String {
        val pubkey = identity.getPublicKeyHex()
        if (editing) {
            val selected = editingIdentity ?: throw UiMessageException(UiMessage.Res(R.string.expense_edit_missing))
            if (selected.authorPubkey != pubkey) {
                throw UiMessageException(UiMessage.Res(R.string.expense_edit_not_author))
            }
            if (draft.initialized &&
                (draft.expenseId != selected.expenseUuid || draft.authorPubkey != selected.authorPubkey)
            ) {
                throw UiMessageException(UiMessage.Res(R.string.expense_draft_unrestorable))
            }
        }
        if (draft.initialized && draft.authorPubkey != pubkey) {
            val changed = UiMessage.Res(R.string.expense_identity_changed)
            recoveryError = changed
            render(_uiState.value.copy(loading = false))
            throw UiMessageException(changed)
        }
        return pubkey
    }

    private fun expectedExpense(): Expense? = try {
        val amount = parser.money(draft.amount, draft.currency)
        val preview = calculator.calculate(
            amount,
            draft.currency,
            draft.splitType,
            draft.participants,
            draft.inputs[draft.splitType].orEmpty()
        )
        if (preview.error != null) {
            null
        } else {
            Expense(
                id = draft.expenseId,
                amount = amount,
                currency = draft.currency.trim().uppercase(Locale.ROOT),
                description = draft.description.trim(),
                paidBy = draft.paidBy,
                splitType = draft.splitType,
                splitAmong = preview.splits,
                timestamp = draft.createdAt,
                category = draft.category
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun markSaved() {
        recoveryError = null
        draft = draft.copy(dirty = false)
        store.write(draft)
        render(_uiState.value.copy(loading = false, saved = true, error = null, loadingError = null, editable = false))
    }

    private fun valid(): Boolean = _uiState.value.let { state ->
        state.amountError == null &&
            state.descriptionError == null &&
            state.splitError == null &&
            state.previewSplits.isNotEmpty() &&
            state.loadingError == null
    }

    private fun render(base: AddExpenseUiState = _uiState.value) {
        var amountError: UiMessage? = null
        val amount = try {
            val parsed = parser.money(draft.amount, draft.currency)
            when {
                parsed <= 0 -> {
                    amountError = UiMessage.Res(R.string.expense_amount_positive)
                    null
                }
                parsed > ExpenseInputParser.MAX_EXPENSE_AMOUNT -> {
                    amountError = UiMessage.Res(R.string.expense_amount_too_large)
                    null
                }
                else -> parsed
            }
        } catch (e: IllegalArgumentException) {
            // Parser messages are domain text and are shown as-is.
            amountError = e.toUiMessage(R.string.expense_amount_invalid)
            null
        }
        if (!attempted && draft.amount.isEmpty()) amountError = null
        val descriptionError = when {
            attempted && draft.description.isBlank() -> UiMessage.Res(R.string.expense_description_required)
            draft.description.length > MAX_DESCRIPTION_LENGTH ->
                UiMessage.Plural(R.plurals.expense_description_too_long, MAX_DESCRIPTION_LENGTH, MAX_DESCRIPTION_LENGTH)
            else -> null
        }
        val inputs = draft.inputs[draft.splitType].orEmpty()
        val split =
            amount?.let { calculator.calculate(it, draft.currency, draft.splitType, draft.participants, inputs) }
                ?: ExpenseSplitPreview()
        val splitError = if (group == null) {
            null
        } else {
            when {
                base.myPubkey !in base.members -> UiMessage.Res(R.string.expense_not_member)
                draft.paidBy !in base.members -> UiMessage.Res(R.string.expense_payer_left)
                draft.participants.any { it !in base.members } -> UiMessage.Res(R.string.expense_participant_left)
                draft.participants.isEmpty() -> UiMessage.Res(R.string.expense_select_participant)
                else -> split.error?.let(UiMessage::Raw)
            }
        }
        val loadingError = draftLoadError ?: recoveryError ?: if (draft.initialized &&
            base.myPubkey.isNotEmpty() &&
            draft.authorPubkey != base.myPubkey
        ) {
            UiMessage.Res(R.string.expense_identity_changed)
        } else {
            base.loadingError
        }
        _uiState.value = base.copy(
            loadingError = loadingError,
            amount = draft.amount,
            description = draft.description,
            currency = draft.currency,
            paidBy = draft.paidBy,
            category = draft.category,
            splitType = draft.splitType,
            memberInputs = inputs,
            participants = draft.participants,
            previewSplits = split.splits,
            remaining = split.remaining,
            amountError = amountError,
            descriptionError = descriptionError,
            splitError = splitError,
            dirty = draft.dirty,
            editable =
            !base.loading && loadingError == null && !base.saving && !base.saved && !mustRecover && group != null
        )
    }

    private fun groupUnavailable() = UiMessageException(UiMessage.Res(R.string.expense_group_unavailable))

    companion object {
        private const val MAX_DESCRIPTION_LENGTH = 500
    }
}

/** Carries a resource-backed message through this ViewModel's exception-based control flow. */
private class UiMessageException(val uiMessage: UiMessage) : IllegalStateException()

private fun Exception.uiMessage(@StringRes fallback: Int): UiMessage =
    (this as? UiMessageException)?.uiMessage ?: toUiMessage(fallback)
