package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Locally stored Nostr event.
 *
 * Content is stored encrypted (`contentEncrypted`) — decryption is performed on-the-fly
 * via [decryptContent] using the group's symmetric key. The `originalEventJson` field
 * preserves the full signed event for self-healing re-publish to relays.
 *
 * @property eventId Nostr event ID (SHA-256 of canonical serialization)
 * @property groupId UUID of the expense group this event belongs to
 * @property eventType one of: expense, settlement, snapshot, expense_correction, expense_delete, group_meta
 * @property expenseUuid optional UUID linking corrections/deletions to the original expense
 * @property originalEventJson full signed Nostr event JSON for self-healing re-publish
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["groupId", "createdAt"]),
        Index(value = ["groupId", "eventType"]),
        Index(value = ["expenseUuid"])
    ]
)
data class EventEntity(
    @PrimaryKey val eventId: String,
    val groupId: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val contentEncrypted: String,
    val eventType: String,
    val expenseUuid: String? = null,
    val sig: String,
    val syncedToRelays: String = "[]",
    val receivedAt: Long,
    val originalEventJson: String? = null
) {
    /**
     * Decrypt content on-the-fly. Never persisted — call each time content is needed.
     * Returns null if decryption fails (wrong key, corrupted data).
     */
    fun decryptContent(decryptor: (String, String) -> String): String? = try {
        decryptor(contentEncrypted, groupId)
    } catch (_: Exception) {
        null
    }
}
