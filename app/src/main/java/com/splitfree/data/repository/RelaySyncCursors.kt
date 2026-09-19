package com.splitfree.data.repository

import androidx.room.withTransaction
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.HistorySweepEntity
import com.splitfree.data.local.entities.RelaySyncCursorEntity
import com.splitfree.domain.model.sync.HistoryRange
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

@Singleton
class RelaySyncCursors @Inject constructor(private val db: AppDatabase) {
    data class Sweep(val pending: List<HistoryRange>, val attemptedAt: Long = 0, val hadUnresolved: Boolean = false)

    suspend fun sweeps(groupId: String, recipientPubkey: String): Map<String, Sweep> =
        db.historySweepDao().get(groupId, recipientPubkey).associate { row ->
            val pending = JSONArray(row.pendingJson)
            row.relayUrl to Sweep(
                (0 until pending.length()).map { index ->
                    val range = pending.getJSONArray(index)
                    HistoryRange(range.getLong(0), range.getLong(1), range.getString(2))
                },
                row.attemptedAt,
                row.hadUnresolved
            )
        }

    suspend fun saveSweeps(groupId: String, recipientPubkey: String, sweeps: Map<String, Sweep>) {
        db.withTransaction {
            for ((relay, sweep) in sweeps) {
                val pending = JSONArray()
                sweep.pending.forEach { pending.put(JSONArray(listOf(it.since, it.until, it.idPrefix))) }
                db.historySweepDao().put(
                    HistorySweepEntity(
                        groupId,
                        relay,
                        recipientPubkey,
                        pending.toString(),
                        sweep.attemptedAt,
                        sweep.hadUnresolved
                    )
                )
            }
        }
    }

    // Diagnostic sweep completion times cannot prove absence of later publication with older created_at.
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
