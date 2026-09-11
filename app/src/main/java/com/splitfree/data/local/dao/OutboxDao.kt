package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitfree.data.local.entities.OutboxEntity
import kotlinx.coroutines.flow.Flow

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

    /** Live count of every row still waiting to reach a relay. */
    @Query("SELECT COUNT(*) FROM outbox")
    fun pendingOutboxCount(): Flow<Int>

    /**
     * Live count of rows that have failed at least 50 publish attempts (`SyncEngine.MAX_RETRIES`,
     * inlined because Room needs a literal). These are still retried, just on a slow schedule.
     */
    @Query("SELECT COUNT(*) FROM outbox WHERE retryCount >= 50")
    fun stuckOutboxCount(): Flow<Int>

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE eventId = :eventId")
    suspend fun delete(eventId: String)

    /**
     * Drop non-critical rows whose most recent activity is older than [cutoff] (unix seconds).
     *
     * Keyed off the last publish attempt, not the event's `createdAt`: an event authored while
     * offline and only just handed to relays is not "old" merely because its timestamp is. Rows
     * never attempted fall back to `createdAt`, which is the closest thing to an enqueue time.
     */
    @Query(
        "DELETE FROM outbox WHERE COALESCE(lastRetryAt, createdAt) < :cutoff" +
            " AND (eventType IS NULL OR eventType NOT IN ('group_meta', 'key_rotation', 'key_revocation'))"
    )
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("UPDATE outbox SET retryCount = retryCount + 1, lastRetryAt = :now WHERE eventId = :eventId")
    suspend fun incrementRetry(eventId: String, now: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE eventId IN (:eventIds)")
    suspend fun countByEventIds(eventIds: List<String>): Int
}
