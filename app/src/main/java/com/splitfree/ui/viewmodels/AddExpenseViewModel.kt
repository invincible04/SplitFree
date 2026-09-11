package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.money.ExpenseInputParser
import com.splitfree.domain.money.ExpenseSplitCalculator
import com.splitfree.domain.money.ExpenseSplitPreview
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import com.splitfree.ui.viewmodels.expense.ExpenseDraft
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

data class AddExpenseUiState(
    val loading: Boolean = true,
    val loadingError: String? = null,
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
    val amountError: String? = null,
    val descriptionError: String? = null,
    val splitError: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val dirty: Boolean = false,
    val editable: Boolean = false
)

@HiltViewModel
class AddExpenseViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val addExpense: AddExpenseUseCase,
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityContract
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""
    private val store = ExpenseDraftStore(savedStateHandle)
    private var draftLoadError: String? = null
    private var recoveryError: String? = null
    private var draft = try {
        store.read()
    } catch (_: IllegalArgumentException) {
        draftLoadError = "This draft could not be restored safely. Close it and start a new expense."
        ExpenseDraft(expenseId = "", createdAt = 0, localeTag = Locale.getDefault().toLanguageTag())
    }
    private val parser = ExpenseInputParser(Locale.forLanguageTag(draft.localeTag))
    private val calculator = ExpenseSplitCalculator(parser)
    private var mustRecover = store.restored || draft.needsRecovery
    private var group: Group? = null
    private var attempted = false
    private var loadJob: Job? = null
    private var observing: Job? = null
    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        if (draftLoadError == null) store.write(draft)
        render()
        retryLoad()
    }

    fun updateAmount(value: String) = edit { copy(amount = value.take(ExpenseInputParser.MAX_INPUT_LENGTH + 1)) }

    fun updateDescription(value: String) = edit { copy(description = value.take(MAX_DESCRIPTION_LENGTH + 1)) }

    fun updateCurrency(value: String) = edit { copy(currency = value.take(4).uppercase(Locale.ROOT)) }

    fun updatePayer(value: String) {
        if (value in _uiState.value.members) edit { copy(paidBy = value) }
    }

    fun updateCategory(value: String) = edit { copy(category = value.take(100)) }

    fun updateSplitType(value: SplitType) = edit { copy(splitType = value) }

    fun updateMemberInput(pubkey: String, value: String) {
        if (pubkey !in _uiState.value.members && pubkey !in draft.participants) return
        edit {
            val values = inputs[splitType].orEmpty() + (pubkey to value.take(ExpenseInputParser.MAX_INPUT_LENGTH + 1))
            copy(inputs = inputs + (splitType to values))
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
        draft = updated.copy(dirty = true)
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
                val current = groupRepo.getById(groupId) ?: error("This group is no longer available")
                applyGroup(current, pubkey)
                observeGroup()
            } catch (e: CancellationException) {
                render(
                    _uiState.value.copy(
                        loading = false,
                        loadingError =
                        recoveryError ?: "Loading was interrupted. Retry to load this group."
                    )
                )
                throw e
            } catch (e: Exception) {
                render(
                    _uiState.value.copy(
                        loading = false,
                        loadingError = e.message ?: "Could not load this group",
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
            store.write(draft)
        }
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

    private fun observeGroup() {
        observing = viewModelScope.launch {
            try {
                groupRepo.observeById(groupId).collect { current ->
                    if (current == null) {
                        group = null
                        render(
                            _uiState.value.copy(loadingError = "This group is no longer available", editable = false)
                        )
                    } else {
                        applyGroup(current)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                render(_uiState.value.copy(loadingError = e.message ?: "Could not update this group", editable = false))
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
                val current = groupRepo.getById(groupId) ?: error("This group is no longer available")
                applyGroup(current, currentAuthor())
                if (!valid()) return@launch
                draft = draft.copy(needsRecovery = true)
                mustRecover = true
                store.write(draft)
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
                markSaved()
            } catch (e: CancellationException) {
                if (mustRecover) {
                    recoveryError =
                        "Save was interrupted. Retry to check whether it was saved before editing."
                }
                throw e
            } catch (e: Exception) {
                render(_uiState.value.copy(error = e.message ?: "Could not save this expense"))
                recoverIfNeeded()
            } finally {
                render(_uiState.value.copy(saving = false))
            }
        }
    }

    private suspend fun recoverIfNeeded(): Boolean {
        if (!mustRecover) return true
        return try {
            currentAuthor()
            val saved = addExpense.getSavedExpense(
                groupId,
                draft.expenseId,
                draft.authorPubkey.takeIf {
                    draft.initialized
                }
            )
            if (saved != null) {
                val expected = expectedExpense()
                if (expected == null || saved.copy(splitAmong = saved.splitAmong.sortedBy { it.pubkey }) != expected) {
                    recoveryError =
                        "This expense was saved with different details. Your draft is unchanged. Close it and review the saved expense."
                    render(_uiState.value.copy(loading = false))
                } else {
                    markSaved()
                }
                false
            } else {
                mustRecover = false
                recoveryError = null
                draft = draft.copy(needsRecovery = false)
                store.write(draft)
                true
            }
        } catch (e: CancellationException) {
            recoveryError = "Save check was interrupted. Retry before editing."
            render(_uiState.value.copy(loading = false))
            throw e
        } catch (_: Exception) {
            recoveryError = "Could not confirm whether this expense was saved. Retry before editing."
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

    private fun currentAuthor(): String {
        val pubkey = identity.getPublicKeyHex()
        if (draft.initialized && draft.authorPubkey != pubkey) {
            recoveryError = "Your identity changed. Close this draft and start a new expense."
            render(_uiState.value.copy(loading = false))
            error(checkNotNull(recoveryError))
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
        var amountError: String? = null
        val amount = try {
            parser.money(draft.amount, draft.currency).also {
                require(it > 0) { "Enter an amount greater than zero" }
                require(it <= ExpenseInputParser.MAX_EXPENSE_AMOUNT) { "Amount exceeds the maximum allowed" }
            }
        } catch (e: IllegalArgumentException) {
            if (attempted || draft.amount.isNotEmpty()) amountError = e.message
            null
        }
        val descriptionError = when {
            attempted && draft.description.isBlank() -> "Enter a description"
            draft.description.length > MAX_DESCRIPTION_LENGTH -> "Use at most $MAX_DESCRIPTION_LENGTH characters"
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
                base.myPubkey !in base.members -> "You are no longer a member of this group"
                draft.paidBy !in base.members -> "Choose a payer who is still in this group"
                draft.participants.any {
                    it !in base.members
                } -> "A selected participant left this group. Remove them before saving."
                draft.participants.isEmpty() -> "Select at least one participant"
                else -> split.error
            }
        }
        val loadingError = draftLoadError ?: recoveryError ?: if (draft.initialized &&
            base.myPubkey.isNotEmpty() &&
            draft.authorPubkey != base.myPubkey
        ) {
            "Your identity changed. Close this draft and start a new expense."
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

    companion object {
        private const val MAX_DESCRIPTION_LENGTH = 500
    }
}
