package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.splitfree.data.local.entities.HistorySweepEntity

@Dao
interface HistorySweepDao {
    @Query("SELECT * FROM history_sweeps WHERE groupId = :groupId AND recipientPubkey = :recipientPubkey")
    suspend fun get(groupId: String, recipientPubkey: String): List<HistorySweepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(sweep: HistorySweepEntity)

    @Query(
        "DELETE FROM history_sweeps WHERE groupId = :groupId AND relayUrl = :relayUrl AND recipientPubkey = :recipientPubkey"
    )
    suspend fun delete(groupId: String, relayUrl: String, recipientPubkey: String)
}
