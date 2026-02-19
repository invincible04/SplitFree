package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.HashUtil
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.serialization.json.Json

/**
 * Computes net balances for all members in a group, accounting for snapshots, corrections, deletions, and settlements.
 */
class ComputeBalancesUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decrypt event content on-the-fly. Returns null if key missing or decryption fails. */
    private fun decrypt(event: EventSnapshot, groupKey: String): String? = try {
        encryption.decrypt(event.contentEncrypted, groupKey)
    } catch (_: Exception) {
        null
    }

    /**
     * Compute net balances for all members in a group, accounting for
     * snapshots, corrections, deletions, and settlements.
     *
     * @param groupId target group UUID
     * @return [BalanceResult] with per-member balances and excluded expense UUIDs
     */
    suspend fun computeWithExclusions(groupId: String): BalanceResult {
        val groupKey = groupRepo.getGroupKey(groupId) ?: return BalanceResult(emptyList(), emptySet())
        val events = eventRepo.getEventsByGroup(groupId)
        // Key: (pubkey, currency) -> net amount
        val balances = mutableMapOf<Pair<String, String>, Long>()
        val deleted = mutableSetOf<String>()
        val latestCorrection = mutableMapOf<String, String>()
        val seenSettlementIds = mutableSetOf<String>()

        val snapshotEvent = eventRepo.getLatestEventByType(groupId, "snapshot")
        var snapshotTimestamp = 0L
        if (snapshotEvent != null) {
            try {
                val group = groupRepo.getById(groupId)
                if (group != null &&
                    (
                        snapshotEvent.pubkey == group.createdBy ||
                            (group.createdBy.isEmpty() && snapshotEvent.pubkey in group.members)
                        )
                ) {
                    val content = decrypt(snapshotEvent, groupKey)
                    if (content != null) {
                        val snap = json.decodeFromString<BalanceSnapshot>(content)
                        val localIds = eventRepo.getEventIds(groupId).toSet()
                        if (snap.event_hashes.isNotEmpty()) {
                            val localHashes = localIds.mapTo(HashSet()) { HashUtil.sha256Hex(it) }
                            val matchCount = snap.event_hashes.count { it in localHashes }
                            if (snap.event_hashes.size < 10 ||
                                matchCount.toDouble() / snap.event_hashes.size < 0.8
                            ) {
                                Log.w("ComputeBalances", "Snapshot hash mismatch — ignoring")
                            } else {
                                for (b in snap.balances) {
                                    balances[b.pubkey to b.currency] = b.net
                                }
                                snapshotTimestamp = snap.as_of_timestamp
                            }
                        } else {
                            for (b in snap.balances) {
                                balances[b.pubkey to b.currency] = b.net
                            }
                            snapshotTimestamp = snap.as_of_timestamp
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val relevantEvents =
            if (snapshotTimestamp > 0) {
                events.filter { it.createdAt > snapshotTimestamp }
            } else {
                events
            }

        for (e in relevantEvents) {
            when (e.eventType) {
                "expense_delete" -> e.expenseUuid?.let { deleted.add(it) }
                "expense_correction" -> e.expenseUuid?.let { latestCorrection[it] = e.eventId }
            }
        }

        for (e in relevantEvents) {
            val content = decrypt(e, groupKey) ?: continue
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
                    if (e.pubkey != s.from && e.pubkey != s.to) continue
                    if (!seenSettlementIds.add(s.id)) continue
                    val cur = s.currency.uppercase().trim()
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

    suspend operator fun invoke(groupId: String): List<Balance> = computeWithExclusions(groupId).balances

    private fun applyExpense(expense: Expense, balances: MutableMap<Pair<String, String>, Long>) {
        val cur = expense.currency.uppercase().trim()
        if (expense.splitAmong.any { it.share < 0 }) return
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
