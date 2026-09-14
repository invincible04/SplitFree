package com.splitfree.data.local.entities

import androidx.room.Entity

// Group metadata uses REPLACE, so a cascading group foreign key would erase trusted coverage.
@Entity(tableName = "relay_sync_cursors", primaryKeys = ["groupId", "relayUrl", "recipientPubkey"])
data class RelaySyncCursorEntity(
    val groupId: String,
    val relayUrl: String,
    val recipientPubkey: String,
    val throughTimestamp: Long
)
