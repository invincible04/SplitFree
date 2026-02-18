package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitfree.data.local.entities.OutboxEntity

/**
 * Data access for the outbox — events waiting to be published to relays.
 *
 * Events are inserted when created locally and deleted after successful relay publication.
 * Retry count and timestamp are tracked for exponential backoff on failures.
 */
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
    suspend fun incrementRetry(eventId: String, now: Long)
}
