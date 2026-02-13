package com.splitfree.ui.viewmodels

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.local.EventDao
import com.splitfree.data.repository.ExpenseRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.DebtTransaction
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.Settlement
import com.splitfree.domain.usecase.ComputeBalancesUseCase
import com.splitfree.domain.usecase.ExportGroupUseCase
import com.splitfree.domain.usecase.JoinGroupUseCase
import com.splitfree.domain.usecase.MigrateGroupUseCase
import com.splitfree.domain.usecase.SimplifyDebtsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

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

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepo: GroupRepository,
    private val eventDao: EventDao,
    private val expenseRepo: ExpenseRepository,
    private val computeBalances: ComputeBalancesUseCase,
    private val simplifyDebts: SimplifyDebtsUseCase,
    private val exportGroup: ExportGroupUseCase,
    private val migrateGroup: MigrateGroupUseCase,
    private val identity: IdentityManager
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""
    private val json = Json { ignoreUnknownKeys = true }

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
            eventDao.observeEventsByGroup(groupId).collect { events ->
                val result = computeBalances.computeWithExclusions(groupId)
                val debts = simplifyDebts(result.balances)
                val excluded = result.excludedExpenseUuids
                val expenses = events
                    .filter {
                        it.eventType == "expense" && it.contentDecrypted != null
                                && it.expenseUuid != null && it.expenseUuid !in excluded
                    }
                    .mapNotNull {
                        runCatching { json.decodeFromString<Expense>(it.contentDecrypted!!) }.getOrNull()
                    }
                    .sortedByDescending { it.timestamp }
                _uiState.update { it.copy(debts = debts, expenses = expenses) }
            }
        }
    }

    fun getInviteLink(): String? {
        return inviteLinkCache
    }

    private var inviteLinkCache: String? = null

    private fun loadInviteLink() {
        viewModelScope.launch {
            val group = groupRepo.observeById(groupId).filterNotNull().first()
            val key = groupRepo.getGroupKey(groupId)
            if (key != null) inviteLinkCache = JoinGroupUseCase.createInviteLink(group, key)
        }
    }

    private var settlingInProgress = false

    fun recordSettlement(debt: DebtTransaction) {
        if (settlingInProgress) return
        settlingInProgress = true
        viewModelScope.launch {
            try {
                val group = groupRepo.getById(groupId) ?: return@launch
                val settlement = Settlement(
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
