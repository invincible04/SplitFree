package com.splitfree.ui.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.group.Group
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
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.toUiMessage
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the group detail screen (expenses, balances, debts, members, invite link).
 *
 * @property draftRelays the relay list being edited in the relay dialog, or null when no edit is open.
 *   The dialog renders `draftRelays ?: relays`; [GroupDetailViewModel.saveRelays] persists the draft
 *   and [GroupDetailViewModel.cancelRelayEdit] discards it, leaving [relays] untouched.
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
    val draftRelays: List<String>? = null,
    val debts: List<DebtTransaction> = emptyList(),
    val expenses: List<Expense> = emptyList()
) {
    /** True once both keys are known and match; `"" == ""` during the initial empty frame is not creator. */
    val isCreator: Boolean get() = myPubkey.isNotEmpty() && myPubkey == createdBy
}

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

    private val _error = MutableStateFlow<UiMessage?>(null)
    val error: StateFlow<UiMessage?> = _error.asStateFlow()

    private val inviteLinkLoaded = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            groupRepo.observeById(groupId)
                .catch { e -> reportObservationFailure(R.string.group_observation_failed, "Group observation", e) }
                .collect { group ->
                    try {
                        applyGroup(group)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportObservationFailure(R.string.group_update_failed, "Apply group update", e)
                    }
                }
        }
        viewModelScope.launch {
            getExpenses.observe(groupId)
                .catch { e -> reportObservationFailure(R.string.expense_observation_failed, "Expense observation", e) }
                .collectLatest { allExpenses ->
                    try {
                        val result = computeBalances.computeWithExclusions(groupId)
                        val excluded = result.excludedExpenseUuids
                        val debts = simplifyDebts(result.balances)
                        val expenses = allExpenses.filter { e -> e.id !in excluded }
                        _uiState.update { it.copy(debts = debts, expenses = expenses) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to compute balances: ${e.message}", e)
                        _error.value = e.toUiMessage(R.string.compute_balances_failed)
                    }
                }
        }
    }

    private fun applyGroup(group: Group?) {
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

    /**
     * A failing Room query or an unreadable identity key must surface as an error,
     * not as an uncaught exception that tears down the ViewModel scope. Cancellation
     * and JVM [Error]s stay fatal.
     */
    private fun reportObservationFailure(@StringRes fallback: Int, what: String, e: Throwable) {
        if (e is CancellationException || e !is Exception) throw e
        Log.e(TAG, "$what failed: ${e.message}", e)
        _error.value = e.toUiMessage(fallback)
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

    /**
     * Record [debt] as paid. The dialog that offered [debt] may be showing a snapshot from before
     * a newer expense or settlement arrived, so the debts are recomputed through the same path the
     * screen uses and [debt] must still be present (same from/to/amount/currency); otherwise the
     * user is asked to review.
     */
    fun recordSettlement(debt: DebtTransaction) {
        if (!settlingInProgress.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val current = simplifyDebts(computeBalances.computeWithExclusions(groupId).balances)
                if (debt !in current) {
                    Log.w(TAG, "Settlement rejected: balances changed since the dialog opened")
                    _uiState.update { it.copy(debts = current) }
                    _error.value = UiMessage.Res(R.string.settlement_stale)
                    return@launch
                }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Settlement failed: ${e.message}")
                _error.value = e.toUiMessage(R.string.settlement_failed)
            } finally {
                settlingInProgress.set(false)
            }
        }
    }

    private val removalInProgress = AtomicBoolean(false)

    fun removeMember(pubkey: String) {
        // A second tap while a rotation is in flight would try to publish epoch N+1 twice.
        if (!removalInProgress.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                rotateGroupKey(groupId, pubkey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remove member failed: ${e.message}")
                _error.value = e.toUiMessage(R.string.remove_member_failed)
            } finally {
                removalInProgress.set(false)
            }
        }
    }

    // --- Relay editing: the dialog works on a draft so Cancel leaves the saved relays untouched ---

    /** Open the relay dialog: copy the saved relays into an editable draft. */
    fun beginRelayEdit() {
        _uiState.update { it.copy(draftRelays = it.draftRelays ?: it.relays) }
    }

    /** Close the relay dialog without saving. */
    fun cancelRelayEdit() {
        _uiState.update { it.copy(draftRelays = null) }
    }

    fun addRelay(url: String) {
        _uiState.update { it.copy(draftRelays = ((it.draftRelays ?: it.relays) + url).distinct()) }
    }

    fun clearError() {
        _error.value = null
    }

    fun removeRelay(url: String) {
        _uiState.update {
            val current = it.draftRelays ?: it.relays
            if (current.size > 1) it.copy(draftRelays = current - url) else it
        }
    }

    fun checkRelay(url: String) {
        val isKnown = url in RelayDefaults.DEFAULT_RELAYS || url in RelayDefaults.FALLBACK_RELAYS
        val host = url.removePrefix("wss://")
        viewModelScope.launch {
            try {
                runRelayCheck(url, host, isKnown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Relay check failed for $host: ${e.message}")
                _relayStatuses.value = _relayStatuses.value +
                    (url to if (isKnown) RelayCheckStatus.IDLE else RelayCheckStatus.OFFLINE)
                _error.value = UiMessage.Res(R.string.relay_check_failed, host)
            }
        }
    }

    private suspend fun runRelayCheck(url: String, host: String, isKnown: Boolean) {
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
            return
        }
        if (status?.online != true) {
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.OFFLINE)
            _error.value = UiMessage.Res(R.string.relay_offline, host)
            return
        }
        _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.VERIFYING)
        val testEvent = eventSigner.createSignedEvent("verify-${System.nanoTime()}", "relay_test", "test")
        if (!relayHealthMonitor.verifyRelayRoundTrip(url, testEvent)) {
            _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.REJECTED)
            _error.value = UiMessage.Res(R.string.relay_write_read_failed, host)
            return
        }
        _relayStatuses.value = _relayStatuses.value + (url to RelayCheckStatus.ONLINE)
    }

    fun checkAllRelays() {
        val state = _uiState.value
        (state.draftRelays ?: state.relays).forEach { checkRelay(it) }
    }

    /** Persist the draft relay list (or the saved list if no draft is open), then close the draft. */
    fun saveRelays(onDone: () -> Unit) {
        viewModelScope.launch {
            val toSave = _uiState.value.let { it.draftRelays ?: it.relays }
            try {
                updateGroupRelays(groupId, toSave)
                _uiState.update { it.copy(draftRelays = null) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update relays: ${e.message}")
                _error.value = e.toUiMessage(R.string.update_relays_failed)
            }
        }
    }

    companion object {
        private const val TAG = "GroupDetailVM"
    }
}
