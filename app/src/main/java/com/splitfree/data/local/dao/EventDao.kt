package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE groupId = :groupId AND applyState = 0 ORDER BY createdAt ASC, eventId ASC")
    suspend fun getEventsByGroup(groupId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE groupId = :groupId AND applyState = 0 ORDER BY createdAt ASC, eventId ASC")
    fun observeEventsByGroup(groupId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE eventId = :eventId")
    suspend fun getEvent(eventId: String): EventEntity?

    @Query(
        "SELECT * FROM events WHERE groupId = :groupId AND eventType = :type AND applyState = 0 " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getLatestEventByType(groupId: String, type: String): EventEntity?

    @Query("SELECT eventId FROM events WHERE groupId = :groupId")
    suspend fun getEventIds(groupId: String): List<String>

    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND applyState = 0")
    suspend fun getEventCount(groupId: String): Int

    /** Applied-row count as a flow; the nearby coordinator re-advertises to open peers when it changes. */
    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND applyState = 0")
    fun observeEventCount(groupId: String): Flow<Int>

    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId " +
            "AND eventType = 'expense' AND applyState = 0 ORDER BY createdAt ASC, eventId ASC LIMIT 1"
    )
    suspend fun getExpenseByUuid(uuid: String, groupId: String): EventEntity?

    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId AND pubkey = :author " +
            "AND eventType = 'expense' AND applyState = 0 ORDER BY createdAt ASC, eventId ASC LIMIT 1"
    )
    suspend fun getExpenseByAuthor(uuid: String, groupId: String, author: String): EventEntity?

    @Query(
        "SELECT expenseUuid FROM events WHERE groupId = :groupId AND eventType = 'expense_delete' " +
            "AND expenseUuid IS NOT NULL AND applyState = 0"
    )
    suspend fun getDeletedExpenseUuids(groupId: String): List<String>

    /** Author-bound variant of [getDeletedExpenseUuids]; unfiltered by apply state (identity check). */
    @Query(
        "SELECT expenseUuid FROM events WHERE groupId = :groupId AND pubkey = :author " +
            "AND eventType = 'expense_delete' AND expenseUuid IS NOT NULL"
    )
    suspend fun getDeletedExpenseUuidsByAuthor(groupId: String, author: String): List<String>

    /** All rows of [type] in deterministic order, regardless of apply state. */
    @Query("SELECT * FROM events WHERE groupId = :groupId AND eventType = :type ORDER BY createdAt ASC, eventId ASC")
    suspend fun getEventsByType(groupId: String, type: String): List<EventEntity>

    /**
     * `(eventId, sig)` for every applied or pending row in [groupId]. A pending row (say a rotation
     * waiting for an earlier epoch) is still a signed record a peer may need; only rows whose effect
     * was permanently rejected here are withheld.
     */
    @Query("SELECT eventId, sig FROM events WHERE groupId = :groupId AND applyState != 2")
    suspend fun getEventEvidence(groupId: String): List<EventEvidence>

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
    suspend fun upgradeEvidence(eventId: String, sig: String, originalJson: String): Int

    @Query("UPDATE events SET applyState = :state WHERE eventId = :eventId")
    suspend fun setApplyState(eventId: String, state: Int)

    /** Rows whose side effects were deferred, oldest first, so `EventProcessor.retryDeferred` can re-run them. */
    @Query("SELECT * FROM events WHERE groupId = :groupId AND applyState = 1 ORDER BY createdAt ASC, eventId ASC")
    suspend fun getPendingEvents(groupId: String): List<EventEntity>

    /** Number of rows in [groupId] still waiting for their side effect. */
    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId AND applyState = 1")
    suspend fun countPending(groupId: String): Int

    /** Atomic insert; returns true only if the row was actually inserted (not a duplicate). */
    suspend fun insertIfNew(event: EventEntity): Boolean = insert(event) != -1L
}
