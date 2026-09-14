package com.splitfree.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.splitfree.data.local.entities.RelaySyncCursorEntity

@Dao
abstract class RelaySyncCursorDao {
    @Query("SELECT * FROM relay_sync_cursors WHERE groupId = :groupId AND recipientPubkey = :recipientPubkey")
    abstract suspend fun get(groupId: String, recipientPubkey: String): List<RelaySyncCursorEntity>

    @Transaction
    open suspend fun upsert(cursor: RelaySyncCursorEntity) {
        insertIfAbsent(cursor)
        advance(cursor.groupId, cursor.relayUrl, cursor.recipientPubkey, cursor.throughTimestamp)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(cursor: RelaySyncCursorEntity)

    @Query(
        "UPDATE relay_sync_cursors SET throughTimestamp = MAX(throughTimestamp, :throughTimestamp) " +
            "WHERE groupId = :groupId AND relayUrl = :relayUrl AND recipientPubkey = :recipientPubkey"
    )
    protected abstract suspend fun advance(
        groupId: String,
        relayUrl: String,
        recipientPubkey: String,
        throughTimestamp: Long
    )
}
