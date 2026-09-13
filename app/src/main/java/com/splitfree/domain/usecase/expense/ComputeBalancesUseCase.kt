package com.splitfree.domain.usecase.expense

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.model.balance.Balance
import com.splitfree.domain.model.balance.BalanceResult
import com.splitfree.domain.model.balance.BalanceSnapshot
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.ExpenseIdentity
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.HashUtil
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Computes net balances for all members in a group, accounting for snapshots, corrections, deletions, and settlements.
 *
 * A trusted snapshot (creator-signed with every covered event present locally) seeds the balances with the
 * effect of exactly the events it covers. Every local event the snapshot does NOT cover is then replayed on
 * top, regardless of `createdAt`, so an event the creator had not yet received when snapshotting (offline
 * member, clock skew) is never lost. When a non-covered delete or correction targets an expense whose effect
 * is already inside the snapshot, that covered payload is reversed before the new state is applied.
 *
 * Expenses are identified by [ExpenseIdentity] `(author, uuid)`, never by UUID alone: a correction or delete
 * only affects the original signed by the same pubkey, so a member reusing (or front-running) someone else's
 * UUID cannot alter or erase that person's expense. Both records are kept and the collision is logged.
 *
 * Money is all or nothing: a money event this device cannot read (missing epoch key, failed decryption,
 * malformed payload) or cannot add without overflow raises [BalanceUnavailableException] rather than being
 * skipped, so a caller never receives a partial total that looks authoritative. Snapshot and coverage are
 * derived from the same ledger list as the replay, never from a second read.
 */
