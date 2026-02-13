package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.SplitEntry
import com.splitfree.domain.model.SplitType
import com.splitfree.domain.usecase.AddExpenseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddExpenseUiState(
    val members: List<String> = emptyList(),
    val myPubkey: String = "",
    val error: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
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
            _uiState.value = AddExpenseUiState(
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
        memberInputs: Map<String, Long> // pubkey -> raw input per split type
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
            members.map { pk -> SplitEntry(pk, inputs[pk] ?: 0L) }
        }
        SplitType.PERCENTAGE -> {
            val totalPct = inputs.values.sum()
            require(totalPct == 100L) { "Percentages must sum to 100" }
            var allocated = 0L
            members.mapIndexed { i, pk ->
                val pct = inputs[pk] ?: 0L
                val share = if (pct == 0L) 0L
                else if (i == members.lastIndex) amount - allocated
                else amount * pct / 100
                allocated += share
                SplitEntry(pk, share)
            }
        }
        SplitType.SHARES -> {
            val totalUnits = inputs.values.sum()
            require(totalUnits > 0) { "Total shares must be positive" }
            var allocated = 0L
            members.mapIndexed { i, pk ->
                val units = inputs[pk] ?: 0L
                val share = if (units == 0L) 0L
                else if (i == members.lastIndex) amount - allocated
                else amount * units / totalUnits
                allocated += share
                SplitEntry(pk, share)
            }
        }
    }
}
