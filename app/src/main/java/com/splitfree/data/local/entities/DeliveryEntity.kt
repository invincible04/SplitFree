package com.splitfree.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recipient-addressed envelope (gift wrap / key rotation) that this device either authored or
 * is carrying on behalf of another member so it can be handed over on a nearby mesh session.
 *
 * Only the *outer* signed event is stored; couriers cannot open it. Once the intended recipient
 * has consumed the envelope its JSON is dropped but the row is kept as a dedup tombstone.
 *
 * @property envelopeId Nostr event id of the outer (wrapping) event
 * @property groupId group the envelope belongs to
 * @property recipient pubkey the envelope is addressed to (`p` tag)
 * @property eventId logical inner event id hint; author-known, untrusted on couriers
 * @property envelopeJson full signed outer event JSON; null once consumed
 * @property eventType [TYPE_GIFT_WRAP] or [TYPE_KEY_ROTATION]
 * @property state [STATE_AVAILABLE] or [STATE_CONSUMED]
 * @property source [SOURCE_AUTHORED] (this device wrote it) or [SOURCE_CARRIED] (relayed for someone)
 * @property createdAt `created_at` of the outer event (unix seconds)
 * @property receivedAt when this device stored the row (unix seconds); drives courier eviction
 * @property sizeBytes size of [envelopeJson] at insert time; drives courier byte budgets
 */
@Entity(
    tableName = "deliveries",
    indices = [
        Index("groupId", "recipient"),
        Index("groupId", "state")
    ]
)
data class DeliveryEntity(
    @PrimaryKey val envelopeId: String,
    val groupId: String,
    val recipient: String,
    val eventId: String?,
    val envelopeJson: String?,
    val eventType: String,
    val state: Int,
    val source: Int,
    val createdAt: Long,
    val receivedAt: Long,
    val sizeBytes: Int
) {
    companion object {
        const val TYPE_GIFT_WRAP = "gift_wrap"
        const val TYPE_KEY_ROTATION = "key_rotation"

        const val STATE_AVAILABLE = 0
        const val STATE_CONSUMED = 1

        const val SOURCE_AUTHORED = 0
        const val SOURCE_CARRIED = 1
    }
}
