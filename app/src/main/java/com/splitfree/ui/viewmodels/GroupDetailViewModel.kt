package com.splitfree.ui.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.data.nostr.relay.RelayHealthMonitor
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.expense.DebtTransaction
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.ExpenseRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.AuthoredExpense
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.expense.DeleteExpenseUseCase
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the group detail screen (expenses, balances, debts, members, invite link).
 *
 * @property draftRelays the relay list being edited in the relay dialog, or null when no edit is open.
 *   The dialog renders `draftRelays ?: relays`; [GroupDetailViewModel.saveRelays] persists the draft
 *   and [GroupDetailViewModel.cancelRelayEdit] discards it, leaving [relays] untouched.
 * @property balancesAvailable false while the balances cannot be vouched for: the computation failed, the
 *   ledger or group observation died, or the user's own key could not be read. [debts] must then not be
 *   presented as settled or as a zero; the screen shows an unavailable state with a Retry action instead.
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
    val expenses: List<AuthoredExpense> = emptyList(),
    val balancesAvailable: Boolean = true
) {
    /** True once both keys are known and match; `"" == ""` during the initial empty frame is not creator. */
    val isCreator: Boolean get() = myPubkey.isNotEmpty() && myPubkey == createdBy

    fun authoredByMe(identity: ExpenseIdentity): Boolean =
        myPubkey.isNotEmpty() && identity.authorPubkey == myPubkey && expenses.any { it.identity == identity }
}

