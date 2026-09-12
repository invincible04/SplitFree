package com.splitfree.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.splitfree.R
import com.splitfree.domain.model.sync.ConnectionStatus
import com.splitfree.domain.repository.NostrClientContract
import com.splitfree.domain.usecase.group.GroupSummary
import com.splitfree.domain.usecase.group.ObserveGroupSummariesUseCase
import com.splitfree.ui.util.UiMessage
import com.splitfree.ui.util.toUiMessage
import com.splitfree.util.DebugLog as Log
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the home (groups list) screen.
 *
 * Balances are always per currency: [selectedCurrency] picks which one the hero card and the group rows
 * describe, and nothing is ever summed across currencies. [selectedCurrency] is `null` only while no
 * group has any balance activity yet, in which case the hero shows "nothing to settle" instead of a zero.
 *
 * @property loading true until the first group snapshot has arrived; the screen must not show a zero or
 *   "settled" before then
 * @property groups one summary per group with the current user's per-currency balances
 * @property currencies every currency present in any group's activity, sorted alphabetically
 * @property selectedCurrency the currency the hero and rows describe, or null when [currencies] is empty
 * @property connection relay status to show; starts as connecting so a fresh launch never reads as offline
 * @property error a non-fatal observation failure to surface; the list keeps its last good value
 */
data class GroupsListUiState(
    val loading: Boolean = true,
    val groups: List<GroupSummary> = emptyList(),
    val currencies: List<String> = emptyList(),
    val selectedCurrency: String? = null,
    val connection: ConnectionStatus = ConnectionStatus.Connecting,
    val error: UiMessage? = null
) {
    /** The current user's net in [selectedCurrency] for [summary], or null when the group has no such entry. */
    fun myNet(summary: GroupSummary): Long? = selectedCurrency?.let { summary.myBalances[it] }

    /** Sum of every positive net in [selectedCurrency]: what others owe you, in minor units. */
    val owedMinor: Long
        get() = groups.sumOf { (myNet(it) ?: 0L).coerceAtLeast(0L) }

    /** Sum of every negative net in [selectedCurrency]: what you owe others, as a positive minor amount. */
    val oweMinor: Long
        get() = groups.sumOf { (-(myNet(it) ?: 0L)).coerceAtLeast(0L) }

    /** [owedMinor] minus [oweMinor]: positive = net to receive, negative = net to pay. */
    val netMinor: Long
        get() = owedMinor - oweMinor
}

/**
 * Drives the home screen: observes every group's summary, tracks the relay connection and remembers
 * which currency the user is looking at across process death.
 *
 * The relay status is mirrored as reported, with one exception: once [CONNECTING_GRACE_MS] have passed since
 * this ViewModel was created, a status that is still connecting is presented as offline, so a sync service that
 * never started cannot leave the screen saying "Connecting" forever.
 */
@HiltViewModel
class GroupsListViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    observeGroupSummaries: ObserveGroupSummariesUseCase,
    nostrClient: NostrClientContract
) : ViewModel() {
    private val observationError = MutableStateFlow<UiMessage?>(null)

    /** True once [CONNECTING_GRACE_MS] have elapsed since creation. */
    private val graceElapsed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            delay(CONNECTING_GRACE_MS)
            graceElapsed.value = true
        }
    }

    private val connection: Flow<ConnectionStatus> =
        combine(nostrClient.connectionStatus, graceElapsed) { status, elapsed ->
            if (status == ConnectionStatus.Connecting && elapsed) ConnectionStatus.Offline else status
        }

    /** `null` until the use case has produced its first list. */
    private val summaries: Flow<List<GroupSummary>?> =
        observeGroupSummaries.observe()
            .map<List<GroupSummary>, List<GroupSummary>?> { it }
            .onStart { emit(null) }
            .catch { e ->
                if (e is CancellationException || e !is Exception) throw e
                Log.e(TAG, "Group observation failed: ${e.message}", e)
                observationError.value = e.toUiMessage(R.string.group_observation_failed)
                emit(emptyList())
            }

    val uiState: StateFlow<GroupsListUiState> =
        combine(
            summaries,
            connection,
            savedStateHandle.getStateFlow<String?>(KEY_CURRENCY, null),
            observationError
        ) { groups, connection, saved, error ->
            if (groups == null) {
                GroupsListUiState(loading = true, connection = connection, error = error)
            } else {
                val currencies = groups.flatMapTo(sortedSetOf()) { it.currencies }.toList()
                GroupsListUiState(
                    loading = false,
                    groups = groups,
                    currencies = currencies,
                    selectedCurrency = saved?.takeIf { it in currencies } ?: defaultCurrency(groups, currencies),
                    connection = connection,
                    error = error
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupsListUiState())

    /** Show balances in [code]; remembered across recreation. Unknown codes fall back to the default. */
    fun selectCurrency(code: String) {
        savedStateHandle[KEY_CURRENCY] = code
    }

    fun clearError() {
        observationError.value = null
    }

    /** The currency that appears in the most groups; ties resolve alphabetically. */
    private fun defaultCurrency(groups: List<GroupSummary>, currencies: List<String>): String? {
        val perGroup = groups.flatMap { it.currencies }.groupingBy { it }.eachCount()
        // maxByOrNull keeps the first maximum and currencies is sorted, so a tie already goes alphabetically.
        return currencies.maxByOrNull { perGroup[it] ?: 0 }
    }

    companion object {
        private const val TAG = "GroupsListVM"
        private const val KEY_CURRENCY = "selectedCurrency"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** How long a launch may stay "connecting" before the screen calls it offline. */
        private const val CONNECTING_GRACE_MS = 12_000L
    }
}
