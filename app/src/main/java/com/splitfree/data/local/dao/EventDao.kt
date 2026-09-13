package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.splitfree.data.local.entities.EventEntity
import kotlinx.coroutines.flow.Flow

/**
 * `(eventId, sig)` pair exchanged on a nearby mesh session so peers can prove which events they
 * hold without shipping full rows.
 */
data class EventEvidence(val eventId: String, val sig: String)

/**
 * Data access for Nostr events stored locally.
 *
 * Events are keyed by their Nostr event ID (SHA-256 hash) and grouped by `groupId`.
 * Content remains encrypted at rest; decryption happens on-the-fly via [EventEntity.decryptContent].
 *
 * Queries that feed projections (balances, expense lists, latest meta) only read rows whose
 * `applyState` is [EventEntity.APPLY_STATE_APPLIED]. [getEvent] and [getEventIds] are identity /
 * dedup lookups and deliberately return pending rows too.
 */
@Dao
abstract class EventDao {
    /**
     * Shared insert for sync and local publication. Runs the final tombstone check in the same
     * transaction as the write, so two originals racing one pending delete admit exactly one (the
     * other is rejected, not stored) regardless of interleaving.
     * Returns Room's row ID, -1 for a duplicate, or [REJECTED_DELETED]; existing rows are never changed
     * on either non-insert.
     */
    @Transaction
    open suspend fun insert(event: EventEntity): Long {
        val uuid = event.expenseUuid
        if (uuid != null &&
            event.eventType == "expense" &&
            event.applyState == EventEntity.APPLY_STATE_APPLIED &&
            uuid in getAppliedDeletedExpenseUuidsByAuthor(event.groupId, event.pubkey)
        ) {
            return if (getEvent(event.eventId) != null) -1L else REJECTED_DELETED
        }
        return insertHistory(event)
    }

    /**
     * Backup restore: an original whose delete is already applied is deleted history, not a replay.
     * Dependencies between an original and its author's deletes resolve here in either commit order: an
     * applied original promotes deletes already held pending, and a pending delete whose applied original
     * is already stored is inserted applied. Corrections still use deferred retry; failed rows are never
     * promoted.
     */
    @Transaction
    open suspend fun insertHistory(event: EventEntity): Long {
        val uuid = event.expenseUuid ?: return insertRow(event)
        val applied = event.applyState == EventEntity.APPLY_STATE_APPLIED
        if (event.eventType == "expense_delete" &&
            event.applyState == EventEntity.APPLY_STATE_PENDING &&
            getExpenseByAuthor(uuid, event.groupId, event.pubkey) != null
        ) {
            return insertRow(event.copy(applyState = EventEntity.APPLY_STATE_APPLIED))
        }
        val rowId = insertRow(event)
        if (rowId != -1L && applied && event.eventType == "expense") {
            applyPendingExpenseDeletes(event.groupId, event.pubkey, uuid)
        }
        return rowId
    }

