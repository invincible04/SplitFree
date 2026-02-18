package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.usecase.expense.AddExpenseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the add expense screen.
 */
data class AddExpenseUiState(
    val members: List<String> = emptyList(),
    val myPubkey: String = "",
    val error: String? = null,
    val saved: Boolean = false
)

/**
 * Validates input and delegates to [AddExpenseUseCase] for expense creation.
 */
@HiltViewModel
class AddExpenseViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val addExpense: AddExpenseUseCase,
    private val groupRepo: GroupRepository,
    private val identity: IdentityManager
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    private var submitting = false

    init {
        viewModelScope.launch {
            val group = groupRepo.getById(groupId) ?: return@launch
            _uiState.value =
                AddExpenseUiState(
                    members = group.members,
                    myPubkey = identity.getPublicKeyHex()
                )
        }
    }

    fun addExpense(
        description: String,
        amountCents: Long,
        currency: String,
        paidBy: String,
        splitType: SplitType,
        // pubkey -> raw input per split type
        memberInputs: Map<String, Long>
    ) {
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                val members = _uiState.value.members
                // Sort for deterministic remainder allocation across devices
                val sortedMembers = members.sorted()
                val splits = computeSplits(amountCents, splitType, sortedMembers, memberInputs)
                addExpense(
                    groupId = groupId,
                    amount = amountCents,
                    currency = currency,
                    description = description,
                    paidBy = paidBy,
                    splitType = splitType,
                    splitAmong = splits
                )
                _uiState.value = _uiState.value.copy(error = null, saved = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                submitting = false
            }
        }
    }

    private fun computeSplits(
        amount: Long,
        type: SplitType,
        members: List<String>,
        inputs: Map<String, Long>
    ): List<SplitEntry> = when (type) {
        SplitType.EQUAL -> {
            val perPerson = amount / members.size
            val remainder = (amount % members.size).toInt()
            members.mapIndexed { i, pk ->
                SplitEntry(pk, perPerson + if (i < remainder) 1 else 0)
            }
        }

        SplitType.EXACT -> {
            members.mapNotNull { pk ->
                val v = inputs[pk] ?: 0L
                if (v > 0) SplitEntry(pk, v) else null
            }
        }

        SplitType.PERCENTAGE -> {
            val totalPct = inputs.values.sum()
            require(totalPct == 100L) { "Percentages must sum to 100" }
            val active = members.filter { (inputs[it] ?: 0L) > 0 }
            var allocated = 0L
            active.mapIndexed { i, pk ->
                val pct = inputs[pk]!!
                val share =
                    if (i == active.lastIndex) {
                        amount - allocated
                    } else {
                        amount * pct / 100
                    }
                allocated += share
                SplitEntry(pk, share)
            }
        }

        SplitType.SHARES -> {
            val totalUnits = inputs.values.sum()
            require(totalUnits > 0) { "Total shares must be positive" }
            val active = members.filter { (inputs[it] ?: 0L) > 0 }
            var allocated = 0L
            active.mapIndexed { i, pk ->
                val units = inputs[pk]!!
                val share =
                    if (i == active.lastIndex) {
                        amount - allocated
                    } else {
                        amount * units / totalUnits
                    }
                allocated += share
                SplitEntry(pk, share)
            }
        }
    }
}
