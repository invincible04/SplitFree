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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Computes net balances for all members in a group, accounting for snapshots, corrections, deletions, and settlements.
 *
 * A trusted snapshot (creator-signed and verifiable through its `event_hashes`) seeds the balances with the
 * effect of exactly the events it covers. Every local event the snapshot does NOT cover is then replayed on
 * top, regardless of `createdAt`, so an event the creator had not yet received when snapshotting (offline
 * member, clock skew) is never lost. When a non-covered delete or correction targets an expense whose effect
 * is already inside the snapshot, that covered payload is reversed before the new state is applied.
 */
class ComputeBalancesUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decrypt event content on-the-fly using epoch-aware key lookup. Returns null if key missing or decryption fails. */
    private suspend fun decrypt(event: EventSnapshot, groupId: String, keyCache: MutableMap<Int, String?>): String? {
        val key = keyCache.getOrPut(event.keyEpoch) {
            groupRepo.getGroupKeyForEpoch(groupId, event.keyEpoch)
        } ?: return null
        return try {
            encryption.decrypt(event.contentEncrypted, key)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compute net balances for all members in a group, accounting for
     * snapshots, corrections, deletions, and settlements.
     *
     * @param groupId target group UUID
     * @return [BalanceResult] with per-member balances and the UUIDs of deleted expenses
     */
    suspend fun computeWithExclusions(groupId: String): BalanceResult = withContext(Dispatchers.Default) {
        val events = eventRepo.getEventsByGroup(groupId)
        val keyCache = mutableMapOf<Int, String?>()
        // Key: (pubkey, currency) -> net amount
        val balances = mutableMapOf<Pair<String, String>, Long>()

        val covered = seedFromSnapshot(groupId, keyCache, balances)
        val index = ExpenseIndex(events, covered)

        for (uuid in index.uuids) {
            val inSnapshot = index.snapshotEvent(uuid)
            val wanted = index.desiredEvent(uuid)
            // Same event (or none) on both sides: the snapshot already holds the final state for this UUID.
            if (inSnapshot?.eventId == wanted?.eventId) continue
            if (inSnapshot != null) applyExpenseEvent(inSnapshot, groupId, keyCache, balances, sign = -1)
            if (wanted != null) applyExpenseEvent(wanted, groupId, keyCache, balances, sign = 1)
        }

        applySettlements(events, covered, groupId, keyCache, balances)

        BalanceResult(
            balances = balances.map { (key, net) -> Balance(key.first, net, key.second) },
            excludedExpenseUuids = index.deleted.toSet()
        )
    }

    suspend operator fun invoke(groupId: String): List<Balance> = computeWithExclusions(groupId).balances

    /**
     * Seeds [balances] from the latest snapshot if it is trustworthy: authored by the group creator, carrying
     * at least [MIN_SNAPSHOT_HASHES] event hashes of which at least [MIN_SNAPSHOT_MATCH_RATIO] are known
     * locally. Hashes are compared on their first [HashUtil.EVENT_HASH_PREFIX_LENGTH] characters so snapshots
     * written with full-length SHA-256 hashes stay readable.
     *
     * @return ids of the local events whose effect the snapshot already contains; empty if no snapshot is trusted
     */
    private suspend fun seedFromSnapshot(
        groupId: String,
        keyCache: MutableMap<Int, String?>,
        balances: MutableMap<Pair<String, String>, Long>
    ): Set<String> {
        val snapshotEvent = eventRepo.getLatestEventByType(groupId, "snapshot") ?: return emptySet()
        try {
            val group = groupRepo.getById(groupId)
            // Snapshots are only trusted from a known creator. With an unknown creator there is
            // no trusted author, so any member's snapshot is ignored and balances are replayed.
            val trusted = group != null &&
                group.createdBy.isNotEmpty() &&
                snapshotEvent.pubkey == group.createdBy
            if (!trusted) return emptySet()
            val content = decrypt(snapshotEvent, groupId, keyCache) ?: return emptySet()
            val snap = json.decodeFromString<BalanceSnapshot>(content)
            if (snap.event_hashes.isEmpty()) {
                // Reject snapshots without event hashes; they cannot be verified
                Log.w(TAG, "Snapshot has empty event_hashes, ignoring unverifiable snapshot")
                return emptySet()
            }
            val snapshotPrefixes = snap.event_hashes.mapTo(HashSet()) { it.take(HashUtil.EVENT_HASH_PREFIX_LENGTH) }
            val covered = eventRepo.getEventIds(groupId).filterTo(HashSet()) {
                HashUtil.eventHashPrefix(it) in snapshotPrefixes
            }
            if (snap.event_hashes.size < MIN_SNAPSHOT_HASHES ||
                covered.size.toDouble() / snap.event_hashes.size < MIN_SNAPSHOT_MATCH_RATIO
            ) {
                Log.w(TAG, "Snapshot hash mismatch, ignoring")
                return emptySet()
            }
            for (b in snap.balances) {
                balances[b.pubkey to b.currency] = b.net
            }
            return covered
        } catch (_: Exception) {
            return emptySet()
        }
    }

    /**
     * Decrypts [event] as an [Expense] and applies it with [sign] (+1 to apply, -1 to reverse a payload the
     * snapshot already contains). Unreadable or malformed payloads are skipped.
     */
    private suspend fun applyExpenseEvent(
        event: EventSnapshot,
        groupId: String,
        keyCache: MutableMap<Int, String?>,
        balances: MutableMap<Pair<String, String>, Long>,
        sign: Int
    ) {
        val content = decrypt(event, groupId, keyCache)
        if (content == null) {
            if (sign < 0) Log.w(TAG, "Cannot reverse undecryptable ${event.eventType} ${event.eventId}")
            return
        }
        val expense =
            try {
                json.decodeFromString<Expense>(content)
            } catch (ex: Exception) {
                Log.w(TAG, "Skipping malformed ${event.eventType} event ${event.eventId}: ${ex.message}")
                return
            }
        applyExpense(expense, balances, sign)
    }

    /**
     * Replays every settlement the snapshot does not cover. Settlements carry their id in the `x` tag, so
     * covered settlements seed the dedup set without decryption and a re-sent settlement is never double counted.
     */
    private suspend fun applySettlements(
        events: List<EventSnapshot>,
        covered: Set<String>,
        groupId: String,
        keyCache: MutableMap<Int, String?>,
        balances: MutableMap<Pair<String, String>, Long>
    ) {
        val pending = events.filter { it.eventType == "settlement" && it.eventId !in covered }
        if (pending.isEmpty()) return
        val seenSettlementIds = events
            .filter { it.eventType == "settlement" && it.eventId in covered }
            .mapNotNullTo(HashSet()) { it.expenseUuid }

        for (e in pending) {
            val content = decrypt(e, groupId, keyCache) ?: continue
            val s =
                try {
                    json.decodeFromString<Settlement>(content)
                } catch (ex: Exception) {
                    Log.w(TAG, "Skipping malformed settlement ${e.eventId}: ${ex.message}")
                    continue
                }
            if (e.pubkey != s.from && e.pubkey != s.to) continue
            if (!seenSettlementIds.add(s.id)) continue
            val cur = s.currency.uppercase().trim()
            try {
                balances[s.from to cur] = Math.addExact(balances[s.from to cur] ?: 0L, s.amount)
                balances[s.to to cur] = Math.addExact(balances[s.to to cur] ?: 0L, -s.amount)
            } catch (ex: ArithmeticException) {
                Log.w(TAG, "Skipping overflow settlement ${e.eventId}: ${ex.message}")
            }
        }
    }

    /**
     * Applies [expense] to [balances].
     *
     * @param sign +1 to apply the expense, -1 to reverse a payload that was applied earlier
     * @return false if the expense was skipped (negative share or arithmetic overflow)
     */
    private fun applyExpense(expense: Expense, balances: MutableMap<Pair<String, String>, Long>, sign: Int): Boolean {
        require(sign == 1 || sign == -1) { "sign must be +1 or -1" }
        val cur = expense.currency.uppercase().trim()
        if (expense.splitAmong.any { it.share < 0 }) return false
        return try {
            val payerKey = expense.paidBy to cur
            balances.getOrPut(payerKey) { 0L }
            for (split in expense.splitAmong) {
                val debtorKey = split.pubkey to cur
                balances.getOrPut(debtorKey) { 0L }
                if (split.pubkey != expense.paidBy) {
                    val delta = if (sign > 0) split.share else -split.share
                    balances[payerKey] = Math.addExact(balances.getValue(payerKey), delta)
                    balances[debtorKey] = Math.addExact(balances.getValue(debtorKey), -delta)
                }
            }
            true
        } catch (e: ArithmeticException) {
            Log.w(TAG, "Skipping overflow expense ${expense.id}: ${e.message}")
            false
        }
    }

    /**
     * Metadata-only index of `expense`, `expense_correction` and `expense_delete` events keyed by expense
     * UUID. For each UUID it answers two questions: which payload the snapshot already contains
     * ([snapshotEvent]) and which payload the final balances must contain ([desiredEvent]). Nothing is
     * decrypted here.
     *
     * "Latest" is decided by `createdAt` with ties broken by `eventId`, never by storage order. Duplicate
     * `expense` events sharing an `x` tag collapse to the earliest one.
     */
    private class ExpenseIndex(events: List<EventSnapshot>, covered: Set<String>) {
        val deleted = mutableSetOf<String>()
        private val coveredDeleted = mutableSetOf<String>()
        private val latestCorrection = mutableMapOf<String, EventSnapshot>()
        private val latestCoveredCorrection = mutableMapOf<String, EventSnapshot>()
        private val earliestExpense = mutableMapOf<String, EventSnapshot>()
        private val earliestCoveredExpense = mutableMapOf<String, EventSnapshot>()

        /** Every UUID referenced by an expense, correction or delete event. */
        val uuids: Set<String> get() = deleted + latestCorrection.keys + earliestExpense.keys

        init {
            for (e in events) {
                val uuid = e.expenseUuid ?: continue
                val isCovered = e.eventId in covered
                when (e.eventType) {
                    "expense_delete" -> {
                        deleted.add(uuid)
                        if (isCovered) coveredDeleted.add(uuid)
                    }

                    "expense_correction" -> {
                        latestCorrection.keepLatest(uuid, e)
                        if (isCovered) latestCoveredCorrection.keepLatest(uuid, e)
                    }

                    "expense" -> {
                        val displaced = earliestExpense.keepEarliest(uuid, e)
                        if (displaced != null) {
                            Log.w(
                                TAG,
                                "Duplicate expense event ${displaced.eventId} for $uuid, applying earliest only"
                            )
                        }
                        if (isCovered) earliestCoveredExpense.keepEarliest(uuid, e)
                    }
                }
            }
        }

        /** Event whose payload the trusted snapshot already accounts for under [uuid], or null if none. */
        fun snapshotEvent(uuid: String): EventSnapshot? =
            if (uuid in coveredDeleted) null else (latestCoveredCorrection[uuid] ?: earliestCoveredExpense[uuid])

        /** Event whose payload the final balances must contain under [uuid], or null if none. */
        fun desiredEvent(uuid: String): EventSnapshot? =
            if (uuid in deleted) null else (latestCorrection[uuid] ?: earliestExpense[uuid])

        private fun MutableMap<String, EventSnapshot>.keepLatest(uuid: String, e: EventSnapshot) {
            val current = this[uuid]
            if (current == null || isBefore(current, e)) this[uuid] = e
        }

        /** @return the event that lost to [e] (or [e] itself if it lost), null if [uuid] was unseen */
        private fun MutableMap<String, EventSnapshot>.keepEarliest(uuid: String, e: EventSnapshot): EventSnapshot? {
            val current = this[uuid] ?: run {
                this[uuid] = e
                return null
            }
            return if (isBefore(e, current)) {
                this[uuid] = e
                current
            } else {
                e
            }
        }

        private fun isBefore(a: EventSnapshot, b: EventSnapshot): Boolean =
            EventSnapshot.CANONICAL_ORDER.compare(a, b) < 0
    }

    companion object {
        private const val TAG = "ComputeBalances"

        /** A snapshot with fewer hashes than this cannot be meaningfully verified. */
        private const val MIN_SNAPSHOT_HASHES = 10

        /** Fraction of snapshot hashes that must be known locally for the snapshot to be trusted. */
        private const val MIN_SNAPSHOT_MATCH_RATIO = 0.8
    }
}