    /** Keep the raw write behind [insert] so every production insertion uses the atomic admission path. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRow(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE groupId = :groupId AND applyState = 0 ORDER BY createdAt ASC, eventId ASC")
    abstract suspend fun getEventsByGroup(groupId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE groupId = :groupId AND applyState = 0 ORDER BY createdAt ASC, eventId ASC")
    abstract fun observeEventsByGroup(groupId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE eventId = :eventId")
    abstract suspend fun getEvent(eventId: String): EventEntity?

    @Query(
        "SELECT * FROM events WHERE groupId = :groupId AND eventType = :type AND applyState = 0 " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    abstract suspend fun getLatestEventByType(groupId: String, type: String): EventEntity?

    @Query("SELECT eventId FROM events WHERE groupId = :groupId")
    abstract suspend fun getEventIds(groupId: String): List<String>

    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND applyState = 0")
    abstract suspend fun getEventCount(groupId: String): Int

    /** Applied-row count as a flow; the nearby coordinator re-advertises to open peers when it changes. */
    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND applyState = 0")
    abstract fun observeEventCount(groupId: String): Flow<Int>

    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId " +
            "AND eventType = 'expense' AND applyState = 0 ORDER BY createdAt ASC, eventId ASC LIMIT 1"
    )
    abstract suspend fun getExpenseByUuid(uuid: String, groupId: String): EventEntity?

    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId AND pubkey = :author " +
            "AND eventType = 'expense' AND applyState = 0 ORDER BY createdAt ASC, eventId ASC LIMIT 1"
    )
    abstract suspend fun getExpenseByAuthor(uuid: String, groupId: String, author: String): EventEntity?

    /**
     * Every applied row [author] published under [uuid] in [groupId] (the original `expense`, each
     * `expense_correction` and any `expense_delete`) in canonical order. Feeds the current-revision
     * projection of one author-qualified expense.
     */
    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId AND pubkey = :author " +
            "AND applyState = 0 ORDER BY createdAt ASC, eventId ASC"
    )
    abstract suspend fun getExpenseHistoryByAuthor(uuid: String, groupId: String, author: String): List<EventEntity>

    /**
     * Rows of [type] that [author] published in [groupId], regardless of apply state. A command identity
     * lookup; the caller matches the relay address carried in `originalEventJson`.
     */
    @Query(
        "SELECT * FROM events WHERE groupId = :groupId AND pubkey = :author AND eventType = :type " +
            "ORDER BY createdAt ASC, eventId ASC"
    )
    abstract suspend fun getEventsByTypeAndAuthor(groupId: String, type: String, author: String): List<EventEntity>

    @Query(
        "SELECT expenseUuid FROM events WHERE groupId = :groupId AND eventType = 'expense_delete' " +
            "AND expenseUuid IS NOT NULL AND applyState = 0"
    )
    abstract suspend fun getDeletedExpenseUuids(groupId: String): List<String>

    /**
     * Author-bound admission tombstones: only applied deletes count. A pending delete still needs its
     * original, and a failed delete never took effect.
     */
    @Query(
        "SELECT expenseUuid FROM events WHERE groupId = :groupId AND pubkey = :author " +
            "AND eventType = 'expense_delete' AND expenseUuid IS NOT NULL AND applyState = 0"
    )
    abstract suspend fun getAppliedDeletedExpenseUuidsByAuthor(groupId: String, author: String): List<String>

    /** All rows of [type] in deterministic order, regardless of apply state. */
    @Query("SELECT * FROM events WHERE groupId = :groupId AND eventType = :type ORDER BY createdAt ASC, eventId ASC")
    abstract suspend fun getEventsByType(groupId: String, type: String): List<EventEntity>

    /**
     * `(eventId, sig)` for every applied or pending row in [groupId]. A pending row (say a rotation
     * waiting for an earlier epoch) is still a signed record a peer may need; only rows whose effect
     * was permanently rejected here are withheld.
     */
    @Query("SELECT eventId, sig FROM events WHERE groupId = :groupId AND applyState != 2")
    abstract suspend fun getEventEvidence(groupId: String): List<EventEvidence>

    /**
     * Replace a `seal:` placeholder signature with the real one (and the full signed JSON) once a
     * peer supplies it. Rows that already carry a real signature are left alone.
     *
     * @return 1 if the row was upgraded, 0 otherwise
     */
    @Query(
        "UPDATE events SET sig = :sig, originalEventJson = :originalJson " +
            "WHERE eventId = :eventId AND sig LIKE 'seal:%'"
    )
    abstract suspend fun upgradeEvidence(eventId: String, sig: String, originalJson: String): Int

    @Query("UPDATE events SET applyState = :state WHERE eventId = :eventId AND applyState != :state")
    abstract suspend fun setApplyState(eventId: String, state: Int)

    /** Rows whose side effects were deferred, oldest first, so `EventProcessor.retryDeferred` can re-run them. */
    @Query("SELECT * FROM events WHERE groupId = :groupId AND applyState = 1 ORDER BY createdAt ASC, eventId ASC")
    abstract suspend fun getPendingEvents(groupId: String): List<EventEntity>

    /** Number of rows in [groupId] still waiting for their side effect. */
    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND applyState = 1")
    abstract suspend fun countPending(groupId: String): Int

    /**
     * Applied money records whose only evidence is a gift-wrap seal (`seal:` signature): readable here, not
     * forwardable. Control records are left out; a peer missing one shows up through its epoch or roster.
     */
    @Query(
        "SELECT eventId FROM events WHERE groupId = :groupId AND applyState = 0 AND sig LIKE 'seal:%' " +
            "AND eventType IN ('expense', 'settlement', 'expense_correction', 'expense_delete')"
    )
    abstract suspend fun getHeldEventIds(groupId: String): List<String>

    /** Rows by [pubkey] in [groupId] still awaiting a dependency; bounds what one author may hold pending. */
    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND pubkey = :pubkey AND applyState = 1")
    abstract suspend fun countPendingByAuthor(groupId: String, pubkey: String): Int

    /** Pending deletes have already passed authentication and payload checks; only their original was missing. */
    @Query(
        "UPDATE events SET applyState = 0 WHERE groupId = :groupId AND pubkey = :author " +
            "AND expenseUuid = :uuid AND eventType = 'expense_delete' AND applyState = 1"
    )
    protected abstract suspend fun applyPendingExpenseDeletes(groupId: String, author: String, uuid: String)

    /** Boolean variant of the shared atomic [insert]; false when nothing was inserted. */
    suspend fun insertIfNew(event: EventEntity): Boolean = insert(event) > 0L

    companion object {
        /** [insert] result for an `expense` whose author already has an applied delete for its uuid. */
        const val REJECTED_DELETED = -2L
    }
}
