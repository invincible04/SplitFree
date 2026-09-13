package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitfree.data.local.entities.DeliveryEntity

/**
 * Data access for recipient-addressed envelopes carried across nearby mesh sessions.
 *
 * `state` and `source` literals match [DeliveryEntity.STATE_AVAILABLE] / [DeliveryEntity.STATE_CONSUMED]
 * and [DeliveryEntity.SOURCE_AUTHORED] / [DeliveryEntity.SOURCE_CARRIED].
 */
@Dao
interface DeliveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(d: DeliveryEntity): Long

    /** Atomic insert; returns true only if the row was actually inserted (not a duplicate). */
    suspend fun insertIfNew(d: DeliveryEntity): Boolean = insert(d) != -1L

    @Query("SELECT * FROM deliveries WHERE envelopeId = :id")
    suspend fun get(id: String): DeliveryEntity?

    /** Envelopes still holding their JSON, oldest first. */
    @Query("SELECT * FROM deliveries WHERE groupId = :groupId AND state = 0 ORDER BY createdAt ASC, envelopeId ASC")
    suspend fun getAvailable(groupId: String): List<DeliveryEntity>

    /** Every envelope id known for [groupId], consumed or not, for dedup on exchange. */
    @Query("SELECT envelopeId FROM deliveries WHERE groupId = :groupId")
    suspend fun getEnvelopeIds(groupId: String): List<String>

    /** Drop the payload but keep the row as a tombstone so the envelope is never re-accepted. */
    @Query("UPDATE deliveries SET state = 1, envelopeJson = NULL WHERE envelopeId = :id")
    suspend fun markConsumed(id: String)

    /** Number of envelopes this device is carrying for others that are still available. */
    @Query("SELECT COUNT(*) FROM deliveries WHERE groupId = :groupId AND source = 1 AND state = 0")
    suspend fun countCarried(groupId: String): Int

    /** Bytes of envelope JSON this device is carrying for others that are still available. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM deliveries WHERE groupId = :groupId AND source = 1 AND state = 0")
    suspend fun carriedBytes(groupId: String): Long

    /** Evict the [n] carried, still-available envelopes that were received earliest. */
    @Query(
        "DELETE FROM deliveries WHERE envelopeId IN (" +
            "SELECT envelopeId FROM deliveries WHERE groupId = :groupId AND source = 1 AND state = 0 " +
            "ORDER BY receivedAt ASC, envelopeId ASC LIMIT :n)"
    )
    suspend fun evictOldestCarried(groupId: String, n: Int)

    @Query("DELETE FROM deliveries WHERE receivedAt < :cutoff AND source = :source")
    suspend fun deleteOlderThan(cutoff: Long, source: Int)

    @Query("SELECT COUNT(*) FROM deliveries WHERE groupId = :groupId AND state = 0")
    suspend fun countAvailable(groupId: String): Int
}
