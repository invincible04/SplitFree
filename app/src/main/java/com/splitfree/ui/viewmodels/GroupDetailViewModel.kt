package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.GetExpensesUseCase
import com.splitfree.domain.usecase.expense.SimplifyDebtsUseCase
import com.splitfree.domain.usecase.group.CreateInviteLinkUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.group.UpdateGroupRelaysUseCase
import com.splitfree.domain.util.RelayDefaults
import com.splitfree.ui.components.RelayCheckStatus
import com.splitfree.ui.components.RelayInfo
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val memberNames: Map<String, String> = emptyMap(),
    val createdBy: String = "",
    val myPubkey: String = "",
    val relays: List<String> = emptyList(),
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
    private val groupRepo: GroupRepositoryContract,
    private val expenseRepo: ExpenseRepositoryContract,
    private val computeBalances: ComputeBalancesUseCase,
    private val simplifyDebts: SimplifyDebtsUseCase,
    private val rotateGroupKey: RotateGroupKeyUseCase,
    private val identity: IdentityContract,
    private val getExpenses: GetExpensesUseCase,
    private val createInviteLink: CreateInviteLinkUseCase,
    private val updateGroupRelays: UpdateGroupRelaysUseCase,
    private val relayHealthMonitor: RelayHealthMonitor,
    private val eventSigner: EventSigner
) : ViewModel() {
    private val groupId: String = savedStateHandle["groupId"] ?: ""

    private val _uiState = MutableStateFlow(GroupDetailUiState(groupId = groupId))
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    private val _inviteLink = MutableStateFlow<String?>(null)
    val inviteLink: StateFlow<String?> = _inviteLink.asStateFlow()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayCheckStatus>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayCheckStatus>> = _relayStatuses.asStateFlow()

    private val _relayInfo = MutableStateFlow<Map<String, RelayInfo>>(emptyMap())
    val relayInfo: StateFlow<Map<String, RelayInfo>> = _relayInfo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val inviteLinkLoaded = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            groupRepo.observeById(groupId).collect { group ->
                val myPub = identity.getPublicKeyHex()
                _uiState.update {
                    it.copy(
                        groupName = group?.name ?: "Group",
                        memberCount = group?.members?.size ?: 1,
                        members = group?.members ?: emptyList(),
                        memberNames = group?.memberNames ?: emptyMap(),
                        createdBy = group?.createdBy ?: "",
                        myPubkey = myPub,
                        relays = group?.relays ?: emptyList()
                    )
                }
                if (inviteLinkLoaded.compareAndSet(false, true)) {
                    loadInviteLink()
                }
            }
        }
        viewModelScope.launch {
            getExpenses.observe(groupId).collectLatest { allExpenses ->
                try {
                    val result = computeBalances.computeWithExclusions(groupId)
                    val excluded = result.excludedExpenseUuids
                    val debts = simplifyDebts(result.balances)
                    val expenses = allExpenses.filter { e -> e.id !in excluded }
                    _uiState.update { it.copy(debts = debts, expenses = expenses) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to compute balances: ${e.message}", e)
                    _error.value = e.message ?: "Failed to compute balances"
                }
            }
        }
    }

    private fun loadInviteLink() {
        viewModelScope.launch {
            try {
                _inviteLink.value = createInviteLink(groupId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create invite link: ${e.message}")
            }
        }
    }

    private val settlingInProgress = AtomicBoolean(false)

    fun recordSettlement(debt: DebtTransaction) {
        if (!settlingInProgress.compareAndSet(false, true)) return
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
            } catch (e: Exception) {
                Log.w(TAG, "Settlement failed: ${e.message}")
                _error.value = e.message ?: "Settlement failed"
            } finally {
                settlingInProgress.set(false)
            }
        }
    }

    fun removeMember(pubkey: String) {
        viewModelScope.launch {
            try {
                rotateGroupKey(groupId, pubkey)
            } catch (e: Exception) {
                Log.w(TAG, "Remove member failed: ${e.message}")
                _error.value = e.message ?: "Failed to remove member"
            }
        }
    }

    fun addRelay(url: String) {
        _uiState.update { it.copy(relays = (it.relays + url).distinct()) }
    }

    fun clearError() {
        _error.value = null
    }

    fun removeRelay(url: String) {
        val current = _uiState.value.relays
        if (current.size > 1) _uiState.update { it.copy(relays = current - url) }
    }

    fun checkRelay(url: String) {
        val isKnown = url in RelayDefaults.DEFAULT_RELAYS || url in RelayDefaults.FALLBACK_RELAYS
        val host = url.removePrefix("wss://")
        viewModelScope.launch {
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.CHECKING)
            relayHealthMonitor.checkRelays(listOf(url))
            val status = relayHealthMonitor.statuses[url]
            if (status?.online == true) {
                _relayInfo.value = _relayInfo.value +
                    (
                        url to
                            RelayInfo(
                                paid = status.paid,
                                supportsGiftWrap = status.supportsGiftWrap,
                                latencyMs = status.latencyMs
                            )
                        )
            }
            if (isKnown) {
                _relayStatuses.value = _relayStatuses.value +
                    (url to if (status?.online == true) RelayCheckStatus.ONLINE else RelayCheckStatus.IDLE)
                return@launch
            }
            if (status?.online != true) {
                _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.OFFLINE)
                _error.value = "$host is offline or unreachable"
                return@launch
            }
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.VERIFYING)
            val testEvent = eventSigner.createSignedEvent("verify-${System.nanoTime()}", "relay_test", "test")
            if (!relayHealthMonitor.verifyRelayRoundTrip(url, testEvent)) {
                _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.REJECTED)
                _error.value = "$host can't store events — write+read failed"
                return@launch
            }
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.ONLINE)
        }
    }

    fun checkAllRelays() {
        _uiState.value.relays.forEach { checkRelay(it) }
    }

    fun saveRelays(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                updateGroupRelays(groupId, _uiState.value.relays)
                onDone()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update relays: ${e.message}")
                _error.value = e.message ?: "Failed to update relays"
            }
        }
    }

    companion object {
        private const val TAG = "GroupDetailVM"
    }
}
