package com.splitfree.data.local

import androidx.room.*
import com.splitfree.data.local.entities.OutboxEntity

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: OutboxEntity)

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE eventId = :eventId")
    suspend fun delete(eventId: String)

    @Query("DELETE FROM outbox WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("UPDATE outbox SET retryCount = retryCount + 1, lastRetryAt = :now WHERE eventId = :eventId")
    suspend fun incrementRetry(
        eventId: String,
        now: Long,
    )
}
