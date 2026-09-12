package com.splitfree.domain.usecase.group

import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.withIndex

/**
 * What the home screen needs to know about one group without decrypting anything itself.
 *
 * @property group the group as stored locally
 * @property myBalances the current user's net balance per ISO 4217 currency, in minor units (positive =
 *   others owe you). A currency is absent when the user has no balance entry in it, either because the
 *   group has no activity in that currency or because none of it involved the user.
 * @property currencies every currency in which the group has any balance activity, for any member. Lets the
 *   UI tell "settled in INR" (group has INR history, your net is zero) apart from "no INR expenses".
 * @property hasExpenses true when the group has any balance-affecting activity at all
 */
data class GroupSummary(
    val group: Group,
    val myBalances: Map<String, Long>,
    val hasExpenses: Boolean,
    val currencies: Set<String> = myBalances.keys
)

/**
 * Observes every local group together with the current user's per-currency balance in it.
 *
 * The group list comes from [GroupRepositoryContract.observeAll]; for each group a per-group flow
 * recomputes [ComputeBalancesUseCase.computeWithExclusions] whenever that group's events change. Bursts of
 * incoming events are smoothed with a short debounce so relay catch-up does not thrash the decryptor, but
 * the first computation for a group runs immediately so the screen is not held in a loading state. An
 * empty group list emits `emptyList()` at once.
 *
 * Balance failures for one group are logged and reported as "no balances" for that group rather than
 * tearing down the whole stream; the other groups keep updating.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ObserveGroupSummariesUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val eventRepo: EventRepositoryContract,
    private val computeBalances: ComputeBalancesUseCase,
    private val identity: IdentityContract
) {
    /** @return a stream of one [GroupSummary] per group, in the repository's order */
    fun observe(): Flow<List<GroupSummary>> = groupRepo.observeAll().flatMapLatest { groups ->
        if (groups.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(groups.map { summaryFlow(it) }) { it.toList() }
        }
    }

    private fun summaryFlow(group: Group): Flow<GroupSummary> = eventRepo.observeEventsByGroup(group.id)
        .withIndex()
        // Never delay the first computation; only smooth bursts after it.
        .debounce { if (it.index == 0) 0L else DEBOUNCE_MS }
        .map { it.value }
        .mapLatest { summarize(group) }

    private suspend fun summarize(group: Group): GroupSummary {
        val myPubkey = identity.getPublicKeyHex()
        val balances =
            try {
                computeBalances.computeWithExclusions(group.id).balances
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Balances unavailable for group ${group.id}: ${e.message}")
                return GroupSummary(group = group, myBalances = emptyMap(), hasExpenses = false)
            }
        val mine = balances.filter { it.pubkey == myPubkey }.associate { it.currency to it.net }
        return GroupSummary(
            group = group,
            myBalances = mine,
            hasExpenses = balances.isNotEmpty(),
            currencies = balances.mapTo(LinkedHashSet()) { it.currency }
        )
    }

    companion object {
        private const val TAG = "ObserveGroupSummaries"

        /** Quiet period after an event burst before balances are recomputed. */
        const val DEBOUNCE_MS = 150L
    }
}
