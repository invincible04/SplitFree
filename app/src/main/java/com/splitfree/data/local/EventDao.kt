package com.splitfree.data.local

import androidx.room.*
import com.splitfree.data.local.entities.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity)

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

    @Query("SELECT * FROM events WHERE expenseUuid = :uuid AND eventType = 'expense' LIMIT 1")
    suspend fun getExpenseByUuid(uuid: String): EventEntity?

    /** Get event IDs that have been deleted (expense_delete events reference the original via expenseUuid). */
    @Query("SELECT expenseUuid FROM events WHERE groupId = :groupId AND eventType = 'expense_delete' AND expenseUuid IS NOT NULL")
    suspend fun getDeletedExpenseUuids(groupId: String): List<String>

    /**
     * Atomically check-then-insert: returns true if the event was new and inserted.
     * Prevents TOCTOU race conditions across concurrent sync paths.
     */
    @Transaction
    suspend fun insertIfNew(event: EventEntity): Boolean {
        val existing = getEvent(event.eventId)
        if (existing != null) return false
        insert(event)
        return true
    }
}