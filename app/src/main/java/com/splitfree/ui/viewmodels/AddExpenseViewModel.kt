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
    val error: String? = null
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

    init {
        viewModelScope.launch {
            val group = groupRepo.getById(groupId) ?: return@launch
            _uiState.value = AddExpenseUiState(
                members = group.members,
                myPubkey = identity.getPublicKey()
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
        viewModelScope.launch {
            try {
                val members = _uiState.value.members
                val splits = computeSplits(amountCents, splitType, members, memberInputs)
                addExpense(
                    groupId = groupId,
                    amount = amountCents,
                    currency = currency,
                    description = description,
                    paidBy = paidBy,
                    splitType = splitType,
                    splitAmong = splits
                )
                _uiState.value = _uiState.value.copy(error = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
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
                val share = if (i == members.lastIndex) amount - allocated
                else amount * (inputs[pk] ?: 0L) / 100
                allocated += share
                SplitEntry(pk, share)
            }
        }
        SplitType.SHARES -> {
            val totalUnits = inputs.values.sum()
            require(totalUnits > 0) { "Total shares must be positive" }
            var allocated = 0L
            members.mapIndexed { i, pk ->
                val share = if (i == members.lastIndex) amount - allocated
                else amount * (inputs[pk] ?: 0L) / totalUnits
                allocated += share
                SplitEntry(pk, share)
            }
        }
    }
}