class ComputeBalancesUseCase
@Inject
constructor(
    private val eventRepo: EventRepositoryContract,
    private val groupRepo: GroupRepositoryContract,
    private val encryption: GroupEncryption
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decrypts [event] with the key of its epoch.
     *
     * @throws BalanceUnavailableException if the epoch key is missing or the ciphertext does not open
     */
    private suspend fun decrypt(event: EventSnapshot, groupId: String, keyCache: MutableMap<Int, String>): String {
        val key = keyCache.getOrPut(event.keyEpoch) {
            groupRepo.getGroupKeyForEpoch(groupId, event.keyEpoch)
                ?: throw BalanceUnavailableException("Missing key for epoch ${event.keyEpoch}")
        }
        return try {
            encryption.decrypt(event.contentEncrypted, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BalanceUnavailableException("Cannot decrypt ${event.eventType} ${event.eventId}", e)
        }
    }

    /**
     * Compute net balances for all members in a group from one read of its applied ledger.
     *
     * @param groupId target group UUID
     * @return [BalanceResult] with per-member balances and the exact identities of deleted expenses.
     * @throws BalanceUnavailableException if any money event is unreadable or a total overflows
     */
    suspend fun computeWithExclusions(groupId: String): BalanceResult =
        computeWithExclusions(groupId, eventRepo.getEventsByGroup(groupId))

    /**
     * Compute net balances from an immutable ledger list the caller already holds.
     *
     * [events] is the only source of truth: the latest snapshot, the ids it covers and every replayed event
     * come from this list, so an event arriving after the read can neither be counted nor hashed. With
     * [useSnapshots] false every event is replayed and no snapshot is trusted, which is what a new snapshot
     * needs so that its balances describe exactly the ids it hashes.
     *
     * @param groupId target group UUID
     * @param events applied events of [groupId], in any order
     * @param useSnapshots false to ignore any snapshot in [events] and replay everything
     * @throws BalanceUnavailableException if any money event is unreadable or a total overflows
     */
    suspend fun computeWithExclusions(
        groupId: String,
        events: List<EventSnapshot>,
        useSnapshots: Boolean = true
    ): BalanceResult = withContext(Dispatchers.Default) {
        val keyCache = mutableMapOf<Int, String>()
        // Key: (pubkey, currency) -> net amount
        val balances = mutableMapOf<Pair<String, String>, Long>()

        val covered = if (useSnapshots) seedFromSnapshot(groupId, events, keyCache, balances) else emptySet()
        val index = ExpenseIndex(events, covered)

        for (identity in index.identities) {
            val inSnapshot = index.snapshotEvent(identity)
            val wanted = index.desiredEvent(identity)
            // Same event (or none) on both sides: the snapshot already holds the final state for this expense.
            if (inSnapshot?.eventId == wanted?.eventId) continue
            if (inSnapshot != null) applyExpenseEvent(inSnapshot, groupId, keyCache, balances, sign = -1)
            if (wanted != null) applyExpenseEvent(wanted, groupId, keyCache, balances, sign = 1)
        }

        applySettlements(events, covered, groupId, keyCache, balances)

        BalanceResult(
            balances = balances.map { (key, net) -> Balance(key.first, net, key.second) },
            excludedExpenses = index.excludedExpenses()
        )
    }

    suspend operator fun invoke(groupId: String): List<Balance> = computeWithExclusions(groupId).balances

    /**
     * Seeds [balances] from the latest snapshot in [events] if it is trustworthy: authored by the group creator
     * and carrying at least [MIN_SNAPSHOT_HASHES] distinct [HashUtil.eventHashPrefix] values, one per event it
     * counts, each matching exactly one local event id.
     *
     * Coverage must be complete even when most hashes match: a missing correction or delete changes which
     * covered payload must be reversed, and a missing settlement loses the `(author, id)` needed to dedup a
     * retry. Local events outside the hash list are replayed on top regardless of their timestamps.
     *
     * A snapshot is an optimisation, not a source of truth: one that cannot be read or verified is ignored and
     * every event is replayed instead, so an unreadable snapshot never hides an unreadable ledger.
     *
     * @return ids in [events] whose effect the snapshot already contains; empty if no snapshot is trusted
     */
    private suspend fun seedFromSnapshot(
        groupId: String,
        events: List<EventSnapshot>,
        keyCache: MutableMap<Int, String>,
        balances: MutableMap<Pair<String, String>, Long>
    ): Set<String> {
        val snapshotEvent = events.latestSnapshot() ?: return emptySet()
        try {
            val group = groupRepo.getById(groupId)
            // Snapshots are only trusted from a known creator. With an unknown creator there is
            // no trusted author, so any member's snapshot is ignored and balances are replayed.
            val trusted = group != null &&
                group.createdBy.isNotEmpty() &&
                snapshotEvent.pubkey == group.createdBy
            if (!trusted) return emptySet()
            val content = decrypt(snapshotEvent, groupId, keyCache)
            val snap = json.decodeFromString<BalanceSnapshot>(content)
            val hashes = snap.event_hashes
            if (hashes.size < MIN_SNAPSHOT_HASHES ||
                snap.as_of_event_count != hashes.size ||
                hashes.toSet().size != hashes.size ||
                hashes.any { !EVENT_HASH_PATTERN.matches(it) }
            ) {
                Log.w(TAG, "Snapshot hash list invalid, replaying every event")
                return emptySet()
            }
            val idsByHash = events.mapTo(HashSet()) { it.eventId }.groupBy(HashUtil::eventHashPrefix)
            val covered = HashSet<String>()
            for (hash in hashes) {
                covered += idsByHash[hash]?.singleOrNull() ?: run {
                    Log.w(TAG, "Snapshot covers an event that is missing or ambiguous locally, replaying every event")
                    return emptySet()
                }
            }
            for (b in snap.balances) {
                balances[b.pubkey to b.currency] = b.net
            }
            return covered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot ${snapshotEvent.eventId} unreadable, replaying every event: ${e.message}")
            return emptySet()
        }
    }

    /**
     * Decrypts [event] as an [Expense] and applies it with [sign] (+1 to apply, -1 to reverse a payload the
     * snapshot already contains).
     *
     * @throws BalanceUnavailableException if the payload is unreadable, malformed or overflows a total
     */
    private suspend fun applyExpenseEvent(
        event: EventSnapshot,
        groupId: String,
        keyCache: MutableMap<Int, String>,
        balances: MutableMap<Pair<String, String>, Long>,
        sign: Int
    ) {
        val content = decrypt(event, groupId, keyCache)
        val expense =
            try {
                json.decodeFromString<Expense>(content)
            } catch (ex: Exception) {
                throw BalanceUnavailableException("Malformed ${event.eventType} ${event.eventId}", ex)
            }
        applyExpense(expense, balances, sign)
    }

    /**
     * Replays every settlement the snapshot does not cover. A settlement is identified by `(author pubkey,
     * settlement id)`, never by its id alone: the id travels in the plaintext `x` tag, which every stored
     * settlement row carries (`EventProcessor` rejects one without it), and any member may sign a settlement
     * they are party to, so two members reusing one id are two distinct settlements, while the same author
     * re-sending one settlement (a retry under a new Nostr event id) still counts once. Covered settlements
     * seed the dedup set from `(pubkey, x tag)` without decryption; snapshot admission guarantees every
     * covered row is present locally.
     *
     * @throws BalanceUnavailableException if a settlement is unreadable, malformed or overflows a total
     */
    private suspend fun applySettlements(
        events: List<EventSnapshot>,
        covered: Set<String>,
        groupId: String,
        keyCache: MutableMap<Int, String>,
        balances: MutableMap<Pair<String, String>, Long>
    ) {
        val pending = events.filter { it.eventType == "settlement" && it.eventId !in covered }
        if (pending.isEmpty()) return
        // Key: (author pubkey, settlement id)
        val seenSettlements = events
            .filter { it.eventType == "settlement" && it.eventId in covered }
            .mapNotNullTo(HashSet()) { row -> row.expenseUuid?.let { row.pubkey to it } }

        for (e in pending) {
            val content = decrypt(e, groupId, keyCache)
            val s =
                try {
                    json.decodeFromString<Settlement>(content)
                } catch (ex: Exception) {
                    throw BalanceUnavailableException("Malformed settlement ${e.eventId}", ex)
                }
            if (e.pubkey != s.from && e.pubkey != s.to) continue
            if (!seenSettlements.add(e.pubkey to s.id)) continue
            val cur = s.currency.uppercase().trim()
            try {
                balances[s.from to cur] = Math.addExact(balances[s.from to cur] ?: 0L, s.amount)
                balances[s.to to cur] = Math.addExact(balances[s.to to cur] ?: 0L, -s.amount)
            } catch (ex: ArithmeticException) {
                throw BalanceUnavailableException("Settlement ${e.eventId} overflows a balance", ex)
            }
        }
    }

    /**
     * Applies [expense] to [balances].
     *
     * @param sign +1 to apply the expense, -1 to reverse a payload that was applied earlier
     * @return false if the expense was skipped because of a negative share
     * @throws BalanceUnavailableException if a total overflows
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
            throw BalanceUnavailableException("Expense ${expense.id} overflows a balance", e)
        }
    }

    /**
     * Metadata-only index of `expense`, `expense_correction` and `expense_delete` events keyed by
     * [ExpenseIdentity] `(author, uuid)`. For each identity it answers two questions: which payload the
     * snapshot already contains ([snapshotEvent]) and which payload the final balances must contain
     * ([desiredEvent]). Nothing is decrypted here.
     *
     * Because the key includes the author, a correction or delete signed by B never touches an expense
     * signed by A even when both carry the same UUID; B's events resolve to B's own record (or to nothing).
     * When one UUID is seen under several authors the collision is logged and every record is kept.
     *
     * "Latest" is decided by `createdAt` with ties broken by `eventId`, never by storage order. Duplicate
     * `expense` events from the same author sharing an `x` tag collapse to the earliest one.
     */
    private class ExpenseIndex(events: List<EventSnapshot>, covered: Set<String>) {
        private val deleted = mutableSetOf<ExpenseIdentity>()
        private val coveredDeleted = mutableSetOf<ExpenseIdentity>()
        private val latestCorrection = mutableMapOf<ExpenseIdentity, EventSnapshot>()
        private val latestCoveredCorrection = mutableMapOf<ExpenseIdentity, EventSnapshot>()
        private val earliestExpense = mutableMapOf<ExpenseIdentity, EventSnapshot>()
        private val earliestCoveredExpense = mutableMapOf<ExpenseIdentity, EventSnapshot>()

        /** Every `(author, uuid)` referenced by an expense, correction or delete event. */
        val identities: Set<ExpenseIdentity> get() = deleted + latestCorrection.keys + earliestExpense.keys

        /** UUIDs that appear as an `expense` under more than one author. Both records are preserved. */
        val conflictingUuids: Set<String>

        init {
            val authorsByUuid = mutableMapOf<String, MutableSet<String>>()
            for (e in events) {
                val uuid = e.expenseUuid ?: continue
                val identity = ExpenseIdentity(e.pubkey, uuid)
                val isCovered = e.eventId in covered
                when (e.eventType) {
                    "expense_delete" -> {
                        deleted.add(identity)
                        if (isCovered) coveredDeleted.add(identity)
                    }

                    "expense_correction" -> {
                        latestCorrection.keepLatest(identity, e)
                        if (isCovered) latestCoveredCorrection.keepLatest(identity, e)
                    }

                    "expense" -> {
                        authorsByUuid.getOrPut(uuid) { mutableSetOf() }.add(e.pubkey)
                        val displaced = earliestExpense.keepEarliest(identity, e)
                        if (displaced != null) {
                            Log.w(
                                TAG,
                                "Duplicate expense event ${displaced.eventId} for $identity, applying earliest only"
                            )
                        }
                        if (isCovered) earliestCoveredExpense.keepEarliest(identity, e)
                    }
                }
            }
            conflictingUuids = authorsByUuid.filterValues { it.size > 1 }.keys
            for (uuid in conflictingUuids) {
                val authors = authorsByUuid.getValue(uuid).sorted()
                Log.w(
                    TAG,
                    "Expense uuid $uuid claimed by ${authors.size} authors (${authors.joinToString { it.take(8) }}); " +
                        "keeping every record"
                )
            }
        }

        /** Event whose payload the trusted snapshot already accounts for under [identity], or null if none. */
        fun snapshotEvent(identity: ExpenseIdentity): EventSnapshot? = if (identity in coveredDeleted) {
            null
        } else {
            latestCoveredCorrection[identity] ?: earliestCoveredExpense[identity]
        }

        /** Event whose payload the final balances must contain under [identity], or null if none. */
        fun desiredEvent(identity: ExpenseIdentity): EventSnapshot? = if (identity in deleted) {
            null
        } else {
            latestCorrection[identity] ?: earliestExpense[identity]
        }

        fun excludedExpenses(): Set<ExpenseIdentity> = deleted.toSet()

        private fun MutableMap<ExpenseIdentity, EventSnapshot>.keepLatest(identity: ExpenseIdentity, e: EventSnapshot) {
            val current = this[identity]
            if (current == null || isBefore(current, e)) this[identity] = e
        }

        /** @return the event that lost to [e] (or [e] itself if it lost), null if [identity] was unseen */
        private fun MutableMap<ExpenseIdentity, EventSnapshot>.keepEarliest(
            identity: ExpenseIdentity,
            e: EventSnapshot
        ): EventSnapshot? {
            val current = this[identity] ?: run {
                this[identity] = e
                return null
            }
            return if (isBefore(e, current)) {
                this[identity] = e
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

        /** The only hash form a snapshot may carry: [HashUtil.eventHashPrefix] of an event id. */
        private val EVENT_HASH_PATTERN = Regex("[0-9a-f]{${HashUtil.EVENT_HASH_PREFIX_LENGTH}}")
    }
}

/**
 * Raised when a group's balances cannot be stated in full: a money event is unreadable on this device
 * (missing epoch key, failed decryption, malformed payload) or a total overflows. Callers present the
 * balances as unavailable and offer a retry; they never fall back to a partial total.
 */
class BalanceUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** The `snapshot` event that is latest by [EventSnapshot.CANONICAL_ORDER], or null if the list has none. */
internal fun List<EventSnapshot>.latestSnapshot(): EventSnapshot? =
    filter { it.eventType == "snapshot" }.maxWithOrNull(EventSnapshot.CANONICAL_ORDER)