/**
 * Drives the group detail screen: observes expenses, computes balances,
 * handles settlements, invite links, member removal, and group export.
 *
 * Balances are all or nothing. A failed computation, a dead observation or an unreadable own key marks
 * [GroupDetailUiState.balancesAvailable] false; [retryBalances] resubscribes both observations and the
 * state becomes available again only once the group has applied and a computation has succeeded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    private val deleteExpenseUseCase: DeleteExpenseUseCase,
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

    /** Non-error confirmations for the snackbar, such as a completed deletion. */
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val inviteLinkLoaded = AtomicBoolean(false)

    /** Bumped by [retryBalances]; both observations restart on every bump. */
    private val retryRequests = MutableStateFlow(0L)

    /** False after the group observation or the own-key read fails; a successful [applyGroup] restores it. */
    private var groupAvailable = true

    /** False after the ledger observation or a balance computation fails; a successful computation restores it. */
    private var ledgerAvailable = true

    init {
        viewModelScope.launch {
            retryRequests.flatMapLatest {
                groupRepo.observeById(groupId)
                    .catch { e -> reportGroupFailure(R.string.group_observation_failed, "Group observation", e) }
            }.collect { group ->
                try {
                    applyGroup(group)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportGroupFailure(R.string.group_update_failed, "Apply group update", e)
                }
            }
        }
        viewModelScope.launch {
            retryRequests.collectLatest {
                getExpenses.observeWithAuthors(groupId)
                    .catch { e -> reportLedgerFailure(e) }
                    .collectLatest { allExpenses -> refreshLedger(allExpenses) }
            }
        }
    }

    /** Recomputes balances from a fresh subscription to both the group and the ledger. */
    fun retryBalances() {
        _error.value = null
        retryRequests.update { it + 1 }
    }

    private suspend fun refreshLedger(allExpenses: List<AuthoredExpense>) {
        try {
            val result = computeBalances.computeWithExclusions(groupId)
            val debts = simplifyDebts(result.balances)
            val visible = allExpenses.filter { it.identity !in result.excludedExpenses }
            ledgerAvailable = true
            _uiState.update {
                it.copy(balancesAvailable = groupAvailable, debts = debts, expenses = visible)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The history stays readable; only money derived from it is withheld.
            ledgerAvailable = false
            _uiState.update { it.copy(balancesAvailable = false, debts = emptyList(), expenses = allExpenses) }
            Log.e(TAG, "Failed to compute balances: ${e.message}", e)
            _error.value = e.toUiMessage(R.string.compute_balances_failed)
        }
    }

    private fun applyGroup(group: Group?) {
        val myPub = identity.getPublicKeyHex()
        groupAvailable = true
        _uiState.update {
            it.copy(
                balancesAvailable = ledgerAvailable,
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
     * A failing group query or an unreadable own key leaves [GroupDetailUiState.myPubkey] unknown, so debts
     * cannot be attributed: balances are unavailable until the next successful [applyGroup]. The debts stay in
     * state, hidden, so recovery restores them rather than showing a false zero.
     */
    private fun reportGroupFailure(@StringRes fallback: Int, what: String, e: Throwable) {
        if (e is CancellationException || e !is Exception) throw e
        groupAvailable = false
        _uiState.update { it.copy(balancesAvailable = false) }
        reportObservationFailure(fallback, what, e)
    }

    /** A dead ledger observation can no longer refresh the debts, so they are withdrawn until [retryBalances]. */
    private fun reportLedgerFailure(e: Throwable) {
        if (e is CancellationException || e !is Exception) throw e
        ledgerAvailable = false
        _uiState.update { it.copy(balancesAvailable = false, debts = emptyList()) }
        reportObservationFailure(R.string.expense_observation_failed, "Expense observation", e)
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

    private val deletionInProgress = AtomicBoolean(false)

    /** Publish a deletion for [expenseIdentity]; the ledger and balances update through the expense observer. */
    fun deleteExpense(expenseIdentity: ExpenseIdentity) {
        if (!deletionInProgress.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                check(identity.getPublicKeyHex() == expenseIdentity.authorPubkey) {
                    "Only the creator can delete this expense"
                }
                deleteExpenseUseCase(
                    groupId,
                    expenseIdentity.expenseUuid,
                    expectedAuthorPubkey = expenseIdentity.authorPubkey
                )
                _message.value = UiMessage.Res(R.string.expense_deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Delete expense failed: ${e.message}")
                _error.value = e.toUiMessage(R.string.delete_expense_failed)
            } finally {
                deletionInProgress.set(false)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
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

    /** Append [url] to the draft unless the list would no longer fit an invite link. */
    fun addRelay(url: String) {
        _uiState.update {
            val next = ((it.draftRelays ?: it.relays) + url).distinct()
            if (InviteLinkCodec.fitsInviteLink(next)) it.copy(draftRelays = next) else it
        }
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

    /** Make the draft [RelayDefaults.DEFAULT_RELAYS] and check the ones not already known to be online. */
    fun resetRelays() {
        _uiState.update { it.copy(draftRelays = RelayDefaults.DEFAULT_RELAYS) }
        RelayDefaults.DEFAULT_RELAYS.filterNot { _relayStatuses.value[it] == RelayCheckStatus.ONLINE }
            .forEach(::checkRelay)
    }

    fun checkRelay(url: String) {
        viewModelScope.launch {
            try {
                runRelayCheck(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val host = url.removePrefix("wss://")
                Log.w(TAG, "Relay check failed for $host: ${e.message}")
                recordStatus(url, RelayCheckStatus.OFFLINE)
                _error.value = UiMessage.Res(R.string.relay_check_failed, host)
            }
        }
    }

    /**
     * NIP-11 probe, then a signed write+read round-trip for relays outside [RelayDefaults.KNOWN_RELAYS]. Known
     * relays skip the round-trip: they already passed the write+read acceptance check recorded in
     * [RelayDefaults]. Statuses are informational; only the user changes the list.
     */
    private suspend fun runRelayCheck(url: String) {
        recordStatus(url, RelayCheckStatus.CHECKING)
        relayHealthMonitor.checkRelays(listOf(url))
        val status = relayHealthMonitor.statuses[url]
        if (status?.online != true) {
            recordStatus(url, RelayCheckStatus.OFFLINE)
            return
        }
        _relayInfo.value = _relayInfo.value +
            (url to RelayInfo(status.paid, status.supportsGiftWrap, status.latencyMs))
        if (url in RelayDefaults.KNOWN_RELAYS) {
            recordStatus(url, RelayCheckStatus.ONLINE)
            return
        }
        recordStatus(url, RelayCheckStatus.VERIFYING)
        val testEvent = eventSigner.createSignedEvent("verify-${System.nanoTime()}", "relay_test", "test")
        val verified = relayHealthMonitor.verifyRelayRoundTrip(url, testEvent)
        recordStatus(url, if (verified) RelayCheckStatus.ONLINE else RelayCheckStatus.REJECTED)
    }

    private fun recordStatus(url: String, status: RelayCheckStatus) {
        _relayStatuses.value = _relayStatuses.value + (url to status)
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
