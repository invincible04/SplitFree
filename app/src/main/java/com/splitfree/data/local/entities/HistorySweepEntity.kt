package com.splitfree.data.local.entities

import androidx.room.Entity

@Entity(tableName = "history_sweeps", primaryKeys = ["groupId", "relayUrl", "recipientPubkey"])
data class HistorySweepEntity(
    val groupId: String,
    val relayUrl: String,
    val recipientPubkey: String,
    val pendingJson: String,
    val attemptedAt: Long,
    val hadUnresolved: Boolean
)
