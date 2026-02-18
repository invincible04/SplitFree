package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.MigrateGroupUseCase
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the group detail screen (expenses, balances, debts, members, invite link).
 */
data class GroupDetailUiState(
    val groupId: String = "",
    val groupName: String = "",
    val memberCount: Int = 1,
    val members: List<String> = emptyList(),
    val createdBy: String = "",
    val myPubkey: String = "",
    val debts: List<DebtTransaction> = emptyList(),
    val expenses: List<Expense> = emptyList()
)

/**
 * Drives the group detail screen: observes expenses, computes balances,
 * handles settlements, invite links, member removal, and group export.
 */
@HiltViewModel
class GroupDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepo: GroupRepository,
    private val expenseRepo: ExpenseRepository,
    private val computeBalances: ComputeBalancesUseCase,
    private val simplifyDebts: SimplifyDebtsUseCase,
    private val exportGroup: ExportGroupUseCase,
    private val migrateGroup: MigrateGroupUseCase,
    private val identity: IdentityManager,
    private val getExpenses: GetExpensesUseCase,
    private val createInviteLink: CreateInviteLinkUseCase
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""

    private val _uiState = MutableStateFlow(GroupDetailUiState(groupId = groupId))
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            groupRepo.observeById(groupId).collect { group ->
                val myPub = identity.getPublicKeyHex()
                _uiState.update {
                    it.copy(
                        groupName = group?.name ?: "Group",
                        memberCount = group?.members?.size ?: 1,
                        members = group?.members ?: emptyList(),
                        createdBy = group?.createdBy ?: "",
                        myPubkey = myPub
                    )
                }
            }
        }
        loadInviteLink()
        viewModelScope.launch {
            getExpenses.observe(groupId).collect { allExpenses ->
                val result = computeBalances.computeWithExclusions(groupId)
                val debts = simplifyDebts(result.balances)
                val excluded = result.excludedExpenseUuids
                val expenses = allExpenses.filter { e -> e.id !in excluded }
                _uiState.update { it.copy(debts = debts, expenses = expenses) }
            }
        }
    }

    private val _inviteLink = MutableStateFlow<String?>(null)
    val inviteLink: StateFlow<String?> = _inviteLink.asStateFlow()

    private fun loadInviteLink() {
        viewModelScope.launch {
            try {
                _inviteLink.value = createInviteLink(groupId)
            } catch (e: Exception) {
                Log.w("GroupDetailVM", "Failed to create invite link: ${e.message}")
            }
        }
    }

    private var settlingInProgress = false

    fun recordSettlement(debt: DebtTransaction) {
        if (settlingInProgress) return
        settlingInProgress = true
        viewModelScope.launch {
            try {
                val group = groupRepo.getById(groupId) ?: return@launch
                val settlement =
                    Settlement(
                        id = UUID.randomUUID().toString(),
                        from = debt.from,
                        to = debt.to,
                        amount = debt.amount,
                        currency = debt.currency,
                        timestamp = System.currentTimeMillis() / 1000
                    )
                expenseRepo.addSettlement(settlement, groupId)
            } finally {
                settlingInProgress = false
            }
        }
    }

    suspend fun exportGroupData(): String = exportGroup(groupId)

    fun removeMember(pubkey: String, onMigrated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val newGroup = migrateGroup(groupId, pubkey)
                onMigrated(newGroup.id)
            } catch (e: Exception) {
                Log.w("GroupDetailVM", "Remove member failed: ${e.message}")
            }
        }
    }
}
