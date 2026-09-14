package com.splitfree.data.repository

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.RelaySyncCursorEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelaySyncCursors @Inject constructor(private val db: AppDatabase) {
    // Missing relays start at zero; the group-level lastSyncTimestamp is not evidence of relay coverage.
    suspend fun cursors(groupId: String, recipientPubkey: String): Map<String, Long> =
        db.relaySyncCursorDao().get(groupId, recipientPubkey).associate { it.relayUrl to it.throughTimestamp }

    suspend fun advance(groupId: String, recipientPubkey: String, completed: Set<String>, through: Long) {
        if (completed.isEmpty()) return
        db.withTransaction {
            for (relayUrl in completed) {
                db.relaySyncCursorDao().upsert(RelaySyncCursorEntity(groupId, relayUrl, recipientPubkey, through))
            }
        }
    }
}
