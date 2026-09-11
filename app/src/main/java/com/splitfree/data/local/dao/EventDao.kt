package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitfree.data.local.entities.EventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for Nostr events stored locally.
 *
 * Events are keyed by their Nostr event ID (SHA-256 hash) and grouped by `groupId`.
 * Content remains encrypted at rest; decryption happens on-the-fly via [EventEntity.decryptContent].
 */
@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE groupId = :groupId ORDER BY createdAt ASC, eventId ASC")
    suspend fun getEventsByGroup(groupId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE groupId = :groupId ORDER BY createdAt ASC, eventId ASC")
    fun observeEventsByGroup(groupId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE eventId = :eventId")
    suspend fun getEvent(eventId: String): EventEntity?

    @Query("SELECT * FROM events WHERE groupId = :groupId AND eventType = :type ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestEventByType(groupId: String, type: String): EventEntity?

    @Query("SELECT eventId FROM events WHERE groupId = :groupId")
    suspend fun getEventIds(groupId: String): List<String>

    @Query("SELECT COUNT(*) FROM events WHERE groupId = :groupId")
    suspend fun getEventCount(groupId: String): Int

    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId " +
            "AND eventType = 'expense' ORDER BY createdAt ASC, eventId ASC LIMIT 1"
    )
    suspend fun getExpenseByUuid(uuid: String, groupId: String): EventEntity?

    @Query(
        "SELECT * FROM events WHERE expenseUuid = :uuid AND groupId = :groupId AND pubkey = :author " +
            "AND eventType = 'expense' ORDER BY createdAt ASC, eventId ASC LIMIT 1"
    )
    suspend fun getExpenseByAuthor(uuid: String, groupId: String, author: String): EventEntity?

    @Query(
        "SELECT expenseUuid FROM events WHERE groupId = :groupId AND eventType = 'expense_delete' AND expenseUuid IS NOT NULL"
    )
    suspend fun getDeletedExpenseUuids(groupId: String): List<String>

    /** Atomic insert; returns true only if the row was actually inserted (not a duplicate). */
    suspend fun insertIfNew(event: EventEntity): Boolean = insert(event) != -1L
}
