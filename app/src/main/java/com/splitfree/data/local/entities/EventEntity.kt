package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["groupId", "createdAt"]),
        Index(value = ["groupId", "eventType"]),
        Index(value = ["expenseUuid"])
    ]
)
data class EventEntity(
    @PrimaryKey val eventId: String, // Nostr event ID (SHA256)
    val groupId: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val contentEncrypted: String,
    val contentDecrypted: String? = null,
    val eventType: String, // expense, settlement, snapshot, expense_correction, expense_delete, group_meta
    val expenseUuid: String? = null,
    val sig: String,
    val syncedToRelays: String = "[]", // JSON array of relay URLs
    val receivedAt: Long,
    val originalEventJson: String? = null // Full signed Nostr event JSON for self-healing re-publish
)
