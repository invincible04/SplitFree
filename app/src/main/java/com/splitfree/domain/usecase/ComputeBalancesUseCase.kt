package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.domain.model.Balance
import com.splitfree.domain.model.Expense
import com.splitfree.domain.model.Settlement
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class BalanceResult(
    val balances: List<Balance>,
    val excludedExpenseUuids: Set<String>
)

class ComputeBalancesUseCase @Inject constructor(
    private val eventDao: EventDao,
    private val groupRepo: com.splitfree.data.repository.GroupRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun computeWithExclusions(groupId: String): BalanceResult {
        val events = eventDao.getEventsByGroup(groupId)
        // Key: (pubkey, currency) -> net amount
        val balances = mutableMapOf<Pair<String, String>, Long>()
        val deleted = mutableSetOf<String>()
        val latestCorrection = mutableMapOf<String, String>()

        val snapshotEvent = eventDao.getLatestEventByType(groupId, "snapshot")
        var snapshotTimestamp = 0L
        if (snapshotEvent?.contentDecrypted != null) {
            try {
                // Only trust snapshots from group members
                val group = groupRepo.getById(groupId)
                if (group != null && snapshotEvent.pubkey in group.members) {
                    val snap = json.decodeFromString<BalanceSnapshot>(snapshotEvent.contentDecrypted!!)
                    for (b in snap.balances) {
                        balances[b.pubkey to b.currency] = b.net
                    }
                    snapshotTimestamp = snap.as_of_timestamp
                }
            } catch (_: Exception) {}
        }

        val relevantEvents = if (snapshotTimestamp > 0) {
            events.filter { it.createdAt > snapshotTimestamp }
        } else events

        for (e in relevantEvents) {
            e.contentDecrypted ?: continue
            when (e.eventType) {
                "expense_delete" -> e.expenseUuid?.let { deleted.add(it) }
                "expense_correction" -> e.expenseUuid?.let { latestCorrection[it] = e.eventId }
            }
        }

        for (e in relevantEvents) {
            val content = e.contentDecrypted ?: continue
            when (e.eventType) {
                "expense" -> {
                    val uuid = e.expenseUuid ?: continue
                    if (uuid in deleted || uuid in latestCorrection) continue
                    applyExpense(json.decodeFromString<Expense>(content), balances)
                }
                "expense_correction" -> {
                    val originalUuid = e.expenseUuid ?: continue
                    if (originalUuid in deleted) continue
                    if (latestCorrection[originalUuid] != e.eventId) continue
                    applyExpense(json.decodeFromString<Expense>(content), balances)
                }
                "settlement" -> {
                    val s = json.decodeFromString<Settlement>(content)
                    val cur = s.currency
                    balances[s.from to cur] = Math.addExact(balances[s.from to cur] ?: 0L, s.amount)
                    balances[s.to to cur] = Math.addExact(balances[s.to to cur] ?: 0L, -s.amount)
                }
            }
        }

        val excluded = deleted + latestCorrection.keys
        return BalanceResult(
            balances = balances.map { (key, net) -> Balance(key.first, net, key.second) },
            excludedExpenseUuids = excluded
        )
    }

    suspend operator fun invoke(groupId: String): List<Balance> =
        computeWithExclusions(groupId).balances

    private fun applyExpense(expense: Expense, balances: MutableMap<Pair<String, String>, Long>) {
        val cur = expense.currency
        for (split in expense.splitAmong) {
            if (split.pubkey != expense.paidBy) {
                val payerKey = expense.paidBy to cur
                val debtorKey = split.pubkey to cur
                balances[payerKey] = Math.addExact(balances[payerKey] ?: 0L, split.share)
                balances[debtorKey] = Math.addExact(balances[debtorKey] ?: 0L, -split.share)
            }
        }
    }
}
